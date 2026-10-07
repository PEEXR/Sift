package com.peexr.sift.data.embedding

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.peexr.sift.data.search.cosineSimilarity
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * On-device instrumentation benchmarks and integration tests for EmbeddingGemma 2 (ONNX).
 *
 * Evaluates:
 * 1. Image embedding accuracy against Python reference vectors ([imageEmbeddings_matchPythonReference]).
 * 2. Cross-modal retrieval and ranking sanity across standard text queries ([crossModalRanking_isSane]).
 * 3. Memory and latency footprint on the device ART runtime ([memoryAndLatencyProfile]).
 * 4. End-to-end model downloading in an isolated temporary directory ([downloadManager_endToEnd], opt-in).
 *
 * All benchmark tests gracefully [Assume]-skip if the model files or reference test assets are absent.
 */
@RunWith(AndroidJUnit4::class)
class DeviceEmbeddingBenchTest {

    companion object {
        private const val TAG = "BENCH"
        private const val MIN_COSINE_THRESHOLD = 0.97f
        private const val MAX_JAVA_HEAP_RATIO = 0.90

        private var sharedEngine: EmbeddingEngine? = null
        private var cachedImageEmbeddings: Map<String, FloatArray>? = null

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            sharedEngine?.close()
            sharedEngine = null
            cachedImageEmbeddings = null
        }
    }

    private data class ReferenceItem(
        val fileName: String,
        val expectedEmb: FloatArray,
        val width: Int?,
        val height: Int?
    )

    private fun getOrCreateEngine(context: Context, modelStore: ModelStore): EmbeddingEngine {
        return sharedEngine ?: synchronized(this) {
            sharedEngine ?: EmbeddingEngine(context, modelStore).also { sharedEngine = it }
        }
    }

    private fun loadReferenceItems(testDataDir: File): List<ReferenceItem> {
        val refFile = File(testDataDir, "reference.json")
        if (!refFile.exists() || refFile.length() == 0L) {
            return emptyList()
        }
        val jsonString = refFile.readText()
        val rootObj = JSONObject(jsonString)
        val items = mutableListOf<ReferenceItem>()
        val keys = rootObj.keys()
        while (keys.hasNext()) {
            val fileName = keys.next()
            val entry = rootObj.optJSONObject(fileName) ?: continue
            val embJsonArray = entry.optJSONArray("emb128") ?: continue
            val emb = FloatArray(embJsonArray.length())
            for (i in 0 until embJsonArray.length()) {
                emb[i] = embJsonArray.getDouble(i).toFloat()
            }
            var w: Int? = null
            var h: Int? = null
            if (entry.has("size")) {
                val sizeArr = entry.optJSONArray("size")
                if (sizeArr != null && sizeArr.length() >= 2) {
                    w = sizeArr.getInt(0)
                    h = sizeArr.getInt(1)
                }
            }
            items.add(ReferenceItem(fileName, emb, w, h))
        }
        items.sortBy { it.fileName }
        return items
    }

    private fun getOrComputeImageEmbeddings(
        context: Context,
        engine: EmbeddingEngine
    ): Map<String, FloatArray> {
        cachedImageEmbeddings?.let { return it }

        val testDataDir = File(context.filesDir, "testdata")
        val items = loadReferenceItems(testDataDir)
        val map = mutableMapOf<String, FloatArray>()
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        runBlocking {
            for (item in items) {
                val imgFile = File(testDataDir, item.fileName)
                if (!imgFile.exists()) continue
                val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath, options) ?: continue
                try {
                    val emb = engine.embedImage(bitmap)
                    if (emb != null) {
                        map[item.fileName] = emb
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        cachedImageEmbeddings = map
        return map
    }

    private fun assertValidEmbedding(label: String, emb: FloatArray) {
        assertEquals("Vector '$label' must have dimension 128", 128, emb.size)
        for (i in emb.indices) {
            val v = emb[i]
            assertTrue("Vector '$label' contains non-finite float at index $i: $v", v.isFinite())
        }
        var sumSq = 0.0
        for (v in emb) {
            sumSq += v.toDouble() * v.toDouble()
        }
        val norm = sqrt(sumSq)
        assertTrue(
            "Vector '$label' norm was $norm, expected within 1e-3 of 1.0",
            abs(norm - 1.0) <= 1e-3
        )
    }

    /**
     * Test 1 — Verifies image embeddings against Python reference vectors.
     * Decodes test screenshots, executes warmup inference, calculates cosine similarities,
     * logs per-image wall-clock latency, and asserts cosine >= 0.97.
     */
    @Test
    fun imageEmbeddings_matchPythonReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelStore = ModelStore(context)
        Assume.assumeTrue(
            "Model files not installed on device; skipping imageEmbeddings_matchPythonReference.",
            modelStore.isInstalled()
        )

        val testDataDir = File(context.filesDir, "testdata")
        val refFile = File(testDataDir, "reference.json")
        Assume.assumeTrue(
            "Test data or reference.json not found in ${testDataDir.absolutePath}; skipping benchmark.",
            testDataDir.exists() && refFile.exists() && refFile.length() > 0
        )

        val refItems = loadReferenceItems(testDataDir)
        Assume.assumeTrue(
            "No reference items parsed from reference.json; skipping benchmark.",
            refItems.isNotEmpty()
        )
        Assume.assumeTrue(
            "Reference image files missing in ${testDataDir.absolutePath}; skipping benchmark.",
            refItems.all { File(testDataDir, it.fileName).exists() }
        )

        val scenario = ActivityScenario.launch(com.peexr.sift.MainActivity::class.java)
        try {
            val engine = getOrCreateEngine(context, modelStore)
            val bmpOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            // Throwaway warm-up call (logged separately, excluded from stats)
            val warmupItem = refItems.first()
            val warmupFile = File(testDataDir, warmupItem.fileName)
            val warmupBitmap = BitmapFactory.decodeFile(warmupFile.absolutePath, bmpOptions)
            assertNotNull("Failed to decode warmup bitmap: ${warmupFile.name}", warmupBitmap)
            val warmupStartNs = System.nanoTime()
            val warmupEmb = runBlocking { engine.embedImage(warmupBitmap) }
            val warmupMs = (System.nanoTime() - warmupStartNs) / 1_000_000.0
            warmupBitmap.recycle()
            assertNotNull("Warmup embedding was null", warmupEmb)
            Log.i(TAG, "Warmup image embedding: file=%s, warmup_ms=%.2f".format(warmupItem.fileName, warmupMs))

            val cosines = mutableListOf<Float>()
            val latenciesMs = mutableListOf<Double>()
            val computedEmbeddings = mutableMapOf<String, FloatArray>()

            for (item in refItems) {
                val imgFile = File(testDataDir, item.fileName)
                val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath, bmpOptions)
                assertNotNull("Failed to decode bitmap for ${item.fileName}", bitmap)
                val w = bitmap.width
                val h = bitmap.height

                val startNs = System.nanoTime()
                val actualEmb = runBlocking { engine.embedImage(bitmap) }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
                bitmap.recycle()

                assertNotNull("embedImage returned null for ${item.fileName}", actualEmb)
                val cosine = cosineSimilarity(actualEmb!!, item.expectedEmb)

                cosines.add(cosine)
                latenciesMs.add(elapsedMs)
                computedEmbeddings[item.fileName] = actualEmb

                Log.i(
                    TAG,
                    "Image: file=%s, size=%dx%d, cosine=%.4f, ms=%.2f".format(
                        item.fileName, w, h, cosine, elapsedMs
                    )
                )

                assertTrue(
                    "Cosine similarity for ${item.fileName} was $cosine (expected >= $MIN_COSINE_THRESHOLD)",
                    cosine >= MIN_COSINE_THRESHOLD
                )
            }

            cachedImageEmbeddings = computedEmbeddings

            val minCosine = cosines.minOrNull() ?: 0f
            val meanCosine = cosines.average().toFloat()
            val maxMs = latenciesMs.maxOrNull() ?: 0.0
            val meanMs = latenciesMs.average()

            Log.i(
                TAG,
                "Image Benchmark Summary: count=%d, min_cosine=%.4f, mean_cosine=%.4f, max_ms=%.2f, mean_ms=%.2f".format(
                    cosines.size, minCosine, meanCosine, maxMs, meanMs
                )
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * Test 2 — Verifies cross-modal text-image ranking sanity.
     * Embeds representative user query strings, scores them against reference screenshot embeddings,
     * logs top-3 image matches with cosine similarities for manual inspection, and asserts all vectors
     * are 128 finite floats with L2 norm within 1e-3 of 1.
     */
    @Test
    fun crossModalRanking_isSane() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelStore = ModelStore(context)
        Assume.assumeTrue(
            "Model files not installed on device; skipping crossModalRanking_isSane.",
            modelStore.isInstalled()
        )

        val testDataDir = File(context.filesDir, "testdata")
        val refFile = File(testDataDir, "reference.json")
        Assume.assumeTrue(
            "Test data or reference.json not found in ${testDataDir.absolutePath}; skipping benchmark.",
            testDataDir.exists() && refFile.exists() && refFile.length() > 0
        )

        val scenario = ActivityScenario.launch(com.peexr.sift.MainActivity::class.java)
        try {
            val engine = getOrCreateEngine(context, modelStore)
            val imageEmbeddings = getOrComputeImageEmbeddings(context, engine)
            Assume.assumeTrue(
                "No reference image embeddings available; skipping crossModalRanking_isSane.",
                imageEmbeddings.isNotEmpty()
            )

            // Validate all image vectors
            for ((fileName, imgEmb) in imageEmbeddings) {
                assertValidEmbedding("image: $fileName", imgEmb)
            }

            val queries = listOf(
                "receipt or invoice",
                "chat conversation",
                "video game",
                "map or navigation",
                "meme or funny image",
                "source code",
                "phone settings screen",
                "payment or bank transaction"
            )

            runBlocking {
                for (query in queries) {
                    val queryEmb = engine.embedText(query)
                    assertValidEmbedding("query: '$query'", queryEmb)

                    val rankedImages = imageEmbeddings.map { (fileName, imgEmb) ->
                        fileName to cosineSimilarity(queryEmb, imgEmb)
                    }.sortedByDescending { it.second }

                    val top3 = rankedImages.take(3)
                    val top3Formatted = top3.joinToString(", ") { "${it.first} (%.4f)".format(it.second) }
                    Log.i(TAG, "CrossModal ranking: query=\"$query\" -> top3=[$top3Formatted]")
                }
            }
        } finally {
            scenario.close()
        }
    }

    /**
     * Test 3 — Logs ART Java heap and native heap memory profile after embeddings.
     * Asserts used Java heap is strictly less than 90% of max heap.
     */
    @Test
    fun memoryAndLatencyProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelStore = ModelStore(context)
        Assume.assumeTrue(
            "Model files not installed on device; skipping memoryAndLatencyProfile.",
            modelStore.isInstalled()
        )

        val testDataDir = File(context.filesDir, "testdata")
        val refFile = File(testDataDir, "reference.json")
        Assume.assumeTrue(
            "Test data or reference.json not found in ${testDataDir.absolutePath}; skipping benchmark.",
            testDataDir.exists() && refFile.exists() && refFile.length() > 0
        )

        val scenario = ActivityScenario.launch(com.peexr.sift.MainActivity::class.java)
        try {
            val engine = getOrCreateEngine(context, modelStore)
            val imageEmbeddings = getOrComputeImageEmbeddings(context, engine)
            Assume.assumeTrue(
                "No reference image embeddings available; skipping memoryAndLatencyProfile.",
                imageEmbeddings.isNotEmpty()
            )

            val runtime = Runtime.getRuntime()
            val totalHeap = runtime.totalMemory()
            val freeHeap = runtime.freeMemory()
            val usedHeap = totalHeap - freeHeap
            val maxHeap = runtime.maxMemory()

            val usedHeapMb = usedHeap.toDouble() / (1024.0 * 1024.0)
            val maxHeapMb = maxHeap.toDouble() / (1024.0 * 1024.0)
            val nativeAllocatedMb = android.os.Debug.getNativeHeapAllocatedSize().toDouble() / (1024.0 * 1024.0)
            val usedHeapRatio = usedHeap.toDouble() / maxHeap.toDouble()

            Log.i(
                TAG,
                "Memory Profile: usedJavaHeap=%.2f MB, maxJavaHeap=%.2f MB (%.1f%%), nativeAllocated=%.2f MB".format(
                    usedHeapMb, maxHeapMb, usedHeapRatio * 100.0, nativeAllocatedMb
                )
            )

            assertTrue(
                "Used Java heap (%.2f MB) must be < %.0f%% of max heap (%.2f MB): %.1f%%".format(
                    usedHeapMb, MAX_JAVA_HEAP_RATIO * 100.0, maxHeapMb, usedHeapRatio * 100.0
                ),
                usedHeapRatio < MAX_JAVA_HEAP_RATIO
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * Test 4 — Opt-in end-to-end model downloader verification.
     * Only runs when instrumentation argument 'runDownload' equals 'true'.
     * Executes download into an isolated temporary subdirectory to avoid touching the production model,
     * monitors state transitions, verifies final SHA-256 integrity, and cleans up the temp dir.
     */
    @Test
    fun downloadManager_endToEnd() {
        val runDownloadArg = InstrumentationRegistry.getArguments().getString("runDownload")
        Assume.assumeTrue(
            "downloadManager_endToEnd is opt-in; pass '-e runDownload true' to execute.",
            "true".equals(runDownloadArg, ignoreCase = true)
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tempDir = File(context.cacheDir, "temp_model_store_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // Point ModelStore to the isolated temp directory via ContextWrapper
            val tempContext = object : ContextWrapper(context) {
                override fun getFilesDir(): File = tempDir
            }
            val tempModelStore = ModelStore(tempContext)

            val targetDir = tempModelStore.getModelDir()
            assertTrue(
                "tempModelStore root must reside within tempDir",
                targetDir.canonicalPath.startsWith(tempDir.canonicalPath)
            )

            val observedStates = Collections.synchronizedList(mutableListOf<ModelState>())
            val client = OkHttpClient.Builder().build()
            val downloader = ModelDownloader(tempModelStore, client)

            val startNs = System.nanoTime()
            runBlocking {
                val collectJob = launch {
                    tempModelStore.state.collect { state ->
                        observedStates.add(state)
                        Log.i(TAG, "Download state transition: $state")
                    }
                }
                try {
                    downloader.downloadModels(wifiOnly = false)
                } finally {
                    collectJob.cancel()
                }
            }
            val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0

            Log.i(
                TAG,
                "downloadModels completed in %.2f s. Observed transitions count: %d".format(
                    elapsedSec, observedStates.size
                )
            )

            assertTrue("tempModelStore must report isInstalled() == true", tempModelStore.isInstalled())
            assertEquals(ModelState.Ready, tempModelStore.state.value)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
