package com.peexr.sift.data.collections

import com.peexr.sift.data.search.cosineSimilarity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class RefineQueryTest {

    @Test
    fun refineQuery_noFeedback_returnsSameArray() {
        val base = floatArrayOf(1f, 0f, 0f, 0f)
        val result = refineQuery(base, emptyList(), emptyList())
        assertSame(base, result)
    }

    @Test
    fun refineQuery_positivesMoveVectorTowardPositives() {
        val base = floatArrayOf(1f, 0f, 0f, 0f)
        val positives = listOf(floatArrayOf(0f, 1f, 0f, 0f))
        val meanP = floatArrayOf(0f, 1f, 0f, 0f)

        val result = refineQuery(base, positives, emptyList())

        val cosBase = cosineSimilarity(base, meanP)
        val cosResult = cosineSimilarity(result, meanP)
        assertTrue(
            "Result should be closer to mean positive than base was: cosResult=$cosResult, cosBase=$cosBase",
            cosResult > cosBase
        )
    }

    @Test
    fun refineQuery_negativesMoveVectorAwayFromNegatives() {
        val base = floatArrayOf(0.7071068f, 0.7071068f, 0f, 0f)
        val negatives = listOf(floatArrayOf(1f, 0f, 0f, 0f))
        val meanN = floatArrayOf(1f, 0f, 0f, 0f)

        val result = refineQuery(base, emptyList(), negatives)

        val cosBase = cosineSimilarity(base, meanN)
        val cosResult = cosineSimilarity(result, meanN)
        assertTrue(
            "Result should be farther from mean negative than base was: cosResult=$cosResult, cosBase=$cosBase",
            cosResult < cosBase
        )
    }

    @Test
    fun refineQuery_weightScalesWithCount_onePositiveMovesLessThanTen() {
        val base = floatArrayOf(1f, 0f, 0f, 0f)
        val target = floatArrayOf(0f, 1f, 0f, 0f)
        val onePos = listOf(target)
        val tenPos = List(10) { target }

        val result1 = refineQuery(base, onePos, emptyList())
        val result10 = refineQuery(base, tenPos, emptyList())

        val cos1 = cosineSimilarity(result1, target)
        val cos10 = cosineSimilarity(result10, target)
        assertTrue(
            "10 positives should move the vector more toward target than 1: cos10=$cos10, cos1=$cos1",
            cos10 > cos1
        )

        val cosBase1 = cosineSimilarity(result1, base)
        val cosBase10 = cosineSimilarity(result10, base)
        assertTrue(
            "1 positive should stay closer to base than 10 positives: cosBase1=$cosBase1, cosBase10=$cosBase10",
            cosBase1 > cosBase10
        )
    }

    @Test
    fun refineQuery_outputIsUnitLength() {
        val base = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val pos = listOf(
            floatArrayOf(0.2f, 0.8f, 0.1f, 0.5f),
            floatArrayOf(0.9f, 0.1f, 0.3f, 0.2f)
        )
        val neg = listOf(floatArrayOf(0.1f, 0.2f, 0.8f, 0.4f))

        val result = refineQuery(base, pos, neg)

        var sumSq = 0.0
        for (v in result) {
            val d = v.toDouble()
            sumSq += d * d
        }
        val norm = sqrt(sumSq)
        assertEquals(1.0, norm, 1e-5)
    }

    @Test
    fun refineQuery_degenerateCancellingInput_fallsBackToBase() {
        val base = floatArrayOf(1f, 0f, 0f, 0f)
        // Negatives completely cancel base: base - 1.0 * (1/1) * base = 0
        val negatives = listOf(floatArrayOf(1f, 0f, 0f, 0f))
        val result = refineQuery(base, emptyList(), negatives, beta = 1.0f, maxFeedback = 1)
        assertArrayEquals(base, result, 1e-6f)

        // Non-finite input
        val nanPositives = listOf(floatArrayOf(Float.NaN, 0f, 0f, 0f))
        val resultNan = refineQuery(base, nanPositives, emptyList())
        assertArrayEquals(base, resultNan, 1e-6f)
    }

    @Test
    fun refineQuery_mismatchedSizeVectorsIgnored() {
        val base = floatArrayOf(1f, 0f, 0f, 0f) // size 4
        val posMismatched = listOf(floatArrayOf(0f, 1f)) // size 2
        val negMismatched = listOf(floatArrayOf(0f, 1f, 0f, 0f, 0f)) // size 5

        // Only mismatched vectors provided -> falls back to base unchanged
        val resultOnlyMismatched = refineQuery(base, posMismatched, negMismatched)
        assertSame(base, resultOnlyMismatched)

        // Valid positive with mismatched positive -> mismatched ignored
        val validPos = floatArrayOf(0f, 1f, 0f, 0f)
        val mixedPos = listOf(validPos, floatArrayOf(0f, 1f))
        val resultMixed = refineQuery(base, mixedPos, emptyList())
        val resultValidOnly = refineQuery(base, listOf(validPos), emptyList())
        assertArrayEquals(resultValidOnly, resultMixed, 1e-6f)
    }

    @Test
    fun refineQuery_viaCollectionMatcherObject_delegatesCorrectly() {
        val base = floatArrayOf(1f, 0f, 0f, 0f)
        val pos = listOf(floatArrayOf(0f, 1f, 0f, 0f))
        val resultObject = CollectionMatcher.refineQuery(base, pos, emptyList())
        val resultTopLevel = refineQuery(base, pos, emptyList())
        assertArrayEquals(resultTopLevel, resultObject, 1e-6f)
    }
}

class ShouldRefreshAfterSessionTest {
    @org.junit.Test
    fun refreshesOnlyWhenSomethingWasKeptOrRemoved() {
        org.junit.Assert.assertFalse(com.peexr.sift.ui.review.shouldRefreshAfterSession(0, 0))
        org.junit.Assert.assertTrue(com.peexr.sift.ui.review.shouldRefreshAfterSession(1, 0))
        org.junit.Assert.assertTrue(com.peexr.sift.ui.review.shouldRefreshAfterSession(0, 3))
        org.junit.Assert.assertTrue(com.peexr.sift.ui.review.shouldRefreshAfterSession(2, 2))
    }
}
