package com.peexr.sift.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp

class CollectionClassifierTest {

    @Test
    fun train_separableGaussianBlobsIn128D_achievesHighAccuracyOnHeldOut() {
        val rng = Random(1337L)
        val dim = 128

        // Generate 10 positives centered at +0.3 in every dimension, std = 0.1
        val trainPos = List(10) {
            FloatArray(dim) { (0.3 + rng.nextGaussian() * 0.1).toFloat() }
        }

        // Generate 50 negatives centered at -0.3 in every dimension, std = 0.1
        val trainNeg = List(50) {
            FloatArray(dim) { (-0.3 + rng.nextGaussian() * 0.1).toFloat() }
        }

        // 200 held-out samples: 100 positive, 100 negative
        val heldOutPos = List(100) {
            FloatArray(dim) { (0.3 + rng.nextGaussian() * 0.1).toFloat() }
        }
        val heldOutNeg = List(100) {
            FloatArray(dim) { (-0.3 + rng.nextGaussian() * 0.1).toFloat() }
        }

        val model = CollectionClassifier.train(trainPos, trainNeg, lambda = 1.0f)

        assertFalse("Weights must not contain NaN", model.weights.any { it.isNaN() })
        assertFalse("Bias must not be NaN", model.bias.isNaN())

        var correctCount = 0
        for (p in heldOutPos) {
            val prob = model.probability(p)
            if (prob >= 0.5f) correctCount++
        }
        for (n in heldOutNeg) {
            val prob = model.probability(n)
            if (prob < 0.5f) correctCount++
        }

        val accuracy = correctCount / 200.0
        assertTrue("Accuracy on 200 held-out was $accuracy, expected > 0.95", accuracy > 0.95)
    }

    @Test
    fun train_matchesReferenceGradientDescentAndZeroGradientAtOptimum() {
        val positives = listOf(
            floatArrayOf(1.0f, 1.2f),
            floatArrayOf(1.5f, 0.9f),
            floatArrayOf(0.8f, 1.4f)
        )
        val negatives = listOf(
            floatArrayOf(-1.0f, -0.8f),
            floatArrayOf(-1.3f, -1.1f),
            floatArrayOf(-0.7f, -1.5f)
        )
        val lambda = 1.0f

        val model = CollectionClassifier.train(positives, negatives, lambda = lambda)

        // 1. Check that the gradient at the optimum is ~0
        val nPos = positives.size
        val nNeg = negatives.size
        val nTotal = (nPos + nNeg).toDouble()
        val cPos = nTotal / (2.0 * nPos)
        val cNeg = nTotal / (2.0 * nNeg)

        var g0 = 0.0
        var g1 = 0.0
        var gb = 0.0

        for (p in positives) {
            val prob = model.probability(p).toDouble()
            val err = (prob - 1.0) * cPos
            g0 += err * p[0]
            g1 += err * p[1]
            gb += err
        }
        for (n in negatives) {
            val prob = model.probability(n).toDouble()
            val err = (prob - 0.0) * cNeg
            g0 += err * n[0]
            g1 += err * n[1]
            gb += err
        }
        g0 += lambda.toDouble() * model.weights[0]
        g1 += lambda.toDouble() * model.weights[1]

        assertTrue("Gradient coordinate 0 should be near 0, was $g0", abs(g0) < 1e-4)
        assertTrue("Gradient coordinate 1 should be near 0, was $g1", abs(g1) < 1e-4)
        assertTrue("Bias gradient should be near 0, was $gb", abs(gb) < 1e-4)

        // 2. Reference simple gradient descent implementation
        var refW0 = 0.0
        var refW1 = 0.0
        var refB = 0.0
        val lr = 0.02

        for (step in 0 until 30000) {
            var grad0 = 0.0
            var grad1 = 0.0
            var gradB = 0.0

            for (p in positives) {
                val z = (refW0 * p[0] + refW1 * p[1] + refB).coerceIn(-30.0, 30.0)
                val prob = 1.0 / (1.0 + exp(-z))
                val err = (prob - 1.0) * cPos
                grad0 += err * p[0]
                grad1 += err * p[1]
                gradB += err
            }
            for (n in negatives) {
                val z = (refW0 * n[0] + refW1 * n[1] + refB).coerceIn(-30.0, 30.0)
                val prob = 1.0 / (1.0 + exp(-z))
                val err = (prob - 0.0) * cNeg
                grad0 += err * n[0]
                grad1 += err * n[1]
                gradB += err
            }
            grad0 += lambda.toDouble() * refW0
            grad1 += lambda.toDouble() * refW1

            refW0 -= lr * grad0
            refW1 -= lr * grad1
            refB -= lr * gradB
        }

        assertEquals(refW0.toFloat(), model.weights[0], 1e-3f)
        assertEquals(refW1.toFloat(), model.weights[1], 1e-3f)
        assertEquals(refB.toFloat(), model.bias, 1e-3f)
    }

