package com.peexr.sift.data.collections

import java.util.Collections
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

const val MIN_EXAMPLES = 5

data class ClassifierModel(
    val weights: FloatArray,
    val bias: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClassifierModel
        if (!weights.contentEquals(other.weights)) return false
        if (bias != other.bias) return false
        return true
    }

    override fun hashCode(): Int {
        var result = weights.contentHashCode()
        result = 31 * result + bias.hashCode()
        return result
    }
}

fun ClassifierModel.probability(x: FloatArray): Float {
    if (bias == Float.NEGATIVE_INFINITY) return 0f
    if (bias == Float.POSITIVE_INFINITY) return 1f
    val w = weights
    if (w.isEmpty() || x.size != w.size) {
        return 0f
    }
    var z = bias
    for (i in w.indices) {
        z += w[i] * x[i]
    }
    if (z.isNaN()) return 0f
    val clamped = z.coerceIn(-30f, 30f)
    return (1.0 / (1.0 + exp(-clamped.toDouble()))).toFloat()
}

/**
 * Deterministically samples [count] IDs from [candidateIds] after excluding [exclude].
 * Candidates are sorted first so refreshes remain stable.
 */
fun sampleBackground(
    candidateIds: List<Long>,
    exclude: Set<Long>,
    count: Int,
    seed: Long
): List<Long> {
    if (count <= 0) return emptyList()
    val pool = candidateIds.asSequence()
        .filter { it !in exclude }
        .distinct()
        .sorted()
        .toMutableList()
    if (pool.size <= count) return pool
    val rng = Random(seed)
    Collections.shuffle(pool, rng)
    return pool.take(count)
}

/**
 * Solves dense linear system A * x = b using Gaussian elimination with partial pivoting.
 * Returns null if the matrix is singular or ill-conditioned.
 */
internal fun solveLinearSystem(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    val n = b.size
    val a = Array(n) { i -> A[i].clone() }
    val x = b.clone()

    for (k in 0 until n) {
        var maxRow = k
        var maxVal = abs(a[k][k])
        for (i in k + 1 until n) {
            val v = abs(a[i][k])
            if (v > maxVal) {
                maxVal = v
                maxRow = i
            }
        }

        if (maxVal < 1e-12 || maxVal.isNaN()) {
            return null
        }

        if (maxRow != k) {
            val tempA = a[k]
            a[k] = a[maxRow]
            a[maxRow] = tempA

            val tempX = x[k]
            x[k] = x[maxRow]
            x[maxRow] = tempX
        }

        val pivot = a[k][k]
        for (i in k + 1 until n) {
            val factor = a[i][k] / pivot
            a[i][k] = 0.0
            for (j in k + 1 until n) {
                a[i][j] -= factor * a[k][j]
            }
            x[i] -= factor * x[k]
        }
    }

    val sol = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var sum = x[i]
        for (j in i + 1 until n) {
            sum -= a[i][j] * sol[j]
        }
        val pivot = a[i][i]
        if (abs(pivot) < 1e-12 || pivot.isNaN()) {
            return null
        }
        sol[i] = sum / pivot
    }
    return sol
}

private fun softplus(z: Double): Double {
    return if (z > 0.0) {
        z + ln(1.0 + exp(-z))
    } else {
        ln(1.0 + exp(z))
    }
}

private fun computeLoss(
    w: DoubleArray,
    b: Double,
    positives: List<FloatArray>,
    negatives: List<FloatArray>,
    cPos: Double,
    cNeg: Double,
    lambda: Double
): Double {
    var loss = 0.0
    for (x in positives) {
        var z = b
        for (d in x.indices) {
            z += w[d] * x[d]
        }
        val zClamped = z.coerceIn(-30.0, 30.0)
        loss += cPos * softplus(-zClamped)
    }
    for (x in negatives) {
        var z = b
        for (d in x.indices) {
            z += w[d] * x[d]
        }
        val zClamped = z.coerceIn(-30.0, 30.0)
        loss += cNeg * softplus(zClamped)
    }
    var reg = 0.0
    for (d in w.indices) {
        reg += w[d] * w[d]
    }
    loss += 0.5 * lambda * reg
    return loss
}

/**
 * Trains an L2-regularized logistic regression model on embedding vectors.
 *
 * Model: P(y=1|x) = sigmoid(w^T x + b).
 * Loss = class-weighted binary cross-entropy + 0.5 * lambda * ||w||^2, with bias not regularized.
 * Training uses Newton-Raphson (IRLS) with backtracking line search, max 40 iterations.
 */
