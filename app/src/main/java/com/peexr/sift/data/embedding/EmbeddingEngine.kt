package com.peexr.sift.data.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.peexr.sift.data.search.l2Normalize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Truncates an embedding vector to [targetDim] (default 128) and L2-normalizes it.
 * Zero or NaN norm vectors produce an all-zero vector without crashing.
 *
 * @param embedding Full embedding vector (e.g. 768-d from Gemma fusion graph).
 * @param targetDim Truncation target dimension (must be positive, default 128).
 * @return 128-d L2-normalized float array.
 */
fun truncateAndNormalize(embedding: FloatArray, targetDim: Int = 128): FloatArray {
    require(targetDim > 0) { "targetDim must be positive, got $targetDim" }
    // copyOf truncates when longer and zero-pads when shorter
    return embedding.copyOf(targetDim).l2Normalize()
}

/**
 * Builds the fusion model input ID sequence for an image with [softTokens] visual tokens.
 * Pattern: [2 (<bos>), 255999 (<|image>), 258880 × T (<|image|>), 258882 (<image|>), 1 (<eos>)]
 *
 * The placeholder token 258880 MUST be expanded to exactly T copies (where T = P / 9).
 *
 * @param softTokens Number of soft visual tokens (T = P / 9).
 * @return LongArray of token IDs of length softTokens + 4.
 */
fun buildImageInputIds(softTokens: Int): LongArray {
    require(softTokens >= 0) { "softTokens must be non-negative, got $softTokens" }
    val ids = LongArray(softTokens + 4)
    ids[0] = 2L         // <bos>
    ids[1] = 255999L    // <|image>
    for (i in 0 until softTokens) {
        ids[2 + i] = 258880L // <|image|>
    }
    ids[2 + softTokens] = 258882L // <image|>
    ids[3 + softTokens] = 1L      // <eos>
    return ids
}

/**
 * Embedding engine powering EmbeddingGemma 2 (ONNX) local multimodal inference.
 *
 * Runs CPU-bound graph inference on [Dispatchers.Default] and model/file loading on [Dispatchers.IO].
 * All sessions are lazily initialized once and protected against concurrent execution via [Mutex].
 */
