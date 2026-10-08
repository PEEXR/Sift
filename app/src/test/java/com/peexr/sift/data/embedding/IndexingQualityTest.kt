package com.peexr.sift.data.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IndexingQualityTest {

    @Test
    fun chooseAutoBudget_nullMeasurement_returns140() {
        assertEquals(140, chooseAutoBudget(null, 1000))
        assertEquals(140, chooseAutoBudget(null, 0))
    }

    @Test
    fun chooseAutoBudget_fastPhone_selects280() {
        // 3 s/img * 1000 = 3000s <= 28800s -> 280
        assertEquals(280, chooseAutoBudget(3.0, 1000))
    }

    @Test
    fun chooseAutoBudget_snapdragonBaseline_selects280() {
        // 11 s * 2354 screenshots = 25894s (7.2 h) fits the 8 h overnight target at full quality -> 280
        assertEquals(280, chooseAutoBudget(11.0, 2354))
    }

    @Test
    fun chooseAutoBudget_mediatekSlowPhone_selects140() {
        // 20 s * 2354 screenshots = 47080s at 280 (> 8 h)
        // at 140 budget: 10 s * 2354 = 23540s (6.5 h <= 8 h) -> 140
        assertEquals(140, chooseAutoBudget(20.0, 2354))
    }

    @Test
    fun chooseAutoBudget_verySlowPhone_selects70() {
        // 30 s/img: 140 budget = 15 s * 2354 = 35310s > 28800s; 70 budget = 7.5 s * 2354 = 17655s -> 70
        assertEquals(70, chooseAutoBudget(30.0, 2354))
    }

    @Test
    fun chooseAutoBudget_tinyPending_alwaysSelects280() {
        // Even on slow phones, small batches complete quickly
        assertEquals(280, chooseAutoBudget(20.0, 1))
        assertEquals(280, chooseAutoBudget(20.0, 10))
        assertEquals(280, chooseAutoBudget(30.0, 50))
    }

    @Test
    fun chooseAutoBudget_boundaryExactlyAtTarget() {
        // Target is 21600.0s
        // With 100 items, 216.0 s/img * 100 = 21600.0s -> fits exactly at 280
        assertEquals(280, chooseAutoBudget(216.0, 100, targetSeconds = 21600.0))

        // Just 0.1s over target forces drop to 140
        assertEquals(140, chooseAutoBudget(216.01, 100, targetSeconds = 21600.0))
    }

    @Test
    fun updateMeasurement_firstObservation_takenAsIs() {
        val result = updateMeasurement(previous = null, observedSeconds = 12.0, budgetUsed = 280)
        assertEquals(12.0, result, 1e-6)
    }

    @Test
    fun updateMeasurement_emaBlend_usesAlpha03() {
        // previous = 10.0, observed = 20.0 at 280
        // 0.3 * 20.0 + 0.7 * 10.0 = 6.0 + 7.0 = 13.0
        val result = updateMeasurement(previous = 10.0, observedSeconds = 20.0, budgetUsed = 280)
        assertEquals(13.0, result, 1e-6)
    }

    @Test
    fun updateMeasurement_normalisationFrom70Budget() {
        // 5s at budget 70 normalizes to 5 * 280 / 70 = 20s at budget 280
        val firstObs = updateMeasurement(previous = null, observedSeconds = 5.0, budgetUsed = 70)
        assertEquals(20.0, firstObs, 1e-6)

        // EMA update from normalized 70-budget
        // previous = 10.0, normalized = 20.0 -> 13.0
        val blended = updateMeasurement(previous = 10.0, observedSeconds = 5.0, budgetUsed = 70)
        assertEquals(13.0, blended, 1e-6)
    }

    @Test
    fun updateMeasurement_badInputs_ignoresInvalidObservations() {
        // Negative / zero / non-finite observations return previous
        assertEquals(15.0, updateMeasurement(previous = 15.0, observedSeconds = -1.0, budgetUsed = 280), 1e-6)
        assertEquals(15.0, updateMeasurement(previous = 15.0, observedSeconds = 0.0, budgetUsed = 280), 1e-6)
        assertEquals(15.0, updateMeasurement(previous = 15.0, observedSeconds = Double.NaN, budgetUsed = 280), 1e-6)
        assertEquals(15.0, updateMeasurement(previous = 15.0, observedSeconds = Double.POSITIVE_INFINITY, budgetUsed = 280), 1e-6)
        assertEquals(15.0, updateMeasurement(previous = 15.0, observedSeconds = 5.0, budgetUsed = -1), 1e-6)
        assertEquals(15.0, updateMeasurement(previous = 15.0, observedSeconds = 5.0, budgetUsed = 0), 1e-6)

        // If previous is null and input is invalid, never returns NaN
        val fallbackResult = updateMeasurement(previous = null, observedSeconds = -1.0, budgetUsed = 280)
        assertFalse(fallbackResult.isNaN())
        assertEquals(11.0, fallbackResult, 1e-6)
    }

    @Test
    fun resolveBudget_fixedChoicesIgnoreMeasurements() {
        assertEquals(70, resolveBudget(IndexingQuality.FAST, secPerImageAt280 = 2.0, pendingCount = 10))
        assertEquals(140, resolveBudget(IndexingQuality.BALANCED, secPerImageAt280 = 2.0, pendingCount = 10))
        assertEquals(280, resolveBudget(IndexingQuality.BEST, secPerImageAt280 = 50.0, pendingCount = 5000))
    }

    @Test
    fun resolveBudget_autoQualityUsesCostModel() {
        assertEquals(140, resolveBudget(IndexingQuality.AUTO, secPerImageAt280 = 20.0, pendingCount = 2354))
        assertEquals(280, resolveBudget(IndexingQuality.AUTO, secPerImageAt280 = 11.0, pendingCount = 2354))
        assertEquals(280, resolveBudget(IndexingQuality.AUTO, secPerImageAt280 = 3.0, pendingCount = 1000))
        assertEquals(140, resolveBudget(IndexingQuality.AUTO, secPerImageAt280 = null, pendingCount = 500))
    }

    @Test
    fun estimateTotalSeconds_calculationAndRounding() {
        // 10 items at budget 280, 10.0s -> 100s
        assertEquals(100L, estimateTotalSeconds(280, 10.0, 10))

        // 10 items at budget 140 (5.0s/img) -> 50s
        assertEquals(50L, estimateTotalSeconds(140, 10.0, 10))

        // 10 items at budget 70 (2.5s/img) -> 25s
        assertEquals(25L, estimateTotalSeconds(70, 10.0, 10))

        // Rounding: 3 items * (10.0 * 70 / 280 = 2.5) = 7.5 -> rounded to 8
        assertEquals(8L, estimateTotalSeconds(70, 10.0, 3))

        // Null measurement uses fallbackSecAt280 (default 11.0s)
        assertEquals(110L, estimateTotalSeconds(280, null, 10))

        // Null measurement with custom fallback
        assertEquals(30L, estimateTotalSeconds(70, null, 10, fallbackSecAt280 = 12.0))

        // Zero or negative pending items
        assertEquals(0L, estimateTotalSeconds(280, 10.0, 0))
        assertEquals(0L, estimateTotalSeconds(280, 10.0, -5))
    }
}