fun train(
    positives: List<FloatArray>,
    negatives: List<FloatArray>,
    lambda: Float = 1f
): ClassifierModel {
    val firstNonEmpty = positives.firstOrNull { it.isNotEmpty() }
        ?: negatives.firstOrNull { it.isNotEmpty() }
        ?: return ClassifierModel(FloatArray(0), Float.NEGATIVE_INFINITY)

    val targetDim = firstNonEmpty.size
    val validPositives = positives.filter { it.size == targetDim && it.all { v -> v.isFinite() } }
    val validNegatives = negatives.filter { it.size == targetDim && it.all { v -> v.isFinite() } }

    if (validPositives.isEmpty()) {
        return ClassifierModel(FloatArray(targetDim), Float.NEGATIVE_INFINITY)
    }
    if (validNegatives.isEmpty()) {
        return ClassifierModel(FloatArray(targetDim), Float.POSITIVE_INFINITY)
    }

    val nPos = validPositives.size
    val nNeg = validNegatives.size
    val nTotal = (nPos + nNeg).toDouble()
    val cPos = nTotal / (2.0 * nPos)
    val cNeg = nTotal / (2.0 * nNeg)

    val dim = targetDim
    val m = dim + 1
    val w = DoubleArray(dim)
    var b = 0.0
    val regLambda = lambda.toDouble()

    val maxIterations = 40
    for (iter in 0 until maxIterations) {
        val g = DoubleArray(m)
        val H = Array(m) { DoubleArray(m) }

        // Positives (y = 1)
        for (x in validPositives) {
            var z = b
            for (d in 0 until dim) {
                z += w[d] * x[d]
            }
            val zClamped = z.coerceIn(-30.0, 30.0)
            val p = 1.0 / (1.0 + exp(-zClamped))
            val err = (p - 1.0) * cPos
            val weight = (p * (1.0 - p)) * cPos

            for (d in 0 until dim) {
                g[d] += err * x[d]
            }
            g[dim] += err

            for (j in 0 until dim) {
                val xj = x[j].toDouble()
                val wXj = weight * xj
                for (k in 0..j) {
                    val hVal = wXj * x[k]
                    H[j][k] += hVal
                    if (j != k) {
                        H[k][j] += hVal
                    }
                }
                H[j][dim] += wXj
                H[dim][j] += wXj
            }
            H[dim][dim] += weight
        }

        // Negatives (y = 0)
        for (x in validNegatives) {
            var z = b
            for (d in 0 until dim) {
                z += w[d] * x[d]
            }
            val zClamped = z.coerceIn(-30.0, 30.0)
            val p = 1.0 / (1.0 + exp(-zClamped))
            val err = (p - 0.0) * cNeg
            val weight = (p * (1.0 - p)) * cNeg

            for (d in 0 until dim) {
                g[d] += err * x[d]
            }
            g[dim] += err

            for (j in 0 until dim) {
                val xj = x[j].toDouble()
                val wXj = weight * xj
                for (k in 0..j) {
                    val hVal = wXj * x[k]
                    H[j][k] += hVal
                    if (j != k) {
                        H[k][j] += hVal
                    }
                }
                H[j][dim] += wXj
                H[dim][j] += wXj
            }
            H[dim][dim] += weight
        }

        // Regularization on weights: bias not regularized
        for (d in 0 until dim) {
            g[d] += regLambda * w[d]
            H[d][d] += regLambda
        }
        // Add 1e-6 to the bias diagonal of the Hessian
        H[dim][dim] += 1e-6

        // Solve H deltaTheta = -g
        val negG = DoubleArray(m) { -g[it] }
        val solvedDelta = solveLinearSystem(H, negG)
        val deltaTheta = if (solvedDelta != null && solvedDelta.none { it.isNaN() || it.isInfinite() }) {
            solvedDelta
        } else {
            // If the solve fails, take a gradient step of 0.05·grad
            DoubleArray(m) { -0.05 * g[it] }
        }

        var maxDelta = 0.0
        for (i in 0 until m) {
            val dVal = abs(deltaTheta[i])
            if (dVal > maxDelta) maxDelta = dVal
        }
        if (maxDelta < 1e-6) {
            break
        }

        // Backtracking line search: halve step up to 5 times until loss doesn't increase
        val currentLoss = computeLoss(w, b, validPositives, validNegatives, cPos, cNeg, regLambda)
        var step = 1.0
        var accepted = false

        for (h in 0..5) {
            val candW = DoubleArray(dim) { w[it] + step * deltaTheta[it] }
            val candB = b + step * deltaTheta[dim]
            val candLoss = computeLoss(candW, candB, validPositives, validNegatives, cPos, cNeg, regLambda)
            if (candLoss <= currentLoss && !candLoss.isNaN()) {
                for (d in 0 until dim) {
                    w[d] = candW[d]
                }
                b = candB
                accepted = true
                break
            }
            step *= 0.5
        }

        if (!accepted) {
            break
        }
    }

    val finalWeights = FloatArray(dim) { w[it].toFloat().takeIf { f -> f.isFinite() } ?: 0f }
    val finalBias = b.toFloat().takeIf { it.isFinite() } ?: 0f
    return ClassifierModel(finalWeights, finalBias)
}

object CollectionClassifier {
    const val MIN_EXAMPLES = com.peexr.sift.data.collections.MIN_EXAMPLES

    fun train(
        positives: List<FloatArray>,
        negatives: List<FloatArray>,
        lambda: Float = 1f
    ): ClassifierModel = com.peexr.sift.data.collections.train(positives, negatives, lambda)

    fun sampleBackground(
        candidateIds: List<Long>,
        exclude: Set<Long>,
        count: Int,
        seed: Long
    ): List<Long> = com.peexr.sift.data.collections.sampleBackground(candidateIds, exclude, count, seed)
}
