package com.peexr.sift.data.embedding

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed class ModelState {
    object NotInstalled : ModelState()
    data class Downloading(val bytesDone: Long, val bytesTotal: Long, val currentFile: String) : ModelState()
    object Ready : ModelState()
    data class Failed(val message: String) : ModelState()
}

data class ModelFileInfo(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val isExternalData: Boolean = false
)

class ModelStore(
    private val context: Context,
    val manifest: List<ModelFileInfo> = MANIFEST
) {

    companion object {
        const val BASE_URL = "https://huggingface.co/onnx-community/embeddinggemma-2-ONNX/resolve/main/"
        
        val MANIFEST = listOf(
            ModelFileInfo("onnx/model_q4.onnx", BASE_URL + "onnx/model_q4.onnx", 490742, "F9EEBA97ACDDF139B8EE2DDF04BC30DCEAFA88DE93FADF74D7644E0D61A477A9"),
            ModelFileInfo("onnx/model_q4.onnx_data", BASE_URL + "onnx/model_q4.onnx_data", 174028800, "C3975F2D1AB7A1878AE31A7D7A9B7804A827AFF3800B60DFCEAFCE21CAC3DF49", isExternalData = true),
            ModelFileInfo("onnx/vision_encoder_q4.onnx", BASE_URL + "onnx/vision_encoder_q4.onnx", 159400, "7EA284226D4938F0AD921AB091F1D80A9CA699AA802984EF5CD5EEC4F4761D96"),
            ModelFileInfo("onnx/vision_encoder_q4.onnx_data", BASE_URL + "onnx/vision_encoder_q4.onnx_data", 108957696, "0A9D6C927334F152A33DD90874F65D6EA5228999ABE6A450D3F7813677FA704C", isExternalData = true),
            ModelFileInfo("tokenizer.json", BASE_URL + "tokenizer.json", 32170510, "4D777EF5BDC1AA36227ABDFB77C3E49E7B9C892D16E1B6BDA41C393504828BE4")
        )
    }

    private val _state = MutableStateFlow<ModelState>(ModelState.NotInstalled)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun updateState(newState: ModelState) {
        _state.value = newState
    }

    fun getModelDir(): File {
        val dir = File(context.filesDir, "models/embeddinggemma2-q4")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val onnxDir = File(dir, "onnx")
        if (!onnxDir.exists()) {
            onnxDir.mkdirs()
        }
        return dir
    }

    fun getFile(fileInfo: ModelFileInfo): File {
        return File(getModelDir(), fileInfo.name)
    }

    fun isInstalled(): Boolean {
        for (fileInfo in manifest) {
            val file = getFile(fileInfo)
            if (!file.exists() || file.length() != fileInfo.sizeBytes) {
                return false
            }
        }
        return true
    }

    init {
        if (isInstalled()) {
            _state.value = ModelState.Ready
        }
    }
}
