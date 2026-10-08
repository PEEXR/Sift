package com.peexr.sift.ui.review

/**
 * Visual display mode for screenshot cards.
 */
enum class CardImageMode {
    FIT,
    TALL_TOP
}

/**
 * Computes the image display mode based on aspect ratio (width / height).
 * Very tall captures (aspect ratio < 0.35, e.g. scrolling screenshots) are displayed in [CardImageMode.TALL_TOP].
 * Null or normal aspect ratios (>= 0.35) are displayed in [CardImageMode.FIT].
 */
fun cardImageMode(aspect: Float?): CardImageMode {
    return if (aspect == null || aspect >= 0.35f) {
        CardImageMode.FIT
    } else {
        CardImageMode.TALL_TOP
    }
}

/**
 * Collapses whitespace runs and newlines to single spaces, trims the text,
 * and truncates to [maxChars] characters at a word boundary when possible,
 * appending an ellipsis ('…') when truncated.
 *
 * If blank, returns an empty string.
 * If a single long word exceeds [maxChars] with no spaces, hard-truncates with '…'.
 */
fun ocrSnippet(text: String, maxChars: Int = 80): String {
    if (maxChars <= 0) return ""
    val collapsed = text.trim().replace(Regex("\\s+"), " ")
    if (collapsed.isEmpty()) return ""
    if (collapsed.length <= maxChars) return collapsed

    val candidate = collapsed.substring(0, maxChars)
    val truncated = if (collapsed[maxChars] == ' ') {
        candidate.trimEnd()
    } else {
        val lastSpace = candidate.lastIndexOf(' ')
        if (lastSpace != -1) {
            candidate.substring(0, lastSpace).trimEnd()
        } else {
            candidate
        }
    }
    return if (truncated.isEmpty()) {
        candidate + "…"
    } else {
        truncated + "…"
    }
}
