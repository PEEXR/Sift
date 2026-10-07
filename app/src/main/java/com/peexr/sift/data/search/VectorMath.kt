package com.peexr.sift.data.search

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * Pure Kotlin vector math operations for 128-dimensional embedding representations.
 * Compatible with plain JVM unit tests without Android runtime dependencies.
 */

const val EMBEDDING_DIM = 128

/**
 * Decodes a little-endian byte array of size [EMBEDDING_DIM] * 4 into a 128-d [FloatArray].
 *
 * @throws IllegalArgumentException if the byte array size is not exactly [EMBEDDING_DIM] * 4 bytes.
 */
fun ByteArray.toFloatArray(): FloatArray {
    require(this.size == EMBEDDING_DIM * 4) {
        "ByteArray size must be ${EMBEDDING_DIM * 4} bytes, but was ${this.size}"
    }
    val floatArray = FloatArray(EMBEDDING_DIM)
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floatArray)
    return floatArray
}

/**
 * Encodes a 128-d [FloatArray] into a little-endian byte array of size [EMBEDDING_DIM] * 4.
 *
 * @throws IllegalArgumentException if the float array size is not exactly [EMBEDDING_DIM].
 */
fun FloatArray.toByteArray(): ByteArray {
    require(this.size == EMBEDDING_DIM) {
        "FloatArray size must be $EMBEDDING_DIM floats, but was ${this.size}"
    }
    val byteArray = ByteArray(EMBEDDING_DIM * 4)
    ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(this)
    return byteArray
}

/**
 * Computes the L2-normalized vector, returning a new [FloatArray].
 * Zero or NaN norm vectors return an all-zero vector rather than NaN values.
 */
fun FloatArray.l2Normalize(): FloatArray {
    var sumSq = 0.0
    for (i in indices) {
        val v = this[i].toDouble()
        sumSq += v * v
    }
    val norm = sqrt(sumSq)
    if (norm == 0.0 || norm.isNaN()) {
        return FloatArray(this.size)
    }
    val invNorm = (1.0 / norm).toFloat()
    val result = FloatArray(this.size)
    for (i in indices) {
        result[i] = this[i] * invNorm
    }
    return result
}

/**
 * Computes the dot product of two float arrays.
 * Uses a single tight loop unrolled by 4 with 4 independent accumulators to facilitate
 * JIT and ART auto-vectorization, handling any remaining elements afterwards.
 *
 * @throws IllegalArgumentException if the arrays have unequal sizes.
 */
fun dot(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) {
        "Arrays must have equal sizes: a.size=${a.size}, b.size=${b.size}"
    }
    val n = a.size
    var acc0 = 0f
    var acc1 = 0f
    var acc2 = 0f
    var acc3 = 0f

    val limit = n - (n % 4)
    var i = 0
    while (i < limit) {
        acc0 += a[i] * b[i]
        acc1 += a[i + 1] * b[i + 1]
        acc2 += a[i + 2] * b[i + 2]
        acc3 += a[i + 3] * b[i + 3]
        i += 4
    }

    var remainder = 0f
    while (i < n) {
        remainder += a[i] * b[i]
        i++
    }

    return (acc0 + acc1) + (acc2 + acc3) + remainder
}

/**
 * Computes cosine similarity between two float vectors.
 * If either vector has zero or NaN norm, returns 0f.
 *
 * @throws IllegalArgumentException if the arrays have unequal sizes.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) {
        "Arrays must have equal sizes: a.size=${a.size}, b.size=${b.size}"
    }
    var sumSqA = 0.0
    var sumSqB = 0.0
    val n = a.size
    for (i in 0 until n) {
        val va = a[i].toDouble()
        val vb = b[i].toDouble()
        sumSqA += va * va
        sumSqB += vb * vb
    }
    val normA = sqrt(sumSqA)
    val normB = sqrt(sumSqB)
    if (normA == 0.0 || normB == 0.0 || normA.isNaN() || normB.isNaN()) {
        return 0f
    }
    val dotProduct = dot(a, b).toDouble()
    val similarity = dotProduct / (normA * normB)
    return similarity.coerceIn(-1.0, 1.0).toFloat()
}

private class ScoredCandidate(
    var id: Long = 0L,
    var score: Float = 0f
) : Comparable<ScoredCandidate> {
    override fun compareTo(other: ScoredCandidate): Int {
        val cmp = this.score.compareTo(other.score)
        if (cmp != 0) return cmp
        return this.id.compareTo(other.id)
    }
}

/**
 * Retrieves the top-[k] candidate items by cosine similarity.
 * Assumes candidate vectors are pre-normalized. Computes dot product with the L2-normalized query,
 * keeping the top-[k] using a size-[k] min-heap ([PriorityQueue]), avoiding a full sort.
 * The per-candidate hot loop executes without memory allocations.
 *
 * @return List of (id, similarity) pairs sorted descending by similarity. Empty if [k] <= 0 or [candidates] is empty.
 */
fun topKBySimilarity(
    query: FloatArray,
    candidates: List<Pair<Long, FloatArray>>,
    k: Int
): List<Pair<Long, Float>> {
    if (k <= 0 || candidates.isEmpty()) {
        return emptyList()
    }

    val normalizedQuery = query.l2Normalize()
    val capacity = minOf(k, candidates.size)
    val pq = PriorityQueue<ScoredCandidate>(capacity)
    val pool = Array(capacity) { ScoredCandidate() }

    val totalCandidates = candidates.size
    for (i in 0 until totalCandidates) {
        val candidate = candidates[i]
        val id = candidate.first
        val vec = candidate.second
        val score = dot(normalizedQuery, vec)

        if (pq.size < capacity) {
            val entry = pool[pq.size]
            entry.id = id
            entry.score = score
            pq.offer(entry)
        } else {
            val minEntry = pq.peek()!!
            if (score > minEntry.score) {
                val reused = pq.poll()!!
                reused.id = id
                reused.score = score
                pq.offer(reused)
            }
        }
    }

    val result = ArrayList<Pair<Long, Float>>(pq.size)
    while (pq.isNotEmpty()) {
        val item = pq.poll()!!
        result.add(Pair(item.id, item.score))
    }
    result.sortWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first })
    return result
}
