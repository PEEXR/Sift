package com.peexr.sift.data.embedding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.peexr.sift.SiftApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Lightweight periodic background worker that catches up on new screenshots.
 *
 * Runs every 12 hours (flex 4 hours) while charging and battery not low.
 * Syncs MediaStore, checks if unindexed screenshots exist and model is installed,
 * and if so, enqueues [EmbeddingWorker] with KEEP policy.
 *
 * Does NOT perform perception or embedding indexing directly.
 */
class AutoIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AutoIndexWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? SiftApplication
        if (app == null) {
            Log.e(TAG, "ApplicationContext is not an instance of SiftApplication")
            return@withContext Result.success()
        }

        try {
            if (!app.settingsManager.autoIndexNewScreenshots) {
                Log.d(TAG, "Auto-indexing is disabled in settings; skipping catch-up.")
                return@withContext Result.success()
            }

            // Check read media permission quietly
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }

            if (!hasPermission) {
                Log.d(TAG, "Read media permission is not granted; skipping auto-index catch-up quietly.")
                return@withContext Result.success()
            }

            // 1. Sync MediaStore to ingest any new screenshots taken since last run
            app.repository.syncMediaStoreScreenshots()

            // 2. Query pending screenshots count and model installation state
            val pendingCount = app.repository.countNeedingIndexingFlow.first()
            val modelInstalled = app.modelStore.isInstalled()

            // 3. Query WorkManager for in-flight embedding worker
            val workInfos = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork(EmbeddingScheduler.UNIQUE_WORK_NAME)
                .get()
            val workRunning = isWorkRunning(workInfos.map { it.state })

            if (pendingCount > 0 && modelInstalled && !workRunning) {
                Log.i(TAG, "Pending screenshots detected ($pendingCount); triggering non-replacing indexing worker.")
                EmbeddingScheduler.enqueueIndexingNow(app, replace = false)
            } else {
                Log.d(TAG, "Catch-up skipped (pending=$pendingCount, modelInstalled=$modelInstalled, workRunning=$workRunning).")
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Error during periodic auto-indexing catch-up", t)
            Result.success()
        }
    }
}
