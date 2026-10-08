package com.peexr.sift.data.embedding

import kotlin.math.roundToLong

/**
 * Image token budget / indexing quality preset.
 *
 * Compute scales with soft tokens (maxPatches = maxSoftTokens * 9).
 */
enum class IndexingQuality(
    val budget: Int?,
    val label: String,
    val hint: String
) {
    AUTO(null, "Auto", "Picks a speed that finishes overnight"),
    FAST(70, "Fast", "Quickest, slightly less accurate on small text"),
    BALANCED(140, "Balanced", "Good accuracy, about half the time of Best"),
    BEST(280, "Best", "Most accurate, slowest")
}

/**
 * Cost model: seconds per image scales linearly with budget.
 */
fun secondsPerImageAt(budget: Int, secPerImageAt280: Double): Double {
    return secPerImageAt280 * budget / 280.0
}

/**
 * Selects an image token budget for AUTO quality based on measured device speed.
 *
 * @param secPerImageAt280 Measured inference time per image at budget 280 (or null if unmeasured).
 * @param pendingCount Number of screenshots pending embedding.
 * @param targetSeconds Target window to finish indexing within (default: 8 hours = 28,800s).
 * @return 140 if unmeasured; otherwise the largest budget in (280, 140, 70) fitting in targetSeconds; fallback 70.
 */
fun chooseAutoBudget(
    secPerImageAt280: Double?,
    pendingCount: Int,
    targetSeconds: Double = 8 * 3600.0
): Int {
    if (secPerImageAt280 == null) return 140
    val s = secPerImageAt280
    if (!s.isFinite() || s <= 0.0) return 140

    val candidateBudgets = listOf(280, 140, 70)
    for (budget in candidateBudgets) {
        val totalSec = secondsPerImageAt(budget, s) * pendingCount
        if (totalSec <= targetSeconds) {
            return budget
        }
    }
    return 70
}

/**
 * Resolves the token budget to use for a given quality preset.
 */
fun resolveBudget(
    quality: IndexingQuality,
    secPerImageAt280: Double?,
    pendingCount: Int
): Int {
    return quality.budget ?: chooseAutoBudget(secPerImageAt280, pendingCount)
}

/**
 * Updates the measured speed at budget 280 with a new observation using exponential moving average (alpha = 0.3).
 *
 * Normalizes the observed seconds to the 280 budget.
 * Non-finite or non-positive observations are ignored.
 */
fun updateMeasurement(
    previous: Double?,
    observedSeconds: Double,
    budgetUsed: Int
): Double {
    if (!observedSeconds.isFinite() || observedSeconds <= 0.0 || budgetUsed <= 0) {
        return previous ?: 11.0
    }
    val normalized = observedSeconds * 280.0 / budgetUsed
    if (previous == null || !previous.isFinite() || previous <= 0.0) {
        return normalized
    }
    val alpha = 0.3
    return alpha * normalized + (1.0 - alpha) * previous
}

/**
 * Estimates total indexing time in seconds for a budget and pending count.
 * Uses [fallbackSecAt280] (default 11.0s) if no measurement is available.
 */
fun estimateTotalSeconds(
    budget: Int,
    secPerImageAt280: Double?,
    pendingCount: Int,
    fallbackSecAt280: Double = 11.0
): Long {
    if (pendingCount <= 0) return 0L
    val s = if (secPerImageAt280 != null && secPerImageAt280.isFinite() && secPerImageAt280 > 0.0) {
        secPerImageAt280
    } else {
        fallbackSecAt280
    }
    val secPerImage = secondsPerImageAt(budget, s)
    return (secPerImage * pendingCount).roundToLong().coerceAtLeast(0L)
}
