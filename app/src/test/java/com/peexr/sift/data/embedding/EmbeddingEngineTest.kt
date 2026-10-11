package com.peexr.sift.data.embedding

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun embeddingEngine_defaultBudget_is280() {
        val mockContext = mock(Context::class.java)
        val mockModelStore = ModelStore(mockContext)
        val engine = EmbeddingEngine(mockContext, mockModelStore)
        assertEquals(280, engine.maxSoftTokens)
        assertEquals(ImagePatchifier.DEFAULT_MAX_SOFT_TOKENS, engine.maxSoftTokens)
    }

    @Test
    fun embeddingEngine_supportedBudgets_succeeds() {
        val mockContext = mock(Context::class.java)
        val mockModelStore = ModelStore(mockContext)
        for (budget in ImagePatchifier.SUPPORTED_BUDGETS) {
            val engine = EmbeddingEngine(mockContext, mockModelStore, maxSoftTokens = budget)
            assertEquals(budget, engine.maxSoftTokens)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun embeddingEngine_unsupportedBudget_throwsIllegalArgumentException() {
        val mockContext = mock(Context::class.java)
        val mockModelStore = ModelStore(mockContext)
        EmbeddingEngine(mockContext, mockModelStore, maxSoftTokens = 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun embedImage_unsupportedBudget_throwsIllegalArgumentException() {
        val mockContext = mock(Context::class.java)
        val mockModelStore = ModelStore(mockContext)
        val engine = EmbeddingEngine(mockContext, mockModelStore)
        val mockUri = mock(Uri::class.java)
        runBlocking {
            engine.embedImage(mockUri, maxSoftTokens = 100)
        }
    }

    @Test
    fun idleReleaseTimer_firesAfterTimeout() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mutex = Mutex()
        val releaseCount = AtomicInteger(0)
        val timer = IdleReleaseTimer(
            timeoutMs = 50L,
            scope = scope,
            mutex = mutex,
            onRelease = { releaseCount.incrementAndGet() }
        )

        try {
            timer.reset()
            assertTrue(timer.hasActiveJob())
            delay(120L)
            assertEquals(1, releaseCount.get())
        } finally {
            timer.cancel()
            scope.cancel()
        }
    }

    @Test
    fun idleReleaseTimer_resetByActivity_delaysRelease() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mutex = Mutex()
        val releaseCount = AtomicInteger(0)
        val timer = IdleReleaseTimer(
            timeoutMs = 80L,
            scope = scope,
            mutex = mutex,
            onRelease = { releaseCount.incrementAndGet() }
        )

        try {
            timer.reset()
            delay(40L)
            // Activity occurs before timeout expires
            timer.reset()
            delay(50L)
            // 90ms since first start, but only 50ms since reset: must not have fired yet
            assertEquals(0, releaseCount.get())

            // Wait for remaining timeout
            delay(60L)
            assertEquals(1, releaseCount.get())
        } finally {
            timer.cancel()
            scope.cancel()
        }
    }

    @Test
    fun idleReleaseTimer_neverFiresDuringActiveInference() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mutex = Mutex()
        val releaseCount = AtomicInteger(0)
        val timer = IdleReleaseTimer(
            timeoutMs = 50L,
            scope = scope,
            mutex = mutex,
            onRelease = { releaseCount.incrementAndGet() }
        )

        try {
            // Simulate an active inference holding the mutex
            mutex.lock()
            timer.reset()

            // Wait past the timeout
            delay(100L)
            // Mutex is held by the simulated inference, so onRelease must not have executed
            assertEquals(0, releaseCount.get())

            // Inference finishes, resets timer and unlocks mutex
            timer.reset()
            mutex.unlock()

            // Immediately after unlocking, onRelease should not execute because timer was reset
            assertEquals(0, releaseCount.get())

            // Wait for new timeout to expire
            delay(90L)
            assertEquals(1, releaseCount.get())
        } finally {
            if (mutex.isLocked) {
                mutex.unlock()
            }
            timer.cancel()
            scope.cancel()
        }
    }

    @Test
    fun idleReleaseTimer_cancel_preventsRelease() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mutex = Mutex()
        val releaseCount = AtomicInteger(0)
        val timer = IdleReleaseTimer(
            timeoutMs = 50L,
            scope = scope,
            mutex = mutex,
            onRelease = { releaseCount.incrementAndGet() }
        )

        try {
            timer.reset()
            delay(20L)
            timer.cancel()
            assertFalse(timer.hasActiveJob())
            delay(80L)
            assertEquals(0, releaseCount.get())
        } finally {
            timer.cancel()
            scope.cancel()
        }
    }

    @Test
    fun releaseVisionSession_whenNoSessionLoaded_succeedsWithoutException() = runBlocking {
        val mockContext = mock(Context::class.java)
        val mockModelStore = ModelStore(mockContext)
        val engine = EmbeddingEngine(mockContext, mockModelStore)
        try {
            // Should be a safe no-op when vision session was never loaded
            engine.releaseVisionSession()
            assertFalse(engine.isVisionSessionLoaded())
        } finally {
            engine.close()
        }
    }

    @Test
    fun embeddingEngine_close_cancelsResourcesAndStaysClosed() {
        val mockContext = mock(Context::class.java)
        val mockModelStore = ModelStore(mockContext)
        val engine = EmbeddingEngine(mockContext, mockModelStore)

        assertFalse(engine.isTextSessionLoaded())
        assertFalse(engine.isVisionSessionLoaded())

        engine.close()

        // Calling close repeatedly must be safe
        engine.close()

        try {
            runBlocking {
                engine.embedText("query")
            }
            fail("Expected IllegalStateException when calling embedText on closed engine")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("closed") == true)
        }
    }
}
