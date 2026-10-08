package com.peexr.sift.data.embedding

import androidx.work.WorkInfo

/**
 * Pure decision logic for determining whether automatic screenshot indexing should be triggered.
 *
 * Keeps logic free of Android framework dependencies (Context, WorkManager) for fast, robust unit testing.
 */
object AutoIndexPolicy {

    /**
     * Determines whether automatic indexing should proceed.
     *
     * @param enabled User setting toggle.
     * @param modelInstalled Whether the embedding model is downloaded and verified on disk.
     * @param pendingCount Number of screenshots awaiting perception or embedding indexing.
     * @param workRunning Whether the unique embedding worker is currently active.
     * @param lastAutoStartMillis Timestamp (millis) when auto-indexing was last initiated.
     * @param nowMillis Current timestamp (millis).
     * @param minGapMillis Minimum cooldown window between automatic indexing runs (default 60,000 ms = 1 minute).
     * @return True only when all preconditions are satisfied and cooldown has elapsed.
     */
    fun shouldAutoIndex(
        enabled: Boolean,
        modelInstalled: Boolean,
        pendingCount: Int,
        workRunning: Boolean,
        lastAutoStartMillis: Long,
        nowMillis: Long,
        minGapMillis: Long = 60_000
    ): Boolean {
        return enabled &&
            modelInstalled &&
            pendingCount > 0 &&
            !workRunning &&
            (nowMillis - lastAutoStartMillis) >= minGapMillis
    }

    /**
     * Checks if any WorkInfo in the provided states is in the [WorkInfo.State.RUNNING] state.
     */
    fun isWorkRunning(states: List<WorkInfo.State>): Boolean {
        return states.any { it == WorkInfo.State.RUNNING }
    }
}

/**
 * Pure decision function determining whether automatic indexing should proceed.
 */
fun shouldAutoIndex(
    enabled: Boolean,
    modelInstalled: Boolean,
    pendingCount: Int,
    workRunning: Boolean,
    lastAutoStartMillis: Long,
    nowMillis: Long,
    minGapMillis: Long = 60_000
): Boolean = AutoIndexPolicy.shouldAutoIndex(
    enabled = enabled,
    modelInstalled = modelInstalled,
    pendingCount = pendingCount,
    workRunning = workRunning,
    lastAutoStartMillis = lastAutoStartMillis,
    nowMillis = nowMillis,
    minGapMillis = minGapMillis
)

/**
 * Returns true if any work in [states] is currently [WorkInfo.State.RUNNING].
 */
fun isWorkRunning(states: List<WorkInfo.State>): Boolean = AutoIndexPolicy.isWorkRunning(states)