    @Test
    fun degenerateCases_handlesNoPositivesNoNegativesIdenticalVectorsMismatchesGracefully() {
        // No positives
        val noPosModel = CollectionClassifier.train(
            positives = emptyList(),
            negatives = listOf(floatArrayOf(1f, 2f))
        )
        assertEquals(0f, noPosModel.probability(floatArrayOf(1f, 2f)), 1e-6f)
        assertEquals(0f, noPosModel.probability(floatArrayOf(-1f, -2f)), 1e-6f)

        // No negatives
        val noNegModel = CollectionClassifier.train(
            positives = listOf(floatArrayOf(1f, 2f)),
            negatives = emptyList()
        )
        assertEquals(1f, noNegModel.probability(floatArrayOf(1f, 2f)), 1e-6f)
        assertEquals(1f, noNegModel.probability(floatArrayOf(-1f, -2f)), 1e-6f)

        // Identical vectors must not produce NaN
        val identicalPos = List(10) { floatArrayOf(0.7f, 0.3f) }
        val identicalNeg = List(50) { floatArrayOf(0.7f, 0.3f) }
        val identicalModel = CollectionClassifier.train(identicalPos, identicalNeg)

        assertFalse("Weights must not be NaN", identicalModel.weights.any { it.isNaN() })
        assertFalse("Bias must not be NaN", identicalModel.bias.isNaN())
        val p = identicalModel.probability(floatArrayOf(0.7f, 0.3f))
        assertFalse("Probability must not be NaN", p.isNaN())
        assertTrue("Probability must be in [0, 1]", p in 0f..1f)

        // All-zero vectors must not produce NaN
        val zeroPos = List(10) { floatArrayOf(0f, 0f) }
        val zeroNeg = List(50) { floatArrayOf(0f, 0f) }
        val zeroModel = CollectionClassifier.train(zeroPos, zeroNeg)
        assertFalse(zeroModel.weights.any { it.isNaN() })
        assertFalse(zeroModel.bias.isNaN())

        // Mismatched dimensions: skips mismatched ones and trains on shared dimension
        val mixedPos = listOf(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(1f, 2f),
            floatArrayOf(4f, 5f, 6f)
        )
        val mixedNeg = listOf(
            floatArrayOf(-1f, -2f, -3f),
            floatArrayOf(-1f),
            floatArrayOf(-4f, -5f, -6f)
        )
        val mixedModel = CollectionClassifier.train(mixedPos, mixedNeg)
        assertEquals(3, mixedModel.weights.size)
        assertFalse(mixedModel.weights.any { it.isNaN() })
        assertFalse(mixedModel.bias.isNaN())

        // Evaluating with vector of wrong dimension returns 0f safely without crashing
        assertEquals(0f, mixedModel.probability(floatArrayOf(1f, 2f)), 1e-6f)
    }

    @Test
    fun sampleBackground_isDeterministicAndNeverReturnsExcludedIds() {
        val candidates = (1L..100L).toList()
        val exclude = setOf(5L, 10L, 15L, 20L, 25L, 30L, 35L, 40L)
        val seed = 42L

        val sample1 = CollectionClassifier.sampleBackground(candidates, exclude, count = 20, seed = seed)
        val sample2 = CollectionClassifier.sampleBackground(candidates, exclude, count = 20, seed = seed)

        assertEquals(20, sample1.size)
        // Must be exactly identical for the same seed
        assertEquals(sample1, sample2)

        // Must never contain any excluded ID
        assertTrue("Sampled IDs must not contain excluded items", sample1.none { it in exclude })

        // Must have unique items
        assertEquals(20, sample1.distinct().size)

        // Different seed produces different shuffle
        val sampleOtherSeed = CollectionClassifier.sampleBackground(candidates, exclude, count = 20, seed = 9999L)
        assertFalse(sample1 == sampleOtherSeed)

        // Edge case: count <= 0 returns emptyList
        assertTrue(CollectionClassifier.sampleBackground(candidates, exclude, count = 0, seed = seed).isEmpty())
        assertTrue(CollectionClassifier.sampleBackground(candidates, exclude, count = -5, seed = seed).isEmpty())

        // Edge case: empty candidates returns emptyList
        assertTrue(CollectionClassifier.sampleBackground(emptyList(), exclude, count = 10, seed = seed).isEmpty())

        // Edge case: fewer candidates than requested count returns all candidates sorted
        val smallCandidates = listOf(8L, 3L, 9L)
        val smallSample = CollectionClassifier.sampleBackground(smallCandidates, setOf(3L), count = 10, seed = seed)
        assertEquals(listOf(8L, 9L), smallSample)
    }
}
