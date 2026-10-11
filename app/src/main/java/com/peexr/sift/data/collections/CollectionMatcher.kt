package com.peexr.sift.data.collections

import com.peexr.sift.data.search.dot
import kotlin.math.sqrt

enum class AutoFillSensitivity(
    val zThreshold: Float,
    val label: String,
    val classifierThreshold: Float = 0.5f
) {
    STRICT(2.0f, "Strict", 0.65f),
    BALANCED(1.5f, "Balanced", 0.50f),
    LOOSE(1.0f, "Loose", 0.35f)
}

/**
 * Pure matching logic for auto-filling collections via EmbeddingGemma 2 representations.
 *
 * Calibration note: measured on the user's real screenshots, z >= 1.5 includes true matches
 * plus a few near misses (the swipe review handles those); z >= 2.0 is high precision.
 */
object CollectionMatcher {

    const val DEFAULT_Z_THRESHOLD = 1.5f

    /**
     * Fixed background descriptions used for z-score normalization.
     *
     * Note: these only normalise each image's similarity scale; they are NOT collections
     * and are never shown to the user.
     */
    internal val BACKGROUND_DESCRIPTIONS: List<String> = listOf(
        "meme or joke image",
        "chat or messaging conversation",
        "social media post",
        "study notes or textbook page",
        "exam question paper",
        "document or form",
        "receipt or payment",
        "ticket or booking",
        "source code",
        "video game screen",
        "map",
        "shopping product page",
        "phone settings screen",
        "news article",
        "video app screen",
        "photo of a person",
        "music player",
        "calendar or schedule",
        "email inbox",
        "home screen with app icons"
    )

    private data class BackgroundStats(val mean: Double, val std: Double)

    private fun computeBackgroundStats(image: FloatArray, background: List<FloatArray>): BackgroundStats {
        if (background.isEmpty()) return BackgroundStats(0.0, 0.0)
        val n = background.size
        var sum = 0.0
        val dots = DoubleArray(n)
        for (i in 0 until n) {
            val d = dot(image, background[i]).toDouble()
            dots[i] = d
            sum += d
        }
        val mean = sum / n
        var varSum = 0.0
        for (i in 0 until n) {
            val diff = dots[i] - mean
            varSum += diff * diff
        }
        val std = sqrt(varSum / n)
        return BackgroundStats(mean, std)
    }

    /**
     * Computes the z-score of an image embedding with respect to a collection query embedding
     * normalized against a set of background embeddings:
     * (dot(image, collection) - mean over b of dot(image, b)) / (population std over b of dot(image, b)).
     *
     * Returns 0f if population std < 1e-6.
     */
    fun zScore(image: FloatArray, collection: FloatArray, background: List<FloatArray>): Float {
        val stats = computeBackgroundStats(image, background)
        if (stats.std < 1e-6 || stats.std.isNaN()) return 0f
        val sim = dot(image, collection).toDouble()
        return ((sim - stats.mean) / stats.std).toFloat()
    }

