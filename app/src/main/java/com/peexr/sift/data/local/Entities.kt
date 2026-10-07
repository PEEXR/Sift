package com.peexr.sift.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "screenshots")
data class ScreenshotEntity(
    @PrimaryKey val id: Long,
    val uri: String,
    val displayName: String,
    val dateAdded: Long,
    val sizeBytes: Long,
    val ocrText: String = "",
    val visualLabels: String = "", // Comma-separated or JSON
    val isProcessed: Boolean = false,
    val isJunk: Boolean = false,
    val junkConfidence: Double = 0.0,
    val junkRationale: String = "",
    val embedding: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ScreenshotEntity

        if (id != other.id) return false
        if (uri != other.uri) return false
        if (displayName != other.displayName) return false
        if (dateAdded != other.dateAdded) return false
        if (sizeBytes != other.sizeBytes) return false
        if (ocrText != other.ocrText) return false
        if (visualLabels != other.visualLabels) return false
        if (isProcessed != other.isProcessed) return false
        if (isJunk != other.isJunk) return false
        if (junkConfidence != other.junkConfidence) return false
        if (junkRationale != other.junkRationale) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + dateAdded.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + ocrText.hashCode()
        result = 31 * result + visualLabels.hashCode()
        result = 31 * result + isProcessed.hashCode()
        result = 31 * result + isJunk.hashCode()
        result = 31 * result + junkConfidence.hashCode()
        result = 31 * result + junkRationale.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}

data class EmbeddingRow(
    val id: Long,
    val embedding: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbeddingRow

        if (id != other.id) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

data class ScreenshotUriRow(
    val id: Long,
    val uri: String
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val colorHex: String = "#6750A4"
)

@Entity(
    tableName = "screenshot_collections",
    primaryKeys = ["screenshotId", "collectionId"],
    indices = [Index("collectionId")],
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ScreenshotCollectionCrossRef(
    val screenshotId: Long,
    val collectionId: String
)
