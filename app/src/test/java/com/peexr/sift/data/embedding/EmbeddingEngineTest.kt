package com.peexr.sift.data.embedding

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.math.sqrt

class EmbeddingEngineTest {

    @Test
    fun truncateAndNormalize_truncates768To128AndNormalizes() {
        val input = FloatArray(768) { (it + 1).toFloat() }
        val result = truncateAndNormalize(input, targetDim = 128)

        assertEquals(128, result.size)

        var sumSq = 0.0
        for (v in result) {
            sumSq += v * v
        }
        val norm = sqrt(sumSq)
        assertEquals(1.0, norm, 1e-5)

        // Verify elements are proportional to the first 128 elements of the input
        var expectedSumSq = 0.0
        for (i in 0 until 128) {
            val v = (i + 1).toDouble()
            expectedSumSq += v * v
        }
        val expectedInvNorm = 1.0 / sqrt(expectedSumSq)
        for (i in 0 until 128) {
            val expectedVal = ((i + 1) * expectedInvNorm).toFloat()
            assertEquals(expectedVal, result[i], 1e-5f)
        }
    }

    @Test
    fun truncateAndNormalize_allZerosVector_returnsZeroVectorWithoutNaN() {
        val input = FloatArray(768) { 0.0f }
        val result = truncateAndNormalize(input, targetDim = 128)

        assertEquals(128, result.size)
        for (v in result) {
            assertFalse(v.isNaN())
            assertEquals(0.0f, v, 0.0f)
        }
    }

    @Test
    fun truncateAndNormalize_alreadyTargetDim_normalizesCorrectly() {
        val input = FloatArray(128) { 1.0f }
        val result = truncateAndNormalize(input, targetDim = 128)

        assertEquals(128, result.size)
        val expectedVal = (1.0 / sqrt(128.0)).toFloat()
        for (v in result) {
            assertEquals(expectedVal, v, 1e-6f)
        }
    }

    @Test
    fun truncateAndNormalize_shorterVector_padsToTargetDim() {
        val input = floatArrayOf(3.0f, 4.0f)
        val result = truncateAndNormalize(input, targetDim = 4)

        assertEquals(4, result.size)
        // 3^2 + 4^2 = 25, sqrt = 5 -> [0.6, 0.8, 0.0, 0.0]
        assertEquals(0.6f, result[0], 1e-6f)
        assertEquals(0.8f, result[1], 1e-6f)
        assertEquals(0.0f, result[2], 0.0f)
        assertEquals(0.0f, result[3], 0.0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncateAndNormalize_zeroTargetDim_throwsException() {
        truncateAndNormalize(FloatArray(10), targetDim = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncateAndNormalize_negativeTargetDim_throwsException() {
        truncateAndNormalize(FloatArray(10), targetDim = -1)
    }

    @Test
    fun buildImageInputIds_zeroSoftTokens_returnsWrapperSequence() {
        val ids = buildImageInputIds(0)
        assertArrayEquals(longArrayOf(2L, 255999L, 258882L, 1L), ids)
    }

    @Test
    fun buildImageInputIds_singleSoftToken_containsOnePlaceholder() {
        val ids = buildImageInputIds(1)
        assertArrayEquals(longArrayOf(2L, 255999L, 258880L, 258882L, 1L), ids)
    }

    @Test
    fun buildImageInputIds_goldenSoftTokens264_matchesSpecification() {
        val ids = buildImageInputIds(264)
        // Size must be softTokens + 4 = 268
        assertEquals(268, ids.size)
        assertEquals(2L, ids[0])         // <bos>
        assertEquals(255999L, ids[1])    // <|image>

        for (i in 0 until 264) {
            assertEquals("Token at index ${2 + i} must be image placeholder 258880", 258880L, ids[2 + i])
        }

        assertEquals(258882L, ids[266])  // <image|>
        assertEquals(1L, ids[267])       // <eos>
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildImageInputIds_negativeSoftTokens_throwsException() {
        buildImageInputIds(-1)
    }

    @Test
    fun embedText_whenModelNotReady_throwsIllegalStateException() {
        val mockContext = mock(Context::class.java)
        val mockModelStore = ModelStore(mockContext)
        val engine = EmbeddingEngine(mockContext, mockModelStore)

        try {
            runBlocking {
                engine.embedText("test query")
            }
            fail("Expected IllegalStateException when model is NotInstalled")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Embedding model is not ready") == true)
        }
    }

    @Test
    fun embedText_whenEngineClosed_throwsIllegalStateException() {
        val mockContext = mock(Context::class.java)
        val mockModelStore = ModelStore(mockContext)
        val engine = EmbeddingEngine(mockContext, mockModelStore)
        engine.close()

        try {
            runBlocking {
                engine.embedText("test query")
            }
            fail("Expected IllegalStateException when engine is closed")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("closed") == true)
        }
    }
}
