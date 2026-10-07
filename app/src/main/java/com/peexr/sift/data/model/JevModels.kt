package com.peexr.sift.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class JevRequest(
    val model: String = "jev-latest",
    val state: JsonElement,
    val questions: Map<String, JevQuestion>
)

@Serializable
data class JevQuestion(
    val type: String, // "choice", "noul", "score"
    val instructions: String,
    val criteria: JsonElement? = null
)

@Serializable
data class JevResponse(
    val answers: Map<String, JevAnswer> = emptyMap()
)

@Serializable
data class JevAnswer(
    val choice: String? = null,
    val noul: Boolean? = null,
    val score: Int? = null,
    val confidence: Double? = null,
    val rationale: String? = null
)

data class ScreenshotItem(
    val id: Long,
    val uri: String,
    val displayName: String,
    val dateAdded: Long,
    val sizeBytes: Long,
    val ocrText: String = "",
    val visualLabels: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val isProcessed: Boolean = false,
    val isJunk: Boolean = false,
    val junkConfidence: Double = 0.0,
    val junkRationale: String = ""
)

data class CollectionItem(
    val id: String,
    val name: String,
    val description: String,
    val colorHex: String? = null,
    val count: Int = 0
)
