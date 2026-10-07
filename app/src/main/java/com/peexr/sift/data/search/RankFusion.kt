package com.peexr.sift.data.search

/**
 * Pure Kotlin Reciprocal Rank Fusion (RRF) and FTS query sanitization.
 * Compatible with plain JVM unit tests without Android runtime dependencies.
 */

private class RrfCandidate(
    val id: Long,
    var score: Double = 0.0,
    var bestRank: Int = Int.MAX_VALUE
)

/**
 * Combines multiple ranking lists using Reciprocal Rank Fusion (RRF).
 *
 * score(id) = sum over rankings of (weight / (k + rank)), where rank is 1-based.
 * An id appearing multiple times within a single list counts only at its first position.
 * Output is sorted by score descending, ties broken by best (lowest) rank across all lists,
 * then by id ascending for determinism.
 *
 * @param rankings List of ranking lists, each containing screenshot/document IDs in rank order.
 * @param k Constant smoothing parameter (standard default is 60). Must be non-negative.
 * @param limit Maximum number of fused items to return. Must be positive.
 * @param weights Optional list of float weights multiplying each respective ranking list's contribution.
 * @return Ordered list of unique document IDs, truncated to at most [limit] items.
 */
fun reciprocalRankFusion(
    rankings: List<List<Long>>,
    k: Int = 60,
    limit: Int = Int.MAX_VALUE,
    weights: List<Float>? = null
): List<Long> {
    require(k >= 0) { "k must be non-negative, but was $k" }
    if (weights != null) {
        require(weights.size == rankings.size) {
            "weights size (${weights.size}) must match rankings size (${rankings.size})"
        }
    }
    if (rankings.isEmpty() || limit <= 0) {
        return emptyList()
    }

    val scoreMap = LinkedHashMap<Long, RrfCandidate>()
    val seenInList = HashSet<Long>()

    for (listIdx in rankings.indices) {
        val ranking = rankings[listIdx]
        val weight = weights?.get(listIdx)?.toDouble() ?: 1.0
        seenInList.clear()

        for (rank0 in ranking.indices) {
            val id = ranking[rank0]
            if (seenInList.add(id)) {
                val rank = rank0 + 1
                val candidate = scoreMap.getOrPut(id) { RrfCandidate(id) }
                candidate.score += weight / (k + rank)
                if (rank < candidate.bestRank) {
                    candidate.bestRank = rank
                }
            }
        }
    }

    if (scoreMap.isEmpty()) {
        return emptyList()
    }

    val sorted = scoreMap.values.sortedWith(
        Comparator { a, b ->
            val scoreCmp = b.score.compareTo(a.score) // desc
            if (scoreCmp != 0) return@Comparator scoreCmp

            val rankCmp = a.bestRank.compareTo(b.bestRank) // asc (lower rank number is better)
            if (rankCmp != 0) return@Comparator rankCmp

            a.id.compareTo(b.id) // asc
        }
    )

    return sorted.take(limit).map { it.id }
}

private val FTS_SPECIAL_CHARACTERS_REGEX = Regex("""["*():^\-+{}~]""")

/**
 * Sanitizes a raw user search query string for SQLite FTS5 MATCH queries.
 *
 * Splits on whitespace, strips FTS5 special characters (`"*():^-+` etc.), drops empty tokens,
 * wraps each term in double quotes, and appends `*` to the last term for as-you-type prefix matching.
 * Returns an empty string if nothing remains (caller must skip the FTS query in that case).
 */
fun sanitizeFtsQuery(raw: String): String {
    val terms = raw.split(Regex("\\s+"))
        .map { term -> term.replace(FTS_SPECIAL_CHARACTERS_REGEX, "").trim() }
        .filter { it.isNotEmpty() }

    if (terms.isEmpty()) {
        return ""
    }

    return terms.mapIndexed { index, term ->
        if (index == terms.lastIndex) {
            "\"$term\"*"
        } else {
            "\"$term\""
        }
    }.joinToString(" ")
}
