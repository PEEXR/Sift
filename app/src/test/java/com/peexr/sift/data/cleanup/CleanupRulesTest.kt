package com.peexr.sift.data.cleanup

import com.peexr.sift.data.search.EMBEDDING_DIM
import com.peexr.sift.data.search.l2Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupRulesTest {

    // --- isOlderThan boundaries ---

    @Test
    fun isOlderThan_nullDays_alwaysReturnsTrue() {
        val now = 1_000_000L
        assertTrue(isOlderThan(dateAddedSeconds = now - 100, nowSeconds = now, days = null))
        assertTrue(isOlderThan(dateAddedSeconds = now + 100, nowSeconds = now, days = null))
    }

    @Test
    fun isOlderThan_strictlyOlderThanDaysBoundary() {
        val now = 10_000_000L
        val days = 30
        val exactBoundary = now - (days * 86400L)

        // Exactly equal -> strictly older is false
        assertFalse(isOlderThan(dateAddedSeconds = exactBoundary, nowSeconds = now, days = days))

        // 1 second older than boundary -> true
        assertTrue(isOlderThan(dateAddedSeconds = exactBoundary - 1L, nowSeconds = now, days = days))

        // 1 second newer than boundary -> false
        assertFalse(isOlderThan(dateAddedSeconds = exactBoundary + 1L, nowSeconds = now, days = days))

        // Today / now -> false
        assertFalse(isOlderThan(dateAddedSeconds = now, nowSeconds = now, days = days))
    }

    // --- looksLikeCode ---

    @Test
    fun looksLikeCode_positiveOtpMatches() {
        assertEquals(CodeKind.OTP, looksLikeCode("Your OTP is 482913"))
        assertEquals(CodeKind.OTP, looksLikeCode("482913 is your verification code"))
        assertEquals(CodeKind.OTP, looksLikeCode("Security code: 1234. Do not share."))
        assertEquals(CodeKind.OTP, looksLikeCode("Enter passcode 987654 to continue"))
        assertEquals(CodeKind.OTP, looksLikeCode("Your one-time login code is 83921"))
    }

    @Test
    fun looksLikeCode_positiveExpiringMatches() {
        assertEquals(CodeKind.EXPIRING, looksLikeCode("Code expires in 10 minutes"))
        assertEquals(CodeKind.EXPIRING, looksLikeCode("Offer valid until midnight"))
        assertEquals(CodeKind.EXPIRING, looksLikeCode("This link is expiring soon"))
        assertEquals(CodeKind.EXPIRING, looksLikeCode("Ticket expires on Sunday"))
        assertEquals(CodeKind.EXPIRING, looksLikeCode("Discount code: use within 24 hours"))
    }

    @Test
    fun looksLikeCode_otpTakesPriorityOverExpiring() {
        // Both OTP keyword and expiring phrase present
        assertEquals(CodeKind.OTP, looksLikeCode("Your OTP is 482913, expires in 10 minutes"))
        assertEquals(CodeKind.OTP, looksLikeCode("Your verification code is 123456, valid for 5 minutes"))
    }

    @Test
    fun looksLikeCode_negativeCases() {
        // standalone digits but no OTP or expiring keyword
        assertNull(looksLikeCode("Total ₹482913 paid"))

        // no standalone 4-8 digit number and no expiring phrase
        assertNull(looksLikeCode("Meeting at 4:30"))

        // blank strings
        assertNull(looksLikeCode(""))
        assertNull(looksLikeCode("   \n\t  "))

        // 9-digit number with "otp" (does not match 4-8 digit standalone)
        assertNull(looksLikeCode("Your otp is 123456789"))
    }

    // --- findBursts ---

    private fun makeUnitVector(dim: Int, activeIndex: Int): FloatArray {
        val array = FloatArray(dim)
        array[activeIndex] = 1.0f
        return array.l2Normalize()
    }

    private fun makeSlightlyPerturbedVector(dim: Int, baseIndex: Int, noise: Float): FloatArray {
        val array = FloatArray(dim)
        array[baseIndex] = 1.0f
        array[(baseIndex + 1) % dim] = noise
        return array.l2Normalize()
    }

    @Test
    fun findBursts_threeNearIdenticalShotsWithinTwoMinutes_returnsOneBurstOfThree() {
        val baseVec = makeUnitVector(EMBEDDING_DIM, 0)
        // High similarity (~0.995 cosine)
        val vec1 = makeSlightlyPerturbedVector(EMBEDDING_DIM, 0, 0.05f)
        val vec2 = makeSlightlyPerturbedVector(EMBEDDING_DIM, 0, 0.08f)

        val items = listOf(
            EmbeddedRef(id = 1L, dateAdded = 1000L, embedding = baseVec),
            EmbeddedRef(id = 2L, dateAdded = 1060L, embedding = vec1),
            EmbeddedRef(id = 3L, dateAdded = 1120L, embedding = vec2)
        )

        val bursts = findBursts(items, windowSeconds = 600, threshold = 0.92f)
        assertEquals(1, bursts.size)
        assertEquals(listOf(1L, 2L, 3L), bursts[0])
    }

    @Test
    fun findBursts_sameVectorsTwentyMinutesApart_returnsEmpty() {
        val vec = makeUnitVector(EMBEDDING_DIM, 0)
        // 20 minutes = 1200 seconds apart > 600s window
        val items = listOf(
            EmbeddedRef(id = 1L, dateAdded = 1000L, embedding = vec),
            EmbeddedRef(id = 2L, dateAdded = 2200L, embedding = vec)
        )

        val bursts = findBursts(items, windowSeconds = 600, threshold = 0.92f)
        assertTrue("Burst window exceeded should yield no bursts", bursts.isEmpty())
    }

    @Test
    fun findBursts_differentVectorsCloseInTime_returnsEmpty() {
        val vecA = makeUnitVector(EMBEDDING_DIM, 0)
        val vecB = makeUnitVector(EMBEDDING_DIM, 5) // Orthogonal: cosine = 0.0

        val items = listOf(
            EmbeddedRef(id = 1L, dateAdded = 1000L, embedding = vecA),
            EmbeddedRef(id = 2L, dateAdded = 1010L, embedding = vecB)
        )

        val bursts = findBursts(items, windowSeconds = 600, threshold = 0.92f)
        assertTrue("Dissimilar vectors should yield no bursts", bursts.isEmpty())
    }

    @Test
    fun findBursts_unsortedInput_sortsAndReturnsBurstsInOrder() {
        val baseVec = makeUnitVector(EMBEDDING_DIM, 2)
        val vec1 = makeSlightlyPerturbedVector(EMBEDDING_DIM, 2, 0.04f)
        val vec2 = makeSlightlyPerturbedVector(EMBEDDING_DIM, 2, 0.06f)

        // Pass in scrambled order
        val items = listOf(
            EmbeddedRef(id = 3L, dateAdded = 3000L, embedding = vec2),
            EmbeddedRef(id = 1L, dateAdded = 2800L, embedding = baseVec),
            EmbeddedRef(id = 2L, dateAdded = 2900L, embedding = vec1)
        )

        val bursts = findBursts(items, windowSeconds = 600, threshold = 0.92f)
        assertEquals(1, bursts.size)
        assertEquals(listOf(1L, 2L, 3L), bursts[0])
    }

    // --- burstDeletionCandidates ---

    @Test
    fun burstDeletionCandidates_keepsNewestOfEachBurst() {
        // Burst 1: [10L, 20L, 30L] -> newest is 30L, delete candidates: 10L, 20L
        // Burst 2: [40L, 50L] -> newest is 50L, delete candidate: 40L
        val bursts = listOf(
            listOf(10L, 20L, 30L),
            listOf(40L, 50L)
        )

        val candidates = burstDeletionCandidates(bursts)
        assertEquals(listOf(10L, 20L, 40L), candidates)
    }

    @Test
    fun burstDeletionCandidates_emptyList_returnsEmpty() {
        assertTrue(burstDeletionCandidates(emptyList()).isEmpty())
    }
}
