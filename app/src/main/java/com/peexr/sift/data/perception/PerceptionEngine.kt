package com.peexr.sift.data.perception

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class PerceptionResult(
    val ocrText: String,
    val visualLabels: List<String>
)

class PerceptionEngine(private val context: Context) {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build()
    )

    suspend fun analyzeImage(uri: Uri): Result<PerceptionResult> = withContext(Dispatchers.IO) {
        try {
            val inputImage = InputImage.fromFilePath(context, uri)

            // Run OCR and Image Labeler in parallel
            val ocrDeferred = async {
                val visionText = textRecognizer.process(inputImage).await()
                visionText.text.trim()
            }

            val labelsDeferred = async {
                val labels = imageLabeler.process(inputImage).await()
                labels.map { it.text.lowercase() }
            }

            val ocrText = ocrDeferred.await()
            val labels = labelsDeferred.await()

            Result.success(PerceptionResult(ocrText = ocrText, visualLabels = labels))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        textRecognizer.close()
        imageLabeler.close()
    }
}
