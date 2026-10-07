package com.peexr.sift.data.model

data class CollectionSummary(
    val id: String,
    val name: String,
    val description: String,
    val colorHex: String,
    val memberCount: Int,
    val reviewCount: Int,
    val totalBytes: Long,
    val previewUris: List<String>
)

data class CollectionMemberItem(
    val item: ScreenshotItem,
    val kept: Boolean
)

data class ScreenshotDetail(
    val item: ScreenshotItem,
    val hasEmbedding: Boolean
)
