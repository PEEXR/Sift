package com.peexr.sift.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VectorMathTest {

    @Test
    fun testByteRoundTrip() {
        val original = FloatArray(EMBEDDING_DIM) { i ->
            (i * 0.123f) - 7.5f
        }

        val bytes = original.toByteArray()
        assertEquals(EMBEDDING_DIM * 4, bytes.size)

        val recovered = bytes.toFloatArray()
        assertEquals(EMBEDDING_DIM, recovered.size)

        for (i in 0 until EMBEDDING_DIM) {
            assertEquals("Mismatch at index $i", original[i], recovered[i], 0.0f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testByteArrayInvalidSizeThrows() {
        ByteArray(500).toFloatArray()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFloatArrayInvalidSizeThrows() {
        FloatArray(100).toByteArray()
    }

    @Test
    fun testL2NormalizeKnownValues() {
        val input = floatArrayOf(3.0f, 4.0f, 0.0f, 0.0f)
        val normalized = input.l2Normalize()

        assertNotSame("Normalize should return a new array", input, normalized)
        assertEquals(3.0f, input[0], 0.0f) // Original untouched
        assertEquals(0.6f, normalized[0], 1e-6f)
        assertEquals(0.8f, normalized[1], 1e-6f)
        assertEquals(0.0f, normalized[2], 0.0f)
        assertEquals(0.0f, normalized[3], 0.0f)

        var normSq = 0f
        for (v in normalized) normSq += v * v
        assertEquals(1.0f, normSq, 1e-6f)
    }

    @Test
    fun testL2NormalizeZeroVector() {
        val zeroVector = FloatArray(EMBEDDING_DIM) { 0.0f }
        val normalized = zeroVector.l2Normalize()

        assertEquals(EMBEDDING_DIM, normalized.size)
        for (i in normalized.indices) {
            assertFalse("Normalized zero vector should not contain NaN", normalized[i].isNaN())
            assertEquals(0.0f, normalized[i], 0.0f)
        }
    }

    @Test
    fun testDotProductKnownValuesMultipleOfFour() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val b = floatArrayOf(2.0f, 3.0f, 4.0f, 5.0f)
        // 1*2 + 2*3 + 3*4 + 4*5 = 2 + 6 + 12 + 20 = 40
        val result = dot(a, b)
        assertEquals(40.0f, result, 1e-6f)
    }

    @Test
    fun testDotProductKnownValuesNotMultipleOfFour() {
        // Size 5: 1 unrolled block + 1 remainder
        val a5 = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f)
        val b5 = floatArrayOf(2.0f, 3.0f, 4.0f, 5.0f, 6.0f)
        // 40 + 5*6 = 70
        assertEquals(70.0f, dot(a5, b5), 1e-6f)

        // Size 1: 0 unrolled blocks + 1 remainder
        val a1 = floatArrayOf(3.5f)
        val b1 = floatArrayOf(2.0f)
        assertEquals(7.0f, dot(a1, b1), 1e-6f)

        // Size 7: 1 unrolled block + 3 remainder
        val a7 = floatArrayOf(1f, 1f, 1f, 1f, 2f, 3f, 4f)
        val b7 = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f)
        // 4 + 2 + 3 + 4 = 13
        assertEquals(13.0f, dot(a7, b7), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDotProductMismatchedSizesThrows() {
        dot(FloatArray(4), FloatArray(5))
    }

    @Test
    fun testCosineSimilarity() {
        val a = floatArrayOf(1.0f, 0.0f, 0.0f)
        val b = floatArrayOf(0.0f, 1.0f, 0.0f)
        // Orthogonal
        assertEquals(0.0f, cosineSimilarity(a, b), 1e-6f)

        // Identical
        val c = floatArrayOf(2.0f, 4.0f, 6.0f, 8.0f, 10.0f) // Not multiple of 4
        assertEquals(1.0f, cosineSimilarity(c, c), 1e-6f)

        // Opposite
        val negC = floatArrayOf(-2.0f, -4.0f, -6.0f, -8.0f, -10.0f)
        assertEquals(-1.0f, cosineSimilarity(c, negC), 1e-6f)

        // Zero norms
        val zeros = floatArrayOf(0f, 0f, 0f)
        assertEquals(0.0f, cosineSimilarity(a, zeros), 0.0f)
        assertEquals(0.0f, cosineSimilarity(zeros, zeros), 0.0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCosineSimilarityMismatchedSizesThrows() {
        cosineSimilarity(FloatArray(3), FloatArray(4))
    }

    @Test
    fun testTopKBySimilarityOrdering() {
        val dim = EMBEDDING_DIM
        fun makeUnitVector(axis: Int): FloatArray {
            val vec = FloatArray(dim)
            vec[axis] = 1.0f
            return vec
        }

        val candidates = listOf(
            101L to makeUnitVector(0),
            102L to makeUnitVector(1),
            103L to makeUnitVector(2),
            104L to makeUnitVector(3)
        )

        // Query closer to axis 0 than axis 1, orthogonal to 2 and 3
        val query = FloatArray(dim).apply {
            this[0] = 4.0f
            this[1] = 3.0f
        } // normalized will be [0.8f, 0.6f, 0f, ...]

        val top2 = topKBySimilarity(query, candidates, k = 2)
        assertEquals(2, top2.size)
        assertEquals(101L, top2[0].first)
        assertEquals(0.8f, top2[0].second, 1e-6f)
        assertEquals(102L, top2[1].first)
        assertEquals(0.6f, top2[1].second, 1e-6f)
    }

    @Test
    fun testTopKBySimilarityKGreaterThanN() {
        val candidates = listOf(
            1L to floatArrayOf(1.0f, 0.0f),
            2L to floatArrayOf(0.0f, 1.0f)
        )
        val query = floatArrayOf(1.0f, 0.0f)

        val result = topKBySimilarity(query, candidates, k = 10)
        assertEquals(2, result.size)
        assertEquals(1L, result[0].first)
        assertEquals(1.0f, result[0].second, 1e-6f)
        assertEquals(2L, result[1].first)
        assertEquals(0.0f, result[1].second, 1e-6f)
    }

    @Test
    fun testTopKBySimilarityEdgeCases() {
        val query = floatArrayOf(1.0f, 0.0f)
        val candidates = listOf(1L to floatArrayOf(1.0f, 0.0f))

        assertTrue(topKBySimilarity(query, candidates, k = 0).isEmpty())
        assertTrue(topKBySimilarity(query, candidates, k = -5).isEmpty())
        assertTrue(topKBySimilarity(query, emptyList(), k = 5).isEmpty())
    }
}
