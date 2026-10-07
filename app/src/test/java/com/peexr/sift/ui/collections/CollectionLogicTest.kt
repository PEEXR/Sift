package com.peexr.sift.ui.collections

import androidx.compose.ui.graphics.Color
import com.peexr.sift.data.model.CollectionMemberItem
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.ui.theme.CollectionPalette
import com.peexr.sift.ui.theme.collectionColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionLogicTest {

    private fun createMember(id: Long, kept: Boolean): CollectionMemberItem {
        return CollectionMemberItem(
            item = ScreenshotItem(
                id = id,
                uri = "content://media/external/images/media/$id",
                displayName = "screenshot_$id.png",
                dateAdded = 1000L + id,
                sizeBytes = 2048L
            ),
            kept = kept
        )
    }

    @Test
    fun filterMembers_allReturnsAllItems() {
        val member1 = createMember(1L, kept = true)
        val member2 = createMember(2L, kept = false)
        val member3 = createMember(3L, kept = true)
        val list = listOf(member1, member2, member3)

        val result = filterMembers(list, MemberFilter.ALL)
        assertEquals(3, result.size)
        assertEquals(list, result)
    }

    @Test
    fun filterMembers_autoReturnsOnlyUnkeptItems() {
        val member1 = createMember(1L, kept = true)
        val member2 = createMember(2L, kept = false)
        val member3 = createMember(3L, kept = true)
        val member4 = createMember(4L, kept = false)
        val list = listOf(member1, member2, member3, member4)

        val result = filterMembers(list, MemberFilter.AUTO)
        assertEquals(listOf(member2, member4), result)
    }

    @Test
    fun filterMembers_keptReturnsOnlyKeptItems() {
        val member1 = createMember(1L, kept = true)
        val member2 = createMember(2L, kept = false)
        val member3 = createMember(3L, kept = true)
        val member4 = createMember(4L, kept = false)
        val list = listOf(member1, member2, member3, member4)

        val result = filterMembers(list, MemberFilter.KEPT)
        assertEquals(listOf(member1, member3), result)
    }

    @Test
    fun filterMembers_emptyListReturnsEmpty() {
        assertTrue(filterMembers(emptyList(), MemberFilter.ALL).isEmpty())
        assertTrue(filterMembers(emptyList(), MemberFilter.AUTO).isEmpty())
        assertTrue(filterMembers(emptyList(), MemberFilter.KEPT).isEmpty())
    }

    @Test
    fun collectionColors_exactMatchReturnsCorrectPalettePair() {
        assertEquals(CollectionPalette[0], collectionColors("#FFD8EC")) // pink
        assertEquals(CollectionPalette[1], collectionColors("#E5D8FF")) // lilac
        assertEquals(CollectionPalette[2], collectionColors("#CFE5FF")) // sky
        assertEquals(CollectionPalette[3], collectionColors("#E4F7B5")) // lime
        assertEquals(CollectionPalette[4], collectionColors("#FFDCC8")) // peach
        assertEquals(CollectionPalette[5], collectionColors("#C9F2DE")) // mint
    }

    @Test
    fun collectionColors_caseInsensitiveMatchReturnsCorrectPair() {
        assertEquals(CollectionPalette[0], collectionColors("#ffd8ec"))
        assertEquals(CollectionPalette[1], collectionColors("#e5d8ff"))
        assertEquals(CollectionPalette[2], collectionColors("#cfe5ff"))
        assertEquals(CollectionPalette[3], collectionColors("#e4f7b5"))
        assertEquals(CollectionPalette[4], collectionColors("#ffdcc8"))
        assertEquals(CollectionPalette[5], collectionColors("#c9f2de"))
    }

    @Test
    fun collectionColors_unknownHexFallsBackToFirstPair() {
        assertEquals(CollectionPalette.first(), collectionColors("#123456"))
        assertEquals(CollectionPalette.first(), collectionColors("#000000"))
        assertEquals(CollectionPalette.first(), collectionColors("non-existent"))
        assertEquals(CollectionPalette.first(), collectionColors(""))
    }
}
