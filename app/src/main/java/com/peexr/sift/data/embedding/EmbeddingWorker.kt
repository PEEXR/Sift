package com.peexr.sift.data.embedding

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.peexr.sift.SiftApplication
import java.util.concurrent.TimeUnit
import com.peexr.sift.data.local.IndexingRow
import com.peexr.sift.data.search.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker that performs unified on-device indexing (OCR text recognition and multimodal embeddings)
 * for screenshots.
 *
 * Runs ML Kit OCR on unprocessed screenshots regardless of model status, and generates 128-d multimodal
 * embeddings via [EmbeddingEngine] when the model is in [ModelState.Ready] or installed.
 *
 * Checks cancellation/[isStopped] between items, and handles per-item exceptions gracefully so
 * that a single corrupted or unreadable screenshot does not fail the entire indexing run.
 */
class EmbeddingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "EmbeddingWorker"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_PROGRESS = "progress"
        const val KEY_BUDGET = "budget"
        const val BATCH_SIZE = 25
        const val NOTIFICATION_ID = 4201
        private const val CHANNEL_ID = "sift_indexing"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0, 0)
    }

    fun createForegroundInfo(done: Int, total: Int): ForegroundInfo {
        val channelId = CHANNEL_ID
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screenshot indexing",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Indexing screenshots")
            .setContentText("$done / $total")
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (total > 0) {
            builder.setProgress(total, done, false)
        }

        val notification = builder.build()

        return when {
            Build.VERSION.SDK_INT >= 35 -> {
                ForegroundInfo(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
            }
            Build.VERSION.SDK_INT >= 29 -> {
                ForegroundInfo(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? SiftApplication
        if (app == null) {
            Log.e(TAG, "ApplicationContext is not an instance of SiftApplication")
            return@withContext Result.failure()
        }

        val modelStore = app.modelStore
        val canEmbed = modelStore.state.value is ModelState.Ready || modelStore.isInstalled()

        val database = app.database
        val dao = database.screenshotDao()

        val pending = dao.getScreenshotsNeedingIndexing()
        val itemsToProcess = if (canEmbed) {
            pending
        } else {
            pending.filter { !it.isProcessed }
        }

        if (itemsToProcess.isEmpty()) {
            Log.d(TAG, "No screenshots requiring indexing work (canEmbed=$canEmbed).")
            return@withContext Result.success()
        }

        val embeddingEngine = if (canEmbed) app.embeddingEngine else null
        val total = itemsToProcess.size
        var processed = 0
        var embeddedCount = 0

        val settings = app.settingsManager
        val quality = settings.indexingQuality
        var measuredSec = settings.measuredSecPerImageAt280
        val itemsNeedingEmbedding = itemsToProcess.count { it.needsEmbedding }
        var chosenBudget = resolveBudget(quality, measuredSec, itemsNeedingEmbedding)

        var isCalibrating = canEmbed && quality == IndexingQuality.AUTO && measuredSec == null && itemsNeedingEmbedding > 0
        val calibrationSamples = mutableListOf<Double>()

        Log.i(TAG, "Beginning background indexing for $total screenshots ($itemsNeedingEmbedding need embedding, chosen budget=$chosenBudget, sec/image at 280=${measuredSec ?: "unmeasured"}).")

        try {
            setForeground(createForegroundInfo(0, total))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground service; continuing indexing best-effort in background", e)
        }

        fun finalizeMeasurement() {
            if (isCalibrating && calibrationSamples.isNotEmpty()) {
                val median = computeMedian(calibrationSamples)
                measuredSec = updateMeasurement(previous = null, observedSeconds = median, budgetUsed = 140)
                settings.measuredSecPerImageAt280 = measuredSec
                isCalibrating = false
            } else if (measuredSec != null) {
                settings.measuredSecPerImageAt280 = measuredSec
            }
        }

        for (batch in itemsToProcess.chunked(BATCH_SIZE)) {
            if (isStopped) {
                finalizeMeasurement()
                Log.i(TAG, "Worker stopped before batch, requesting retry.")
                return@withContext Result.retry()
            }

            for (item in batch) {
                if (isStopped) {
                    finalizeMeasurement()
                    Log.i(TAG, "Worker stopped during batch, requesting retry.")
                    return@withContext Result.retry()
                }

                val uri = Uri.parse(item.uri)

                // 1. ML Kit OCR & perception if not already processed
                if (!item.isProcessed) {
                    try {
                        val perceptionResult = app.perceptionEngine.analyzeImage(uri)
                        if (perceptionResult.isSuccess) {
                            val perception = perceptionResult.getOrNull()!!
                            val labelsJson = perception.visualLabels.joinToString(",")
                            dao.updatePerception(item.id, perception.ocrText, labelsJson)
                        } else {
                            Log.w(TAG, "Perception analysis failed for screenshot id=${item.id}", perceptionResult.exceptionOrNull())
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error running perception for screenshot id=${item.id}", e)
                    }
                }

                // 2. Multimodal embedding if needed and model is ready
                if (item.needsEmbedding && canEmbed && embeddingEngine != null) {
                    val budgetToUse = if (isCalibrating) 140 else chosenBudget
                    try {
                        val t0 = SystemClock.elapsedRealtime()
                        val embedding = embeddingEngine.embedImage(uri, maxSoftTokens = budgetToUse)
                        val elapsedSec = (SystemClock.elapsedRealtime() - t0) / 1000.0

                        if (embedding != null) {
                            dao.updateEmbedding(item.id, embedding.toByteArray())
                            embeddedCount++

                            if (isCalibrating) {
                                calibrationSamples.add(elapsedSec)
                                if (calibrationSamples.size >= 3) {
                                    val remainingSamples = calibrationSamples.drop(1)
                                    val median = computeMedian(remainingSamples)
                                    measuredSec = updateMeasurement(previous = null, observedSeconds = median, budgetUsed = 140)
                                    settings.measuredSecPerImageAt280 = measuredSec
                                    isCalibrating = false
                                    val remainingPending = (itemsNeedingEmbedding - embeddedCount).coerceAtLeast(0)
                                    chosenBudget = resolveBudget(quality, measuredSec, remainingPending)
                                    Log.i(TAG, "Calibration completed: median ${median}s at budget 140 -> secPerImageAt280=${measuredSec}s. New budget: $chosenBudget")
                                }
                            } else {
                                measuredSec = updateMeasurement(previous = measuredSec, observedSeconds = elapsedSec, budgetUsed = budgetToUse)
                                if (embeddedCount % 10 == 0) {
                                    settings.measuredSecPerImageAt280 = measuredSec
                                }
                            }
                        } else {
                            Log.w(TAG, "Could not compute embedding for screenshot id=${item.id}")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating embedding for screenshot id=${item.id}", e)
                    }
                }

                processed++
                setProgress(
                    workDataOf(
                        KEY_CURRENT to processed,
                        KEY_TOTAL to total,
                        KEY_PROGRESS to ((processed * 100) / total),
                        KEY_BUDGET to (if (isCalibrating) 140 else chosenBudget)
                    )
                )

                if (processed % 5 == 0 || processed == total) {
                    try {
                        setForeground(createForegroundInfo(processed, total))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update foreground notification at $processed/$total", e)
                    }
                }
            }
        }

        finalizeMeasurement()
        Log.i(TAG, "Finished background indexing ($processed/$total processed, $embeddedCount embedded, budget: $chosenBudget, sec/image at 280: $measuredSec).")

        if (embeddedCount > 0) {
            try {
                app.repository.refreshAutoCollections()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing auto collections after indexing", e)
            }
        }

        Result.success()
    }

    private fun computeMedian(samples: List<Double>): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}

/**
 * Scheduling helpers for [EmbeddingWorker].
 */
object EmbeddingScheduler {
    const val UNIQUE_WORK_NAME = "com.peexr.sift.embedding_indexing"

    const val PERIODIC_AUTO_INDEX_WORK_NAME = "com.peexr.sift.auto_index_periodic"

    /**
     * Builds standard background indexing constraints: device charging and idle.
     */
    fun buildConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build()
    }

    /**
     * Enqueues background screenshot embedding indexing work with battery-charging
     * and device-idle constraints, using [ExistingWorkPolicy.KEEP] per RFC.
     */
    fun enqueueIndexing(context: Context): Operation {
        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setConstraints(buildConstraints())
            .build()

        return WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Enqueues immediate screenshot embedding indexing work without charging or idle
     * constraints.
     *
     * @param replace If true, uses [ExistingWorkPolicy.REPLACE] for manual runs to replace any pending
     *                idle-constrained work. If false, uses [ExistingWorkPolicy.KEEP] for automated runs
     *                so in-flight indexing is never interrupted or restarted.
     */
    fun enqueueIndexingNow(context: Context, replace: Boolean = true): Operation {
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .build()

        return WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            policy,
            request
        )
    }

    /**
     * Enqueues or cancels the periodic background catch-up indexing work.
     *
     * When enabled, enqueues a periodic work request (every 12h, flex 4h) with charging
     * and battery-not-low constraints using [ExistingPeriodicWorkPolicy.KEEP].
     * When disabled, cancels the unique periodic work.
     */
    fun syncAutoIndexSchedule(context: Context, enabled: Boolean): Operation {
        val workManager = WorkManager.getInstance(context)
        return if (enabled) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoIndexWorker>(
                12L, TimeUnit.HOURS,
                4L, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                PERIODIC_AUTO_INDEX_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } else {
            workManager.cancelUniqueWork(PERIODIC_AUTO_INDEX_WORK_NAME)
        }
    }
}
