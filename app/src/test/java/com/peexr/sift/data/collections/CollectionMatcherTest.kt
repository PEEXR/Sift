package com.peexr.sift.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CollectionMatcherTest {

    @Test
    fun zScore_withHandMadeNormalizedVectors_computesAccurateMath() {
        val image = floatArrayOf(1f, 0f, 0f, 0f)
        val collection = floatArrayOf(0.8f, 0.6f, 0f, 0f)

        val background = listOf(
            floatArrayOf(0.2f, 0.9797959f, 0f, 0f),
            floatArrayOf(0.4f, 0.9165151f, 0f, 0f),
            floatArrayOf(0.6f, 0.8f, 0f, 0f)
        )

        // Dots with image are 0.2, 0.4, 0.6 -> mean = 0.4
        // Population variance = ((0.2-0.4)^2 + (0.4-0.4)^2 + (0.6-0.4)^2) / 3 = 0.08 / 3
        // Dot with collection = 0.8 -> deviation = 0.4
        // Exact z = 0.4 / sqrt(0.08 / 3) = sqrt(6)
        val expectedZ = sqrt(6.0).toFloat()
        val actualZ = CollectionMatcher.zScore(image, collection, background)

        assertEquals(expectedZ, actualZ, 1e-4f)
    }

    @Test
    fun match_thresholdFiltering_onlyKeepsCandidatesAboveThreshold() {
        val background = listOf(
            floatArrayOf(0.2f, 0f, 0f, 0f),
            floatArrayOf(0.4f, 0f, 0f, 0f),
            floatArrayOf(0.6f, 0f, 0f, 0f)
        )
        val collection = floatArrayOf(1f, 0f, 0f, 0f)

        // For image [x, 0, 0, 0]: background dots are 0.2*x, 0.4*x, 0.6*x -> mean = 0.4*x, variance = (0.08/3)*x^2
        // collection dot = 1.0*x -> deviation = 0.6*x -> z = (0.6*x) / (sqrt(0.08/3)*x) = 0.6 / sqrt(0.08/3) = sqrt(13.5) ≈ 3.674
        val imgHigh = floatArrayOf(1f, 0f, 0f, 0f)

        // For image matching background closely:
        // background dots: 0.1, 0.5, 0.9 -> mean = 0.5, var = 0.32/3, collection dot = 0.5 -> deviation = 0 -> z = 0
        val imgZero = floatArrayOf(0f, 1f, 0f, 0f)

        val screenshots = listOf(
            Pair(101L, imgHigh),
            Pair(102L, imgZero)
        )

        val matches = CollectionMatcher.match(
            collection = collection,
            screenshots = screenshots,
            background = background,
            threshold = 1.5f
        )

        assertEquals(1, matches.size)
        assertEquals(101L, matches[0].first)
        assertTrue(matches[0].second >= 1.5f)
    }

    @Test
    fun match_sortOrder_returnsCandidatesSortedByZDescending() {
        val background = listOf(
            floatArrayOf(0.2f, 0f, 0f, 0f),
            floatArrayOf(0.4f, 0f, 0f, 0f),
            floatArrayOf(0.6f, 0f, 0f, 0f)
        )

        val image = floatArrayOf(1f, 0f, 0f, 0f)
        // Background dots: 0.2, 0.4, 0.6 -> mean = 0.4, std = sqrt(0.08/3) ≈ 0.1633

        // Col1 dot = 0.8 -> dev = 0.4 -> z ≈ 2.45
        // Col2 dot = 0.7 -> dev = 0.3 -> z ≈ 1.84
        val colHigh = floatArrayOf(0.8f, 0f, 0f, 0f)
        val colMed = floatArrayOf(0.7f, 0f, 0f, 0f)

        val screenshots = listOf(
            Pair(1L, floatArrayOf(0.7f, 0f, 0f, 0f)), // will have lower similarity to collection
            Pair(2L, floatArrayOf(1.0f, 0f, 0f, 0f)), // will have higher similarity to collection
            Pair(3L, floatArrayOf(0.85f, 0f, 0f, 0f))
        )
        val collection = floatArrayOf(1f, 0f, 0f, 0f)

        val results = CollectionMatcher.match(
            collection = collection,
            screenshots = screenshots,
            background = background,
            threshold = 1.0f
        )

        assertTrue(results.size >= 2)
        for (i in 0 until results.size - 1) {
            assertTrue(
                "Expected results to be sorted descending: ${results[i].second} >= ${results[i + 1].second}",
                results[i].second >= results[i + 1].second
            )
        }
    }

    @Test
    fun zScore_whenStdNearZero_returnsZero() {
        val image = floatArrayOf(1f, 0f, 0f, 0f)
        val collection = floatArrayOf(1f, 0f, 0f, 0f)

        // Identical background vectors -> zero variance
        val backgroundIdentical = listOf(
            floatArrayOf(0.5f, 0.5f, 0f, 0f),
            floatArrayOf(0.5f, 0.5f, 0f, 0f),
            floatArrayOf(0.5f, 0.5f, 0f, 0f)
        )

        val z = CollectionMatcher.zScore(image, collection, backgroundIdentical)
        assertEquals(0f, z, 1e-6f)
    }

    @Test
    fun emptyInputs_returnZeroOrEmptyList() {
        val image = floatArrayOf(1f, 0f, 0f, 0f)
        val collection = floatArrayOf(1f, 0f, 0f, 0f)
        val background = listOf(floatArrayOf(0.5f, 0f, 0f, 0f))

        // Empty background for zScore
        assertEquals(0f, CollectionMatcher.zScore(image, collection, emptyList()), 1e-6f)

        // Empty screenshots for match
        val emptyScreenshots = CollectionMatcher.match(collection, emptyList(), background)
        assertTrue(emptyScreenshots.isEmpty())

        // Empty background for match
        val emptyBackground = CollectionMatcher.match(collection, listOf(Pair(1L, image)), emptyList())
        assertTrue(emptyBackground.isEmpty())
    }

    @Test
    fun sameScreenshot_matchesTwoDifferentCollections() {
        val background = listOf(
            floatArrayOf(0.1f, 0.1f, 0.8f, 0f),
            floatArrayOf(0.2f, 0.2f, 0.7f, 0f),
            floatArrayOf(0.3f, 0.3f, 0.6f, 0f)
        )

        // Screenshot has strong components in both dim 0 and dim 1
        val screenshot = floatArrayOf(0.7071f, 0.7071f, 0f, 0f)
        val candidates = listOf(Pair(42L, screenshot))

        // Collection A aligns with dim 0
        val collectionA = floatArrayOf(1f, 0f, 0f, 0f)
        // Collection B aligns with dim 1
        val collectionB = floatArrayOf(0f, 1f, 0f, 0f)

        val matchesA = CollectionMatcher.match(collectionA, candidates, background, threshold = 1.0f)
        val matchesB = CollectionMatcher.match(collectionB, candidates, background, threshold = 1.0f)

        assertEquals(1, matchesA.size)
        assertEquals(42L, matchesA[0].first)
        assertTrue(matchesA[0].second >= 1.0f)

        assertEquals(1, matchesB.size)
        assertEquals(42L, matchesB[0].first)
        assertTrue(matchesB[0].second >= 1.0f)
    }

    @Test
    fun backgroundDescriptions_containsExactly20SpecifiedStrings() {
        assertEquals(20, CollectionMatcher.BACKGROUND_DESCRIPTIONS.size)
        val expected = listOf(
            "meme or joke image",
            "chat or messaging conversation",
            "social media post",
            "study notes or textbook page",
            "exam question paper",
            "document or form",
            "receipt or payment",
            "ticket or booking",
            "source code",
            "video game screen",
            "map",
            "shopping product page",
            "phone settings screen",
            "news article",
            "video app screen",
            "photo of a person",
            "music player",
            "calendar or schedule",
            "email inbox",
            "home screen with app icons"
        )
        assertEquals(expected, CollectionMatcher.BACKGROUND_DESCRIPTIONS)
    }
}
