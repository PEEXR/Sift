package com.peexr.sift.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ScreenshotRef(
    val displayName: String,
    val dateAdded: Long,
    val sizeBytes: Long
)

@Serializable
data class BackupCollection(
    val name: String,
    val description: String,
    val colorHex: String,
    val autoFill: Boolean = true,
    val kept: List<ScreenshotRef> = emptyList(),
    val excluded: List<ScreenshotRef> = emptyList()
)

@Serializable
data class BackupFile(
    val version: Int = 1,
    val collections: List<BackupCollection> = emptyList()
)

data class Candidate(
    val id: Long,
    val displayName: String,
    val dateAdded: Long,
    val sizeBytes: Long
)

private val backupJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

fun encodeBackup(file: BackupFile): String {
    return backupJson.encodeToString(file)
}

fun decodeBackup(json: String): Result<BackupFile> = runCatching {
    val backup = backupJson.decodeFromString<BackupFile>(json)
    if (backup.version > 1) {
        throw IllegalArgumentException("Unsupported backup version: ${backup.version} (maximum supported is 1)")
    }
    backup
}

fun matchRefs(refs: List<ScreenshotRef>, candidates: List<Candidate>): List<Long> {
    if (refs.isEmpty() || candidates.isEmpty()) {
        return emptyList()
    }
    val candidateMap = HashMap<Triple<String, Long, Long>, Long>()
    for (candidate in candidates) {
        val key = Triple(candidate.displayName, candidate.dateAdded, candidate.sizeBytes)
        if (!candidateMap.containsKey(key)) {
            candidateMap[key] = candidate.id
        }
    }
    return refs.mapNotNull { ref ->
        candidateMap[Triple(ref.displayName, ref.dateAdded, ref.sizeBytes)]
    }
}
