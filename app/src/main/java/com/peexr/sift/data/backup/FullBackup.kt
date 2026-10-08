package com.peexr.sift.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable
data class RefKey(
    val displayName: String,
    val dateAdded: Long,
    val sizeBytes: Long
)

@Serializable
data class BackupSettings(
    val autoFillSensitivity: String,
    val indexingQuality: String,
    val autoIndexNewScreenshots: Boolean,
    val measuredSecPerImageAt280: Double?
)

@Serializable
data class FullCollection(
    val key: String, /* stable id inside the file */
    val name: String,
    val description: String,
    val colorHex: String,
    val autoFill: Boolean
)

@Serializable
data class FullMembership(
    val collectionKey: String,
    val source: String, /* AUTO | MANUAL | EXCLUDED */
    val score: Float?
)

@Serializable
data class FullScreenshot(
    val displayName: String,
    val dateAdded: Long,
    val sizeBytes: Long,
    val ocrText: String,
    val visualLabels: String,
    val isProcessed: Boolean,
    val embeddingB64: String?, /* 128 floats, little-endian, Base64 NO_WRAP via java.util.Base64 */
    val memberships: List<FullMembership> = emptyList()
) {
    fun toRefKey(): RefKey = RefKey(
        displayName = displayName,
        dateAdded = dateAdded,
        sizeBytes = sizeBytes
    )
}

@Serializable
data class FullBackup(
    val version: Int = 1,
    val createdAtEpochSec: Long,
    val appVersion: String,
    val settings: BackupSettings,
    val collections: List<FullCollection>,
    val screenshots: List<FullScreenshot>
)

data class RestoreSummary(
    val screenshotsMatched: Int,
    val screenshotsUnmatched: Int,
    val embeddingsRestored: Int,
    val collectionsCreated: Int,
    val collectionsReused: Int,
    val membershipsApplied: Int
)

private val fullBackupJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}

const val BACKUP_REMINDER_MESSAGE = "No recent backup. Indexing took a while — keep a copy."
const val THIRTY_DAYS_SEC = 30L * 24 * 60 * 60

/**
 * Pure function returning a calm warning reminder when there has been no backup,
 * or the last backup is older than 30 days and at least 100 screenshots have been indexed.
 */
fun backupReminderText(
    lastBackupEpochSec: Long?,
    nowEpochSec: Long,
    indexedCount: Int
): String? {
    if (indexedCount < 100) return null
    if (lastBackupEpochSec == null) return BACKUP_REMINDER_MESSAGE
    val ageSec = nowEpochSec - lastBackupEpochSec
    if (ageSec > THIRTY_DAYS_SEC) return BACKUP_REMINDER_MESSAGE
    return null
}

/**
 * Encodes a 128-float embedding into little-endian Base64 using java.util.Base64.
 *
 * @throws IllegalArgumentException if size is not 128 or contains non-finite values.
 */
fun embeddingToBase64(floats: FloatArray): String {
    require(floats.size == 128) { "Expected 128 floats, got ${floats.size}" }
    val bb = ByteBuffer.allocate(128 * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (f in floats) {
        require(f.isFinite()) { "Embedding contains non-finite float: $f" }
        bb.putFloat(f)
    }
    return Base64.getEncoder().encodeToString(bb.array())
}

/**
 * Decodes little-endian Base64 into a 128-float array.
 * Returns null unless input produces exactly 128 finite floats.
 */
fun embeddingFromBase64(b64: String): FloatArray? {
    return try {
        val bytes = Base64.getDecoder().decode(b64.trim())
        if (bytes.size != 128 * 4) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(128)
        for (i in 0 until 128) {
            val f = bb.getFloat()
            if (!f.isFinite()) return null
            floats[i] = f
        }
        floats
    } catch (_: Exception) {
        null
    }
}

/**
 * Serializes [FullBackup] to GZIP-compressed JSON bytes.
 */
fun encodeFullBackup(b: FullBackup): ByteArray {
    val jsonString = fullBackupJson.encodeToString(b)
    val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
    val baos = ByteArrayOutputStream()
    GZIPOutputStream(baos).use { gzipOut ->
        gzipOut.write(jsonBytes)
        gzipOut.finish()
    }
    return baos.toByteArray()
}

private fun isGzip(bytes: ByteArray): Boolean {
    return bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
}

/**
 * Deserializes [FullBackup] from either plain or GZIP-compressed JSON bytes.
 * Fails with a clear message "That file isn't a Sift backup" for malformed input or version > 1.
 */
fun decodeFullBackup(bytes: ByteArray): Result<FullBackup> = runCatching {
    val jsonString = try {
        if (isGzip(bytes)) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gis ->
                gis.bufferedReader(Charsets.UTF_8).readText()
            }
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        throw IllegalArgumentException("That file isn't a Sift backup", e)
    }

    val backup = try {
        fullBackupJson.decodeFromString<FullBackup>(jsonString)
    } catch (e: Exception) {
        throw IllegalArgumentException("That file isn't a Sift backup", e)
    }

    if (backup.version > 1 || backup.version < 1) {
        throw IllegalArgumentException("That file isn't a Sift backup")
    }
    backup
}

/**
 * Matches backup screenshot refs against current device candidates using exact
 * (displayName, dateAdded, sizeBytes) triples.
 *
 * If duplicate candidates exist, they are handed out in order so duplicates map one-to-one.
 * Returns a list where each element corresponds to the backup screenshot index; null if no match.
 */
fun restoreMatches(
    refs: List<RefKey>,
    candidates: List<Candidate>
): List<Long?> {
    if (refs.isEmpty()) return emptyList()

    val candidateMap = HashMap<Triple<String, Long, Long>, ArrayDeque<Long>>()
    for (candidate in candidates) {
        val key = Triple(candidate.displayName, candidate.dateAdded, candidate.sizeBytes)
        val queue = candidateMap.getOrPut(key) { ArrayDeque() }
        queue.add(candidate.id)
    }

    return refs.map { ref ->
        val key = Triple(ref.displayName, ref.dateAdded, ref.sizeBytes)
        val queue = candidateMap[key]
        if (queue != null && queue.isNotEmpty()) {
            queue.removeFirst()
        } else {
            null
        }
    }
}
