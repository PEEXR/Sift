package com.peexr.sift.ui

import com.peexr.sift.data.model.CollectionMemberItem
import com.peexr.sift.data.model.CollectionSummary
import com.peexr.sift.data.model.ScreenshotDetail
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.ui.review.ReviewCard
import com.peexr.sift.ui.viewer.ViewerCollectionRow

/**
 * Shared fake data builders for Compose UI tests.
 */
fun fakeScreenshot(
    id: Long = 1L,
    displayName: String = "screenshot_$id.png",
    dateAdded: Long = 1700000000L,
    sizeBytes: Long = 1024L * 1024L * 2L,
    uri: String = "content://invalid/$id",
    ocrText: String = "",
    visualLabels: List<String> = emptyList(),
    collections: List<String> = emptyList(),
    isProcessed: Boolean = true,
    isJunk: Boolean = false,
    junkConfidence: Double = 0.0,
    junkRationale: String = ""
): ScreenshotItem = ScreenshotItem(
    id = id,
    uri = uri,
    displayName = displayName,
    dateAdded = dateAdded,
    sizeBytes = sizeBytes,
    ocrText = ocrText,
    visualLabels = visualLabels,
    collections = collections,
    isProcessed = isProcessed,
    isJunk = isJunk,
    junkConfidence = junkConfidence,
    junkRationale = junkRationale
)

fun fakeCollectionSummary(
    id: String = "col_1",
    name: String = "Memes",
    description: String = "funny memes and reaction images",
    colorHex: String = "#6750A4",
    memberCount: Int = 10,
    reviewCount: Int = 3,
    totalBytes: Long = 1024L * 1024L * 50L,
    previewUris: List<String> = listOf("content://invalid/1", "content://invalid/2")
): CollectionSummary = CollectionSummary(
    id = id,
    name = name,
    description = description,
    colorHex = colorHex,
    memberCount = memberCount,
    reviewCount = reviewCount,
    totalBytes = totalBytes,
    previewUris = previewUris
)

fun fakeCollectionMember(
    item: ScreenshotItem = fakeScreenshot(),
    kept: Boolean = false
): CollectionMemberItem = CollectionMemberItem(
    item = item,
    kept = kept
)

fun fakeReviewCard(
    item: ScreenshotItem = fakeScreenshot(),
    confidenceLabel: String = "Unsure",
    originalScore: Float? = null,
    isStrong: Boolean = false
): ReviewCard = ReviewCard(
    item = item,
    confidenceLabel = confidenceLabel,
    originalScore = originalScore,
    isStrong = isStrong
)

fun fakeScreenshotDetail(
    item: ScreenshotItem = fakeScreenshot(),
    hasEmbedding: Boolean = false
): ScreenshotDetail = ScreenshotDetail(
    item = item,
    hasEmbedding = hasEmbedding
)

fun fakeViewerCollectionRow(
    summary: CollectionSummary = fakeCollectionSummary(),
    isMember: Boolean = false
): ViewerCollectionRow = ViewerCollectionRow(
    summary = summary,
    isMember = isMember
)
