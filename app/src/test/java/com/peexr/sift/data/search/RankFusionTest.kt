package com.peexr.sift.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankFusionTest {

    @Test
    fun testRrfOrderingStandard() {
        val list1 = listOf(1L, 2L, 3L)
        val list2 = listOf(2L, 4L, 1L)

        // For k = 60:
        // 2L: 1/(60+2) + 1/(60+1) = 1/62 + 1/61 ≈ 0.032522
        // 1L: 1/(60+1) + 1/(60+3) = 1/61 + 1/63 ≈ 0.032266
        // 4L: 1/(60+2) = 1/62 ≈ 0.016129
        // 3L: 1/(60+3) = 1/63 ≈ 0.015873
        val fused = reciprocalRankFusion(listOf(list1, list2), k = 60)
        assertEquals(listOf(2L, 1L, 4L, 3L), fused)
    }

    @Test
    fun testRrfTieBreakingByBestRank() {
        // Using k = 0 to obtain exact fractions:
        // Item A (id 50L): rank 1 in list1 -> 1/1 = 1.0. Best rank = 1.
        // Item B (id 10L): rank 2 in list1 (1/2 = 0.5) + rank 2 in list2 (1/2 = 0.5) -> Total score = 1.0. Best rank = 2.
        // Even though 10L has lower ID, 50L has best rank 1 < 2, so 50L wins the tie-breaker.
        val list1 = listOf(50L, 10L)
        val list2 = listOf(99L, 10L) // 99L is at rank 1 in list 2 (score 1.0, best rank 1)
        // Let's create an isolated pair with equal score:
        // List A: [50L]
        // List B: [10L] with weights:
        // Instead of k=0 where 1/1=1, let's test directly with rankings and weights:
        // List 1 (weight 1.0): [50L] -> 50L has rank 1 in list 1 (score = 1/61, best rank = 1)
        // List 2 (weight 0.5): [10L]
        // List 3 (weight 0.5): [10L] -> 10L has rank 1 in list 2 & 3 (score = 0.5/61 + 0.5/61 = 1/61, best rank = 1)
        // To test DIFFERENT best rank with EQUAL score:
        // With k = 0:
        // List 1: [50L] -> score = 1/1 = 1.0, best rank = 1
        // List 2: [999L, 10L] -> 10L has rank 2, score = 1/2 = 0.5
        // List 3: [999L, 10L] -> 10L has rank 2, score = 1/2 = 0.5
        // Total score for 10L = 0.5 + 0.5 = 1.0. Best rank for 10L = 2.
        // 50L has score 1.0, best rank = 1.
        // 10L has score 1.0, best rank = 2.
        // 50L should come before 10L despite 10L < 50L.
        val r1 = listOf(50L)
        val r2 = listOf(999L, 10L)
        val r3 = listOf(999L, 10L)
        val result = reciprocalRankFusion(listOf(r1, r2, r3), k = 0)

        // 999L has rank 1 in r2 and r3: score = 1.0 + 1.0 = 2.0 (rank 1 overall)
        // 50L and 10L both have score 1.0. 50L has bestRank 1, 10L has bestRank 2.
        assertEquals(999L, result[0])
        assertEquals(50L, result[1])
        assertEquals(10L, result[2])
    }

    @Test
    fun testRrfTieBreakingByIdAscending() {
        val list1 = listOf(200L)
        val list2 = listOf(100L)

        // Both have score 1/(60+1) and bestRank 1
        val result = reciprocalRankFusion(listOf(list1, list2), k = 60)
        assertEquals(listOf(100L, 200L), result)
    }

    @Test
    fun testRrfDuplicateInSingleListCountsOnlyAtFirstPosition() {
        // 10L appears at index 0 (rank 1), index 2, index 3.
        // 20L appears at index 1 (rank 2).
        val list = listOf(10L, 20L, 10L, 10L)
        val result = reciprocalRankFusion(listOf(list), k = 60)

        assertEquals(2, result.size)
        assertEquals(10L, result[0])
        assertEquals(20L, result[1])
    }

    @Test
    fun testRrfWeights() {
        val list1 = listOf(10L)
        val list2 = listOf(20L)

        // Weight list2 more heavily than list1
        val weights = listOf(1.0f, 3.0f)
        val result = reciprocalRankFusion(listOf(list1, list2), k = 60, weights = weights)

        assertEquals(listOf(20L, 10L), result)
    }

    @Test
    fun testRrfLimit() {
        val list = listOf(1L, 2L, 3L, 4L, 5L)
        val result = reciprocalRankFusion(listOf(list), limit = 3)
        assertEquals(listOf(1L, 2L, 3L), result)

        val emptyLimit = reciprocalRankFusion(listOf(list), limit = 0)
        assertTrue(emptyLimit.isEmpty())

        val negativeLimit = reciprocalRankFusion(listOf(list), limit = -1)
        assertTrue(negativeLimit.isEmpty())
    }

    @Test
    fun testRrfEmptyRankings() {
        assertTrue(reciprocalRankFusion(emptyList()).isEmpty())
        assertTrue(reciprocalRankFusion(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRrfMismatchedWeightsThrows() {
        reciprocalRankFusion(listOf(listOf(1L), listOf(2L)), weights = listOf(1.0f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRrfNegativeKThrows() {
        reciprocalRankFusion(listOf(listOf(1L)), k = -1)
    }

    @Test
    fun testSanitizeFtsQueryBasic() {
        assertEquals("\"cat\"*", sanitizeFtsQuery("cat"))
        assertEquals("\"cat\" \"dog\"*", sanitizeFtsQuery("cat dog"))
        assertEquals("\"hello\" \"world\"*", sanitizeFtsQuery("  hello   world  "))
    }

    @Test
    fun testSanitizeFtsQuerySpecialCharacters() {
        // Strip FTS5 operators: ", *, (, ), :, ^, -, +, {, }, ~
        assertEquals("\"receipt\"*", sanitizeFtsQuery("\"receipt\"*"))
        assertEquals("\"coffee\" \"wifi\"*", sanitizeFtsQuery("(coffee): +wifi*"))
        assertEquals("\"account\" \"details\"*", sanitizeFtsQuery("^account {details}"))
    }

    @Test
    fun testSanitizeFtsQueryEmptyAndWhitespace() {
        assertEquals("", sanitizeFtsQuery(""))
        assertEquals("", sanitizeFtsQuery("    "))
        assertEquals("", sanitizeFtsQuery("*** : () - + ~ {}"))
    }
}
