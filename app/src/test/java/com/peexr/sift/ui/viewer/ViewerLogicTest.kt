package com.peexr.sift.ui.viewer

import com.peexr.sift.data.local.MembershipSource
import com.peexr.sift.data.model.CollectionSummary
import com.peexr.sift.ui.collections.nextCollectionColorHex
import com.peexr.sift.ui.theme.CollectionPaletteHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerLogicTest {

    private fun createSummary(id: String, name: String): CollectionSummary {
        return CollectionSummary(
            id = id,
            name = name,
            description = "desc for $name",
            colorHex = "#FFD8EC",
            memberCount = 1,
            reviewCount = 0,
            totalBytes = 1000L,
            previewUris = emptyList()
        )
    }

    @Test
    fun buildViewerCollections_membersFirstThenAlphabetical() {
        val summaries = listOf(
            createSummary("c1", "Zebra"),
            createSummary("c2", "Apple"),
            createSummary("c3", "Mango"),
            createSummary("c4", "Banana")
        )

        // Zebra and Banana are members; Apple and Mango are not
        val memberships = mapOf(
            "c1" to MembershipSource.MANUAL,
            "c4" to MembershipSource.AUTO
        )

        val result = buildViewerCollections(summaries, memberships)

        assertEquals(4, result.size)
        // Members first: Banana, Zebra
        assertTrue(result[0].isMember)
        assertEquals("Banana", result[0].summary.name)

        assertTrue(result[1].isMember)
        assertEquals("Zebra", result[1].summary.name)

        // Non-members next: Apple, Mango
        assertFalse(result[2].isMember)
        assertEquals("Apple", result[2].summary.name)

        assertFalse(result[3].isMember)
        assertEquals("Mango", result[3].summary.name)
    }

    @Test
    fun buildViewerCollections_membershipSourceEvaluation() {
        val summaries = listOf(
            createSummary("c_auto", "AutoCol"),
            createSummary("c_manual", "ManualCol"),
            createSummary("c_excluded", "ExcludedCol"),
            createSummary("c_absent", "AbsentCol")
        )

        val memberships = mapOf(
            "c_auto" to MembershipSource.AUTO,
            "c_manual" to MembershipSource.MANUAL,
            "c_excluded" to MembershipSource.EXCLUDED
            // c_absent is omitted from map
        )

        val result = buildViewerCollections(summaries, memberships)
        val resultMap = result.associate { it.summary.id to it.isMember }

        assertTrue("AUTO source must be member", resultMap["c_auto"] == true)
        assertTrue("MANUAL source must be member", resultMap["c_manual"] == true)
        assertFalse("EXCLUDED source must not be member", resultMap["c_excluded"] == true)
        assertFalse("Absent source must not be member", resultMap["c_absent"] == true)
    }

    @Test
    fun nextCollectionColorHex_wrapsAfter6AndEqualsPaletteModulo() {
        for (i in 0 until 18) {
            val expected = CollectionPaletteHex[i % 6]
            val actual = nextCollectionColorHex(i)
            assertEquals("Mismatch for count $i", expected, actual)
        }
    }
}
