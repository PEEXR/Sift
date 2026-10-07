package com.peexr.sift.data.embedding

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.peexr.sift.MainActivity
import com.peexr.sift.SiftApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device benchmark and integration test for [EmbeddingWorker] executing in the background
 * via [WorkManager] on the real app database without any active foreground Activity.
 *
 * Purpose: Evaluates whether the host OS (e.g. Android 16 fast_freezer on specific OEMs)
 * throttles or freezes the background indexing process, while measuring end-to-end embedding throughput.
 *
 * Opt-in: Runs only when the instrumentation argument `runWorker` is set to "true".
 * Duration is configurable via `observeSeconds` (defaults to 180 seconds).
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingWorkerDeviceTest {

    companion object {
        private const val TAG = "WORKERBENCH"
        private const val DEFAULT_OBSERVE_SECONDS = 180L
        private const val POLL_INTERVAL_MS = 5000L
    }

    @Test
    fun runEmbeddingWorkerBackgroundBenchmark() {
        val args = InstrumentationRegistry.getArguments()
        val runWorkerArg = args.getString("runWorker")
        Assume.assumeTrue(
            "EmbeddingWorkerDeviceTest is opt-in; pass '-e runWorker true' to execute.",
            "true".equals(runWorkerArg, ignoreCase = true)
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelStore = ModelStore(context)
        Assume.assumeTrue(
            "Embedding model is not installed; skipping background worker test.",
            modelStore.isInstalled()
        )

        val observeSeconds = args.getString("observeSeconds")?.toLongOrNull() ?: DEFAULT_OBSERVE_SECONDS

        val app = context.applicationContext as SiftApplication
        val dao = app.database.screenshotDao()

        val pendingBefore = runBlocking { dao.getScreenshotsNeedingEmbedding().size }
        Log.i(TAG, "Screenshots needing embedding before test: $pendingBefore")
        Assume.assumeTrue(
            "No screenshots needing embedding in database (pendingBefore == 0); skipping test.",
            pendingBefore > 0
        )

        val startFromForegroundArg = args.getString("startFromForeground") ?: "true"
        val startFromForeground = !"false".equals(startFromForegroundArg, ignoreCase = true)
        Log.i(TAG, "Mode: startFromForeground=$startFromForeground")

        if (startFromForeground) {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            Log.i(TAG, "ActivityScenario launched. Enqueuing indexing now...")
            EmbeddingScheduler.enqueueIndexingNow(context)

            val waitStartNs = System.nanoTime()
            val maxWaitNs = 20_000_000_000L // max 20 s
            while (System.nanoTime() - waitStartNs < maxWaitNs) {
                val workInfos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(EmbeddingScheduler.UNIQUE_WORK_NAME)
                    .get()
                val state = workInfos.firstOrNull()?.state
                if (state == WorkInfo.State.RUNNING || state?.isFinished == true) {
                    Log.i(TAG, "WorkInfo reached state: $state")
                    break
                }
                try {
                    Thread.sleep(500L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            try {
                Thread.sleep(5000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            scenario.close()
            Log.i(TAG, "ActivityScenario closed; app in background while work continues.")
        } else {
            Log.i(TAG, "Enqueuing indexing now via EmbeddingScheduler.enqueueIndexingNow (background mode)...")
            EmbeddingScheduler.enqueueIndexingNow(context)
        }

        val startNs = System.nanoTime()
        val maxDurationNs = observeSeconds * 1_000_000_000L

        while (true) {
            val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(EmbeddingScheduler.UNIQUE_WORK_NAME)
                .get()
            val workInfo = workInfos.firstOrNull()
            val state = workInfo?.state
            val current = workInfo?.progress?.getInt(EmbeddingWorker.KEY_CURRENT, -1) ?: -1
            val total = workInfo?.progress?.getInt(EmbeddingWorker.KEY_TOTAL, -1) ?: -1

            Log.i(
                TAG,
                "Elapsed: %.1fs | State: %s | Progress: %d / %d".format(elapsedSec, state, current, total)
            )

            if (workInfo != null && state?.isFinished == true) {
                Log.i(TAG, "Work finished with state: $state")
                break
            }

            if ((System.nanoTime() - startNs) >= maxDurationNs) {
                Log.i(TAG, "Observation window of ${observeSeconds}s elapsed.")
                break
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        WorkManager.getInstance(context)
            .cancelUniqueWork(EmbeddingScheduler.UNIQUE_WORK_NAME)
            .result
            .get()

        val pendingAfter = runBlocking { dao.getScreenshotsNeedingEmbedding().size }
        val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
        val embedded = pendingBefore - pendingAfter
        val secondsPerImage = if (embedded > 0) elapsedSec / embedded.toDouble() else 0.0

        Log.i(
            TAG,
            "Results: pendingBefore=%d, pendingAfter=%d, embedded=%d, elapsed=%.2fs, secondsPerImage=%.2fs/img".format(
                pendingBefore, pendingAfter, embedded, elapsedSec, secondsPerImage
            )
        )

        assertTrue(
            "Expected embedded > 0, but got embedded=$embedded (pendingBefore=$pendingBefore, pendingAfter=$pendingAfter)",
            embedded > 0
        )
    }
}
