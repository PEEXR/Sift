package com.peexr.sift.data.embedding

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.peexr.sift.SiftApplication
import com.peexr.sift.data.search.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker that iterates through screenshots missing an embedding vector,
 * generates a 128-d multimodal embedding for each via [EmbeddingEngine], and stores it
 * in the Room database via [ScreenshotDao.updateEmbedding].
 *
 * Runs only when the model is in [ModelState.Ready] (skips cleanly with [Result.success] otherwise).
 * Checks cancellation/[isStopped] between items, and handles per-item exceptions gracefully so
 * that a single corrupted or unreadable screenshot does not fail the entire batch.
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
        val isReady = modelStore.state.value is ModelState.Ready || modelStore.isInstalled()
        if (!isReady) {
            Log.i(TAG, "Embedding model is not ready. Skipping indexing work.")
            return@withContext Result.success()
        }

        val database = app.database
        val dao = database.screenshotDao()
        val embeddingEngine = app.embeddingEngine

        val pending = dao.getScreenshotsNeedingEmbedding()
        if (pending.isEmpty()) {
            Log.d(TAG, "No screenshots requiring embedding.")
            return@withContext Result.success()
        }

        val total = pending.size
        var processed = 0
        Log.i(TAG, "Beginning background embedding for $total screenshots.")

        try {
            setForeground(createForegroundInfo(0, total))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground service; continuing indexing best-effort in background", e)
        }

        for (batch in pending.chunked(BATCH_SIZE)) {
            if (isStopped) {
                Log.i(TAG, "Worker stopped before batch, requesting retry.")
                return@withContext Result.retry()
            }

            for (item in batch) {
                if (isStopped) {
                    Log.i(TAG, "Worker stopped during batch, requesting retry.")
                    return@withContext Result.retry()
                }

                try {
                    val uri = Uri.parse(item.uri)
                    val embedding = embeddingEngine.embedImage(uri)
                    if (embedding != null) {
                        dao.updateEmbedding(item.id, embedding.toByteArray())
                    } else {
                        Log.w(TAG, "Could not compute embedding for screenshot id=${item.id}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error generating embedding for screenshot id=${item.id}", e)
                }

                processed++
                setProgress(
                    workDataOf(
                        KEY_CURRENT to processed,
                        KEY_TOTAL to total,
                        KEY_PROGRESS to ((processed * 100) / total)
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

        Log.i(TAG, "Finished background embedding indexing ($processed/$total processed).")
        Result.success()
    }
}

/**
 * Scheduling helpers for [EmbeddingWorker].
 */
object EmbeddingScheduler {
    const val UNIQUE_WORK_NAME = "com.peexr.sift.embedding_indexing"

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
     * constraints for a user-triggered run. Uses [ExistingWorkPolicy.REPLACE] so that
     * any pending idle-constrained worker is replaced immediately.
     */
    fun enqueueIndexingNow(context: Context): Operation {
        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .build()

        return WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
