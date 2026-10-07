package com.peexr.sift.ui.review

import com.peexr.sift.data.model.ScreenshotItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSessionTest {

    private fun createScreenshot(id: Long): ScreenshotItem {
        return ScreenshotItem(
            id = id,
            uri = "content://media/external/images/media/$id",
            displayName = "screenshot_$id.png",
            dateAdded = 1700000000L + id,
            sizeBytes = 2_000_000L
        )
    }

    // --- splitSearchResults tests ---

    @Test
    fun splitSearchResults_withEmptyInput_returnsEmptyStack() {
        val stack = splitSearchResults(emptyList())
        assertEquals(0, stack.cards.size)
        assertEquals(0, stack.strongCount)
        assertEquals(0, stack.unsureCount)
    }

    @Test
    fun splitSearchResults_withSingleItem_marksAsStrongMinOne() {
        val items = listOf(createScreenshot(1L))
        val stack = splitSearchResults(items)

        assertEquals(1, stack.cards.size)
        assertEquals(1, stack.strongCount)
        assertEquals(0, stack.unsureCount)
        assertEquals(1L, stack.cards[0].item.id)
        assertEquals("Strong match", stack.cards[0].confidenceLabel)
        assertTrue(stack.cards[0].isStrong)
    }

    @Test
    fun splitSearchResults_withTwoItems_splitsOneStrongOneUnsure() {
        // [1L, 2L] -> best 40% of 2 is ceil(0.8) = 1 strong (1L), 1 unsure (2L)
        val items = listOf(createScreenshot(1L), createScreenshot(2L))
        val stack = splitSearchResults(items)

        assertEquals(2, stack.cards.size)
        assertEquals(1, stack.strongCount)
        assertEquals(1, stack.unsureCount)

        // Stack order: unsure weakest-first [2L], then strong weakest-first [1L]
        assertEquals(2L, stack.cards[0].item.id)
        assertEquals("Weak match", stack.cards[0].confidenceLabel)
        assertFalse(stack.cards[0].isStrong)

        assertEquals(1L, stack.cards[1].item.id)
        assertEquals("Strong match", stack.cards[1].confidenceLabel)
        assertTrue(stack.cards[1].isStrong)
    }

    @Test
    fun splitSearchResults_withFiveItems_calculatesCeil40PercentStrongAndOrdersWeakestFirst() {
        // 5 items: ceil(5 * 0.40) = 2 strong matches (1L, 2L)
        // 3 unsure matches (3L, 4L, 5L)
        val items = (1L..5L).map { createScreenshot(it) }
        val stack = splitSearchResults(items)

        assertEquals(5, stack.cards.size)
        assertEquals(2, stack.strongCount)
        assertEquals(3, stack.unsureCount)

        // Unsure weakest-first: 5L, 4L, 3L
        // Strong weakest-first: 2L, 1L
        val expectedOrder = listOf(5L, 4L, 3L, 2L, 1L)
        assertEquals(expectedOrder, stack.cards.map { it.item.id })

        assertEquals("Weak match", stack.cards[0].confidenceLabel)
        assertEquals("Weak match", stack.cards[1].confidenceLabel)
        assertEquals("Weak match", stack.cards[2].confidenceLabel)
        assertEquals("Strong match", stack.cards[3].confidenceLabel)
        assertEquals("Strong match", stack.cards[4].confidenceLabel)
    }

    @Test
    fun splitSearchResults_withSevenItems_calculatesCeil40PercentStrong() {
        // 7 items: ceil(7 * 0.40) = ceil(2.8) = 3 strong (1L, 2L, 3L), 4 unsure (4L, 5L, 6L, 7L)
        val items = (1L..7L).map { createScreenshot(it) }
        val stack = splitSearchResults(items)

        assertEquals(7, stack.cards.size)
        assertEquals(3, stack.strongCount)
        assertEquals(4, stack.unsureCount)

        // Unsure weakest-first: 7L, 6L, 5L, 4L
        // Strong weakest-first: 3L, 2L, 1L
        val expectedOrder = listOf(7L, 6L, 5L, 4L, 3L, 2L, 1L)
        assertEquals(expectedOrder, stack.cards.map { it.item.id })
    }

    // --- Collection mode tests ---

    @Test
    fun collectionMode_keepAndRemoveCountersAndUndo() {
        val cards = listOf(
            ReviewCard(createScreenshot(101L), "Unsure", originalScore = 1.6f),
            ReviewCard(createScreenshot(102L), "Unsure", originalScore = 1.8f)
        )

        var session = ReviewSession().apply(ReviewAction.StartCollection("col1", "Memes", cards))
        assertEquals(ReviewMode.Collection("col1", "Memes"), session.mode)
        assertEquals(0, session.index)
        assertEquals(0, session.kept)
        assertEquals(0, session.removed)
        assertFalse(session.canUndo)

        // Keep first card
        session = session.apply(ReviewAction.Keep)
        assertEquals(1, session.index)
        assertEquals(1, session.kept)
        assertEquals(0, session.removed)
        assertTrue(session.canUndo)
        assertEquals(ReviewPhase.Swiping, session.phase)

        // Undo Keep
        session = session.apply(ReviewAction.Undo)
        assertEquals(0, session.index)
        assertEquals(0, session.kept)
        assertEquals(0, session.removed)
        assertFalse(session.canUndo)

        // Remove first card
        session = session.apply(ReviewAction.RemoveOrMark)
        assertEquals(1, session.index)
        assertEquals(0, session.kept)
        assertEquals(1, session.removed)
        assertTrue(session.canUndo)

        // Undo Remove
        session = session.apply(ReviewAction.Undo)
        assertEquals(0, session.index)
        assertEquals(0, session.kept)
        assertEquals(0, session.removed)
        assertFalse(session.canUndo)
    }

    // --- Search mode mark/unmark/undo tests ---

    @Test
    fun searchMode_markUnmarkUndo() {
        val searchStack = SearchStack(
            cards = listOf(
                ReviewCard(createScreenshot(201L), "Weak match", isStrong = false),
                ReviewCard(createScreenshot(202L), "Strong match", isStrong = true)
            ),
            strongCount = 1,
            unsureCount = 1
        )

        var session = ReviewSession().apply(ReviewAction.StartSearch("test", searchStack))
        assertEquals(ReviewMode.Search("test"), session.mode)
        assertEquals(0, session.index)
        assertTrue(session.marked.isEmpty())
        assertFalse(session.canUndo)

        // Mark first card for deletion
        session = session.apply(ReviewAction.RemoveOrMark)
        assertEquals(1, session.index)
        assertEquals(setOf(201L), session.marked)
        assertTrue(session.canUndo)
        assertEquals(ReviewPhase.Swiping, session.phase)

        // Undo mark
        session = session.apply(ReviewAction.Undo)
        assertEquals(0, session.index)
        assertTrue(session.marked.isEmpty())
        assertFalse(session.canUndo)

        // Keep first card (does not mark)
        session = session.apply(ReviewAction.Keep)
        assertEquals(1, session.index)
        assertEquals(1, session.kept)
        assertTrue(session.marked.isEmpty())

        // Undo keep
        session = session.apply(ReviewAction.Undo)
        assertEquals(0, session.index)
        assertEquals(0, session.kept)
    }

    // --- markRemaining tests ---

    @Test
    fun searchMode_markRemaining() {
        val searchStack = SearchStack(
            cards = listOf(
                ReviewCard(createScreenshot(1L), "Weak match", isStrong = false),
                ReviewCard(createScreenshot(2L), "Weak match", isStrong = false),
                ReviewCard(createScreenshot(3L), "Strong match", isStrong = true),
                ReviewCard(createScreenshot(4L), "Strong match", isStrong = true)
            ),
            strongCount = 2,
            unsureCount = 2
        )

        var session = ReviewSession().apply(ReviewAction.StartSearch("query", searchStack))

        // Review unsure cards
        session = session.apply(ReviewAction.Keep)
        session = session.apply(ReviewAction.Keep)
        assertEquals(2, session.index)
        assertTrue(session.showMarkRemaining)

        // Mark remaining strong matches
        session = session.apply(ReviewAction.MarkRemaining)
        assertEquals(4, session.index)
        assertEquals(setOf(3L, 4L), session.marked)
        assertEquals(ReviewPhase.Summary, session.phase)
        assertTrue(session.canUndo)

        // Undo markRemaining
        session = session.apply(ReviewAction.Undo)
        assertEquals(2, session.index)
        assertTrue(session.marked.isEmpty())
        assertEquals(ReviewPhase.Swiping, session.phase)
    }

    // --- done and summary phase transitions ---

    @Test
    fun doneAndSummaryPhaseTransitions() {
        // 1. Collection mode Done phase transition
        val colCard = listOf(ReviewCard(createScreenshot(10L), "Unsure"))
        var colSession = ReviewSession().apply(ReviewAction.StartCollection("c1", "Receipts", colCard))
        assertEquals(ReviewPhase.Swiping, colSession.phase)

        colSession = colSession.apply(ReviewAction.Keep)
        assertEquals(1, colSession.index)
        assertEquals(ReviewPhase.Done, colSession.phase)

        // Undo from Done returns to Swiping
        colSession = colSession.apply(ReviewAction.Undo)
        assertEquals(0, colSession.index)
        assertEquals(ReviewPhase.Swiping, colSession.phase)

        // 2. Search mode Summary phase transition
        val searchStack = SearchStack(
            cards = listOf(ReviewCard(createScreenshot(20L), "Strong match", isStrong = true)),
            strongCount = 1,
            unsureCount = 0
        )
        var searchSession = ReviewSession().apply(ReviewAction.StartSearch("receipts", searchStack))
        assertEquals(ReviewPhase.Swiping, searchSession.phase)

        searchSession = searchSession.apply(ReviewAction.RemoveOrMark)
        assertEquals(1, searchSession.index)
        assertEquals(ReviewPhase.Summary, searchSession.phase)

        // Summary to Freed phase transition
        searchSession = searchSession.apply(ReviewAction.FilesDeleted(setOf(20L), 2_000_000L))
        assertEquals(ReviewPhase.Freed(2_000_000L), searchSession.phase)
        assertTrue(searchSession.marked.isEmpty())
    }

    // --- Undo is disabled after file delete ---

    @Test
    fun undoIsDisabledAfterFileDelete() {
        val cards = listOf(
            ReviewCard(createScreenshot(1L), "Unsure"),
            ReviewCard(createScreenshot(2L), "Unsure")
        )

        var session = ReviewSession().apply(ReviewAction.StartCollection("c1", "Memes", cards))

        // Keep card 1 -> undo is enabled
        session = session.apply(ReviewAction.Keep)
        assertEquals(1, session.kept)
        assertTrue(session.canUndo)

        // Delete card 2 directly from storage
        session = session.apply(ReviewAction.FileDeleted(screenshotId = 2L, sizeBytes = 2_000_000L))
        assertEquals(1, session.deleted)
        assertEquals(2, session.index)
        assertEquals(ReviewPhase.Done, session.phase)

        // File deletion clears history and disables undo
        assertFalse(session.canUndo)

        // Attempting to undo should do nothing
        val afterUndo = session.apply(ReviewAction.Undo)
        assertEquals(session, afterUndo)
        assertEquals(2, afterUndo.index)
        assertEquals(1, afterUndo.deleted)
        assertFalse(afterUndo.canUndo)
    }
}
