package com.peexr.sift.data.cleanup

import com.peexr.sift.data.search.cosineSimilarity

/**
 * Filter for screenshot age in cleanup and search flows.
 */
enum class AgeFilter(val days: Int?, val label: String) {
    ANY(null, "Any age"),
    MONTH(30, "Older than 1 month"),
    QUARTER(90, "Older than 3 months"),
    HALF_YEAR(180, "Older than 6 months"),
    YEAR(365, "Older than 1 year")
}

/**
 * Checks whether [dateAddedSeconds] is strictly older than [days] relative to [nowSeconds].
 * A null [days] returns true (no age restriction).
 */
fun isOlderThan(dateAddedSeconds: Long, nowSeconds: Long, days: Int?): Boolean {
    if (days == null) return true
    val thresholdSeconds = days.toLong() * 86400L
    return (nowSeconds - dateAddedSeconds) > thresholdSeconds
}

/**
 * Categories of code/temporary screenshots.
 */
enum class CodeKind {
    OTP,
    EXPIRING
}

private val OTP_KEYWORDS = listOf(
    "otp",
    "one-time",
    "one time",
    "verification code",
    "security code",
    "login code",
    "passcode",
    "is your code",
    "your code"
)

private val EXPIRING_PHRASES = listOf(
    "expires in",
    "expires on",
    "valid for",
    "valid until",
    "expiring",
    "will expire",
    "expire in",
    "use within"
)

private val STANDALONE_CODE_REGEX = Regex("""(?<!\d)\d{4,8}(?!\d)""")

/**
 * Deterministically checks if OCR text looks like a one-time code (OTP) or an expiring screenshot.
 * Blank text returns null. OTP takes priority over expiring when both match.
 */
fun looksLikeCode(ocrText: String): CodeKind? {
    if (ocrText.isBlank()) return null
    val lower = ocrText.lowercase()

    val hasOtpKeyword = OTP_KEYWORDS.any { lower.contains(it) }
    val hasStandaloneCode = STANDALONE_CODE_REGEX.containsMatchIn(ocrText)

    if (hasOtpKeyword && hasStandaloneCode) {
        return CodeKind.OTP
    }

    val hasExpiringPhrase = EXPIRING_PHRASES.any { lower.contains(it) }
    if (hasExpiringPhrase) {
        return CodeKind.EXPIRING
    }

    return null
}

/**
 * Lightweight reference representing an item with an L2-normalised 128-d embedding vector.
 */
data class EmbeddedRef(
    val id: Long,
    val dateAdded: Long,
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddedRef
        if (id != other.id) return false
        if (dateAdded != other.dateAdded) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + dateAdded.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

/**
 * Finds near-duplicate bursts within [items].
 * Items are sorted by dateAdded ASC. A burst is a maximal run where each item is within
 * [windowSeconds] of the previous one AND has cosine similarity >= [threshold] to the burst's first item.
 * Returns only bursts of size >= 2, with each burst ordered oldest to newest.
 */
fun findBursts(
    items: List<EmbeddedRef>,
    windowSeconds: Long = 600,
    threshold: Float = 0.92f
): List<List<Long>> {
    if (items.size < 2) return emptyList()

    val sorted = items.sortedWith(compareBy({ it.dateAdded }, { it.id }))
    val bursts = mutableListOf<List<Long>>()

    var i = 0
    while (i < sorted.size) {
        val burst = mutableListOf(sorted[i])
        var j = i + 1
        while (j < sorted.size) {
            val prev = sorted[j - 1]
            val curr = sorted[j]
            val withinWindow = (curr.dateAdded - prev.dateAdded) <= windowSeconds
            val sim = cosineSimilarity(burst[0].embedding, curr.embedding)
            if (withinWindow && sim >= threshold) {
                burst.add(curr)
                j++
            } else {
                break
            }
        }
        if (burst.size >= 2) {
            bursts.add(burst.map { it.id })
        }
        i = j
    }

    return bursts
}

/**
 * Returns candidate IDs for deletion from near-duplicate [bursts].
 * The newest item of each burst is kept; every other ID is returned for deletion.
 */
fun burstDeletionCandidates(bursts: List<List<Long>>): List<Long> {
    return bursts.flatMap { it.dropLast(1) }
}