class EmbeddingEngine(
    private val context: Context,
    val modelStore: ModelStore,
    private val tokenizerProvider: suspend () -> GemmaTokenizer = {
        val tokenizerFile = File(modelStore.getModelDir(), "tokenizer.json")
        GemmaTokenizer.getInstance(tokenizerFile)
    },
    val maxSoftTokens: Int = ImagePatchifier.DEFAULT_MAX_SOFT_TOKENS,
    private val useAcceleratedProviders: Boolean = false
) : AutoCloseable {

    init {
        require(maxSoftTokens in ImagePatchifier.SUPPORTED_BUDGETS) {
            "Unsupported token budget: $maxSoftTokens. Supported: ${ImagePatchifier.SUPPORTED_BUDGETS}"
        }
    }

    companion object {
        const val EMBEDDING_DIM = 128
        const val TEXT_MODEL_FILE = "onnx/model_q4.onnx"
        const val VISION_MODEL_FILE = "onnx/vision_encoder_q4.onnx"
        const val TOKENIZER_FILE = "tokenizer.json"

        // Tokens
        const val BOS_ID = 2L
        const val EOS_ID = 1L
        const val IMAGE_START_ID = 255999L
        const val IMAGE_PLACEHOLDER_ID = 258880L
        const val IMAGE_END_ID = 258882L
    }

    private val inferenceMutex = Mutex()
    private val tokenizerMutex = Mutex()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var textSession: OrtSession? = null

    @Volatile
    private var visionSession: OrtSession? = null

    @Volatile
    private var tokenizer: GemmaTokenizer? = null

    @Volatile
    private var isClosed: Boolean = false

    /**
     * Verifies that the model files are present and ready for inference.
     *
     * @throws IllegalStateException if the model is not ready.
     */
    private fun ensureModelReady() {
        check(!isClosed) { "EmbeddingEngine is closed." }
        val state = modelStore.state.value
        if (state !is ModelState.Ready && !modelStore.isInstalled()) {
            throw IllegalStateException("Embedding model is not ready. Current state: $state")
        }
    }

    /**
     * Lazily loads and returns the GemmaTokenizer on [Dispatchers.IO].
     */
    private suspend fun getTokenizer(): GemmaTokenizer {
        tokenizer?.let { return it }
        return tokenizerMutex.withLock {
            tokenizer ?: withContext(Dispatchers.IO) { tokenizerProvider() }.also { tokenizer = it }
        }
    }

    /**
     * Helper to create an OrtSession with thread count configuration and NNAPI/XNNPACK
     * provider attempt falling back cleanly to CPU execution.
     */
    private fun createSession(env: OrtEnvironment, modelPath: String): OrtSession {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val numThreads = minOf(4, cores)

        // Attempt 1: Try NNAPI / XNNPACK
        try {
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(numThreads)
                // Opt-in only: NNAPI copes poorly with this graph's dynamic shapes (minutes per image on device)
                if (useAcceleratedProviders) {
                    try {
                        options.addNnapi()
                    } catch (_: Throwable) {
                        try {
                            val addXnnpackMethod = options.javaClass.getMethod("addXnnpack", Map::class.java)
                            addXnnpackMethod.invoke(options, emptyMap<String, String>())
                        } catch (_: Throwable) {
                            // Execution provider not supported on this device/runtime
                        }
                    }
                }
                return env.createSession(modelPath, options)
            } finally {
                try {
                    options.close()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            // Attempt 2: Fall back strictly to CPU
            val cpuOptions = OrtSession.SessionOptions()
            try {
                cpuOptions.setIntraOpNumThreads(numThreads)
                return env.createSession(modelPath, cpuOptions)
            } finally {
                try {
                    cpuOptions.close()
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Lazily loads and caches OrtEnvironment and both OrtSession instances on [Dispatchers.IO].
     */
    private suspend fun getSessions(): Triple<OrtEnvironment, OrtSession, OrtSession> = withContext(Dispatchers.IO) {
        ensureModelReady()

        var env = ortEnv
        var ts = textSession
        var vs = visionSession

        if (env != null && ts != null && vs != null) {
            return@withContext Triple(env, ts, vs)
        }

        synchronized(this@EmbeddingEngine) {
            env = ortEnv
            ts = textSession
            vs = visionSession

            if (env != null && ts != null && vs != null) {
                return@withContext Triple(env!!, ts!!, vs!!)
            }

            val textModelFile = File(modelStore.getModelDir(), TEXT_MODEL_FILE)
            val visionModelFile = File(modelStore.getModelDir(), VISION_MODEL_FILE)

            check(textModelFile.exists()) {
                "Text model file not found at ${textModelFile.absolutePath}"
            }
            check(visionModelFile.exists()) {
                "Vision model file not found at ${visionModelFile.absolutePath}"
            }

            val activeEnv = env ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
            val activeTs = ts ?: createSession(activeEnv, textModelFile.absolutePath).also { textSession = it }
            val activeVs = vs ?: createSession(activeEnv, visionModelFile.absolutePath).also { visionSession = it }

            Triple(activeEnv, activeTs, activeVs)
        }
    }

    /**
     * Generates a 128-d L2-normalized embedding vector for the given search query string.
     * Automatically prepends the model query prefix ("task: search result | query: ").
     *
     * @param query Raw query text from user.
     * @return 128-d L2-normalized FloatArray.
     * @throws IllegalStateException if model is not Ready or closed.
     */
    suspend fun embedText(query: String): FloatArray {
        ensureModelReady()
        val fullQuery = GemmaTokenizer.QUERY_PREFIX + query
        val tok = getTokenizer()
        val tokenIds = tok.encode(fullQuery, addSpecial = true)

        val (env, textSess, _) = getSessions()

        return inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                val tensorsToClose = ArrayList<OnnxTensor>(5)
                try {
                    val inputIdsLong = LongArray(tokenIds.size) { tokenIds[it].toLong() }
                    val attentionMaskLong = LongArray(tokenIds.size) { 1L }

                    val inputIdsTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(inputIdsLong),
                        longArrayOf(1, inputIdsLong.size.toLong())
                    ).also { tensorsToClose.add(it) }

                    val attentionMaskTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(attentionMaskLong),
                        longArrayOf(1, attentionMaskLong.size.toLong())
                    ).also { tensorsToClose.add(it) }

                    val emptyImageFeatures = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.allocate(0),
                        longArrayOf(0, 512)
                    ).also { tensorsToClose.add(it) }

                    val emptyVideoFeatures = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.allocate(0),
                        longArrayOf(0, 512)
                    ).also { tensorsToClose.add(it) }

                    val emptyAudioFeatures = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.allocate(0),
                        longArrayOf(0, 512)
                    ).also { tensorsToClose.add(it) }

                    val inputs = mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor,
                        "image_features" to emptyImageFeatures,
                        "video_features" to emptyVideoFeatures,
                        "audio_features" to emptyAudioFeatures
                    )

                    textSess.run(inputs).use { result ->
                        val opt = result.get("sentence_embedding")
                        val outTensor = if (opt.isPresent) opt.get() as OnnxTensor else result.get(0) as OnnxTensor
                        val fb = outTensor.floatBuffer.duplicate()
                        fb.rewind()
                        val fullEmbedding = FloatArray(fb.remaining())
                        fb.get(fullEmbedding)
                        truncateAndNormalize(fullEmbedding, EMBEDDING_DIM)
                    }
                } finally {
                    for (tensor in tensorsToClose) {
                        try {
                            tensor.close()
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    /**
     * Generates a 128-d L2-normalized embedding vector for an image [Bitmap].
     *
     * @param bitmap Software or hardware bitmap of the screenshot.
     * @return 128-d L2-normalized FloatArray, or null if the image could not be processed.
     * @throws IllegalStateException if model is not Ready or closed.
     */
    suspend fun embedImage(bitmap: Bitmap): FloatArray? {
        ensureModelReady()
        val patchBatch = ImagePatchifier.processBitmap(bitmap, maxSoftTokens) ?: return null
        val (env, textSess, visionSess) = getSessions()

        return inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                val imageFeaturesFloats: FloatArray

                // Step 1: Run vision encoder graph
                val visionTensors = ArrayList<OnnxTensor>(2)
                try {
                    val pixelValuesTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(patchBatch.pixelValues),
                        longArrayOf(1, patchBatch.numPatches.toLong(), 768)
                    ).also { visionTensors.add(it) }

                    val positionsTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(patchBatch.positions),
                        longArrayOf(1, patchBatch.numPatches.toLong(), 2)
                    ).also { visionTensors.add(it) }

                    val visionInputs = mapOf(
                        "pixel_values" to pixelValuesTensor,
                        "pixel_position_ids" to positionsTensor
                    )

                    visionSess.run(visionInputs).use { visionResult ->
                        val opt = visionResult.get("image_features")
                        val featTensor = if (opt.isPresent) opt.get() as OnnxTensor else visionResult.get(0) as OnnxTensor
                        val fb = featTensor.floatBuffer.duplicate()
                        fb.rewind()
                        val expectedCount = patchBatch.softTokens * 512
                        val floats = FloatArray(if (fb.remaining() >= expectedCount) expectedCount else fb.remaining())
                        fb.get(floats)
                        imageFeaturesFloats = floats
                    }
                } finally {
                    for (t in visionTensors) {
                        try {
                            t.close()
                        } catch (_: Throwable) {}
                    }
                }

                // Step 2: Run fusion / text model with visual tokens
                val fusionTensors = ArrayList<OnnxTensor>(5)
                try {
                    val inputIds = buildImageInputIds(patchBatch.softTokens)
                    val attentionMask = LongArray(inputIds.size) { 1L }

                    val inputIdsTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(inputIds),
                        longArrayOf(1, inputIds.size.toLong())
                    ).also { fusionTensors.add(it) }

                    val attentionMaskTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(attentionMask),
                        longArrayOf(1, attentionMask.size.toLong())
                    ).also { fusionTensors.add(it) }

                    val imgTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(imageFeaturesFloats),
                        longArrayOf(patchBatch.softTokens.toLong(), 512)
                    ).also { fusionTensors.add(it) }

                    val emptyVideoFeatures = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.allocate(0),
                        longArrayOf(0, 512)
                    ).also { fusionTensors.add(it) }

                    val emptyAudioFeatures = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.allocate(0),
                        longArrayOf(0, 512)
                    ).also { fusionTensors.add(it) }

                    val inputs = mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor,
                        "image_features" to imgTensor,
                        "video_features" to emptyVideoFeatures,
                        "audio_features" to emptyAudioFeatures
                    )

                    textSess.run(inputs).use { result ->
                        val opt = result.get("sentence_embedding")
                        val outTensor = if (opt.isPresent) opt.get() as OnnxTensor else result.get(0) as OnnxTensor
                        val fb = outTensor.floatBuffer.duplicate()
                        fb.rewind()
                        val fullEmbedding = FloatArray(fb.remaining())
                        fb.get(fullEmbedding)
                        truncateAndNormalize(fullEmbedding, EMBEDDING_DIM)
                    }
                } finally {
                    for (t in fusionTensors) {
                        try {
                            t.close()
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    /**
     * Generates a 128-d L2-normalized embedding vector for an image file at [uri].
     *
     * @param uri Content URI or file URI to the image.
     * @return 128-d L2-normalized FloatArray, or null if the URI could not be decoded.
     */
    suspend fun embedImage(uri: Uri): FloatArray? = withContext(Dispatchers.IO) {
        val bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return@withContext null

        try {
            embedImage(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Closes native OrtSession and OrtEnvironment resources.
     */
    override fun close() {
        isClosed = true
        try {
            textSession?.close()
        } catch (_: Throwable) {}
        textSession = null

        try {
            visionSession?.close()
        } catch (_: Throwable) {}
        visionSession = null

        try {
            ortEnv?.close()
        } catch (_: Throwable) {}
        ortEnv = null

        tokenizer = null
    }
}