    /**
     * Matches candidate screenshots against a collection embedding using z-score thresholding.
     * Precomputes each image's background mean/std once per call.
     *
     * @param collection Embedding vector for the collection description/name.
     * @param screenshots List of (screenshotId, imageEmbedding) candidate pairs.
     * @param background Precomputed embedding vectors for the background descriptions.
     * @param threshold Minimum z-score to include a candidate (defaults to [DEFAULT_Z_THRESHOLD]).
     * @return List of (screenshotId, zScore) pairs for all matches with z >= threshold, sorted by z descending.
     */
    fun match(
        collection: FloatArray,
        screenshots: List<Pair<Long, FloatArray>>,
        background: List<FloatArray>,
        threshold: Float = DEFAULT_Z_THRESHOLD
    ): List<Pair<Long, Float>> {
        if (screenshots.isEmpty() || background.isEmpty()) {
            return emptyList()
        }

        val results = ArrayList<Pair<Long, Float>>()
        for ((id, image) in screenshots) {
            val stats = computeBackgroundStats(image, background)
            val z = if (stats.std < 1e-6 || stats.std.isNaN()) {
                0f
            } else {
                ((dot(image, collection).toDouble() - stats.mean) / stats.std).toFloat()
            }
            if (z >= threshold) {
                results.add(Pair(id, z))
            }
        }
        results.sortWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first })
        return results
    }

    /**
     * Refines a collection's query vector using Rocchio-style relevance feedback from user swipes.
     *
     * Kept (positive) screenshots pull the collection's query vector toward what the user considers a match;
     * removed (negative) screenshots push it away.
     *
     * @param base Base query embedding vector for the collection (e.g. from collection text).
     * @param positives List of image embeddings from kept screenshots.
     * @param negatives List of image embeddings from removed screenshots.
     * @param alpha Weight scaling the positive feedback vector (default 0.7).
     * @param beta Weight scaling the negative feedback vector (default 0.4).
     * @param maxFeedback Number of feedback items needed to apply full feedback weight (default 10).
     * @return L2-normalized refined query vector, or [base] if input is degenerate.
     */
    fun refineQuery(
        base: FloatArray,
        positives: List<FloatArray>,
        negatives: List<FloatArray>,
        alpha: Float = 0.7f,
        beta: Float = 0.4f,
        maxFeedback: Int = 10
    ): FloatArray = com.peexr.sift.data.collections.refineQuery(
        base = base,
        positives = positives,
        negatives = negatives,
        alpha = alpha,
        beta = beta,
        maxFeedback = maxFeedback
    )
}

/**
 * Pure Rocchio-style relevance feedback calculation for query vector refinement.
 *
 * @param base Base query embedding vector for the collection.
 * @param positives List of image embeddings from kept screenshots.
 * @param negatives List of image embeddings from removed screenshots.
 * @param alpha Weight scaling positive feedback (default 0.7).
 * @param beta Weight scaling negative feedback (default 0.4).
 * @param maxFeedback Number of feedback items needed to apply full feedback weight (default 10).
 * @return L2-normalized refined query vector, or [base] if input is degenerate.
 */
fun refineQuery(
    base: FloatArray,
    positives: List<FloatArray>,
    negatives: List<FloatArray>,
    alpha: Float = 0.7f,
    beta: Float = 0.4f,
    maxFeedback: Int = 10
): FloatArray {
    if (positives.isEmpty() && negatives.isEmpty()) return base
    if (base.isEmpty()) return base

    val validPositives = positives.filter { it.size == base.size }
    val validNegatives = negatives.filter { it.size == base.size }
    if (validPositives.isEmpty() && validNegatives.isEmpty()) return base
    if (maxFeedback <= 0) return base

    val wP = minOf(validPositives.size, maxFeedback) / maxFeedback.toFloat()
    val wN = minOf(validNegatives.size, maxFeedback) / maxFeedback.toFloat()

    val meanP: FloatArray? = if (validPositives.isNotEmpty()) {
        val mean = FloatArray(base.size)
        for (i in base.indices) {
            var sum = 0.0
            for (p in validPositives) {
                sum += p[i]
            }
            mean[i] = (sum / validPositives.size).toFloat()
        }
        mean
    } else null

    val meanN: FloatArray? = if (validNegatives.isNotEmpty()) {
        val mean = FloatArray(base.size)
        for (i in base.indices) {
            var sum = 0.0
            for (n in validNegatives) {
                sum += n[i]
            }
            mean[i] = (sum / validNegatives.size).toFloat()
        }
        mean
    } else null

    val q = FloatArray(base.size)
    for (i in base.indices) {
        var v = base[i]
        if (meanP != null) {
            v += alpha * wP * meanP[i]
        }
        if (meanN != null) {
            v -= beta * wN * meanN[i]
        }
        if (!v.isFinite()) return base
        q[i] = v
    }

    var sumSq = 0.0
    for (i in q.indices) {
        val d = q[i].toDouble()
        sumSq += d * d
    }
    val norm = sqrt(sumSq)
    if (norm < 1e-6 || !norm.isFinite()) return base

    val invNorm = (1.0 / norm).toFloat()
    val result = FloatArray(base.size)
    for (i in q.indices) {
        val r = q[i] * invNorm
        if (!r.isFinite()) return base
        result[i] = r
    }
    return result
}
