package com.peexr.sift.data.embedding

import androidx.work.WorkInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoIndexPolicyTest {

    @Test
    fun shouldAutoIndex_whenAllConditionsMet_returnsTrue() {
        val result = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = 5,
            workRunning = false,
            lastAutoStartMillis = 100_000L,
            nowMillis = 200_000L,
            minGapMillis = 60_000L
        )
        assertTrue(result)
    }

    @Test
    fun shouldAutoIndex_whenDisabled_returnsFalse() {
        val result = shouldAutoIndex(
            enabled = false,
            modelInstalled = true,
            pendingCount = 5,
            workRunning = false,
            lastAutoStartMillis = 100_000L,
            nowMillis = 200_000L,
            minGapMillis = 60_000L
        )
        assertFalse("Should return false when auto-indexing setting is disabled", result)
    }

    @Test
    fun shouldAutoIndex_whenModelNotInstalled_returnsFalse() {
        val result = shouldAutoIndex(
            enabled = true,
            modelInstalled = false,
            pendingCount = 5,
            workRunning = false,
            lastAutoStartMillis = 100_000L,
            nowMillis = 200_000L,
            minGapMillis = 60_000L
        )
        assertFalse("Should return false when embedding model is not installed", result)
    }

    @Test
    fun shouldAutoIndex_whenPendingCountIsZero_returnsFalse() {
        val result = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = 0,
            workRunning = false,
            lastAutoStartMillis = 100_000L,
            nowMillis = 200_000L,
            minGapMillis = 60_000L
        )
        assertFalse("Should return false when no pending screenshots require indexing", result)
    }

    @Test
    fun shouldAutoIndex_whenPendingCountIsNegative_returnsFalse() {
        val result = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = -1,
            workRunning = false,
            lastAutoStartMillis = 100_000L,
            nowMillis = 200_000L,
            minGapMillis = 60_000L
        )
        assertFalse("Should return false when pending count is negative", result)
    }

    @Test
    fun shouldAutoIndex_whenWorkIsRunning_returnsFalse() {
        val result = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = 5,
            workRunning = true,
            lastAutoStartMillis = 100_000L,
            nowMillis = 200_000L,
            minGapMillis = 60_000L
        )
        assertFalse("Should return false when indexing work is already running", result)
    }

    @Test
    fun shouldAutoIndex_whenWithinMinGapCooldown_returnsFalse() {
        val result = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = 5,
            workRunning = false,
            lastAutoStartMillis = 100_000L,
            nowMillis = 130_000L, // 30s gap < 60s
            minGapMillis = 60_000L
        )
        assertFalse("Should return false when within the minimum gap cooldown", result)
    }

    @Test
    fun shouldAutoIndex_minGapBoundary_exactGap_returnsTrue() {
        val result = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = 5,
            workRunning = false,
            lastAutoStartMillis = 100_000L,
            nowMillis = 160_000L, // Exactly 60_000 ms diff
            minGapMillis = 60_000L
        )
        assertTrue("Should return true when now - lastAutoStart == minGap", result)
    }

    @Test
    fun shouldAutoIndex_minGapBoundary_oneMillisBelowGap_returnsFalse() {
        val result = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = 5,
            workRunning = false,
            lastAutoStartMillis = 100_000L,
            nowMillis = 159_999L, // 59_999 ms diff < 60_000
            minGapMillis = 60_000L
        )
        assertFalse("Should return false when now - lastAutoStart is 1ms below minGap", result)
    }

    @Test
    fun shouldAutoIndex_minGapBoundary_oneMillisAboveGap_returnsTrue() {
        val result = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = 5,
            workRunning = false,
            lastAutoStartMillis = 100_000L,
            nowMillis = 160_001L, // 60_001 ms diff > 60_000
            minGapMillis = 60_000L
        )
        assertTrue("Should return true when now - lastAutoStart is 1ms above minGap", result)
    }

    @Test
    fun shouldAutoIndex_defaultMinGap_respectsSixtySeconds() {
        val belowGap = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = 1,
            workRunning = false,
            lastAutoStartMillis = 0L,
            nowMillis = 59_999L
        )
        assertFalse("Default minGap (60s) should reject 59_999ms gap", belowGap)

        val atGap = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = 1,
            workRunning = false,
            lastAutoStartMillis = 0L,
            nowMillis = 60_000L
        )
        assertTrue("Default minGap (60s) should accept 60_000ms gap", atGap)
    }

    @Test
    fun shouldAutoIndex_initialRun_withZeroLastStart_returnsTrue() {
        val result = shouldAutoIndex(
            enabled = true,
            modelInstalled = true,
            pendingCount = 1,
            workRunning = false,
            lastAutoStartMillis = 0L,
            nowMillis = 120_000L
        )
        assertTrue("Initial run with lastAutoStartMillis=0 should proceed", result)
    }

    @Test
    fun isWorkRunning_withRunningState_returnsTrue() {
        assertTrue(isWorkRunning(listOf(WorkInfo.State.RUNNING)))
        assertTrue(isWorkRunning(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)))
        assertTrue(isWorkRunning(listOf(WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED)))
    }

    @Test
    fun isWorkRunning_withoutRunningState_returnsFalse() {
        assertFalse(isWorkRunning(emptyList()))
        assertFalse(isWorkRunning(listOf(WorkInfo.State.ENQUEUED)))
        assertFalse(
            isWorkRunning(
                listOf(
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.SUCCEEDED,
                    WorkInfo.State.FAILED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.CANCELLED
                )
            )
        )
    }
}
