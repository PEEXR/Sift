package com.peexr.sift.ui.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peexr.sift.ui.fakeReviewCard
import com.peexr.sift.ui.fakeScreenshot
import com.peexr.sift.ui.theme.SiftTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun collectionMode_threeCards_keepAndRemoveAndProgressAndFileDelete() {
        val cards = listOf(
            fakeReviewCard(item = fakeScreenshot(id = 1L)),
            fakeReviewCard(item = fakeScreenshot(id = 2L)),
            fakeReviewCard(item = fakeScreenshot(id = 3L))
        )
        val state = ReviewUiState(
            mode = ReviewMode.Collection(id = "c1", name = "Memes"),
            stack = cards,
            index = 0,
            phase = ReviewPhase.Swiping
        )

        var keepCalled = false
        var removeCalled = false
        var deleteFileCalled = false

        composeTestRule.setContent {
            SiftTheme {
                ReviewScreen(
                    uiState = state,
                    onOpenCollection = {},
                    onSubmitSearch = {},
                    onClearSearch = {},
                    onKeep = { keepCalled = true },
                    onRemoveOrMark = { removeCalled = true },
                    onMarkRemaining = {},
                    onUndo = {},
                    onDeleteCurrentFile = { deleteFileCalled = true },
                    onRequestDeleteMarked = {},
                    onClearMarks = {},
                    onRestart = {},
                    onBack = {},
                    onOpenScreenshot = {},
                    onNavigateToCollections = {}
                )
            }
        }

        // Progress label "1 of 3"
        composeTestRule.onNodeWithText("1 of 3").assertIsDisplayed()

        // "Delete file" button is present in collection mode
        composeTestRule.onNodeWithText("Delete file").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete file").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { deleteFileCalled }
        assertTrue(deleteFileCalled)

        // Keep button via content description (button triggers fly-off animation before callback)
        composeTestRule.onNodeWithContentDescription("Keep").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Keep").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { keepCalled }
        assertTrue(keepCalled)

        // Remove button via content description ("Remove from collection")
        composeTestRule.onNodeWithContentDescription("Remove from collection").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Remove from collection").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { removeCalled }
        assertTrue(removeCalled)
    }

    @Test
    fun searchMode_deleteFileAbsent_trashAndKeepButtonsExist_andMarkRemainingAppearsWhenStrong() {
        val cards = listOf(
            fakeReviewCard(item = fakeScreenshot(id = 1L), isStrong = true),
            fakeReviewCard(item = fakeScreenshot(id = 2L), isStrong = true)
        )
        val stateWithMarkRemaining = ReviewUiState(
            mode = ReviewMode.Search(query = "receipts"),
            stack = cards,
            index = 0,
            showMarkRemaining = true,
            phase = ReviewPhase.Swiping
        )
        var uiState by mutableStateOf(stateWithMarkRemaining)
        var markRemainingCalled = false

        composeTestRule.setContent {
            SiftTheme {
                ReviewScreen(
                    uiState = uiState,
                    onOpenCollection = {},
                    onSubmitSearch = {},
                    onClearSearch = {},
                    onKeep = {},
                    onRemoveOrMark = {},
                    onMarkRemaining = { markRemainingCalled = true },
                    onUndo = {},
                    onDeleteCurrentFile = {},
                    onRequestDeleteMarked = {},
                    onClearMarks = {},
                    onRestart = {},
                    onBack = {},
                    onOpenScreenshot = {},
                    onNavigateToCollections = {}
                )
            }
        }

        // "Delete file" is absent in search mode
        composeTestRule.onNodeWithText("Delete file").assertDoesNotExist()

        // Trash / mark for deletion button exists
        composeTestRule.onNodeWithContentDescription("Mark for deletion").assertIsDisplayed()

        // Keep button exists
        composeTestRule.onNodeWithContentDescription("Keep").assertIsDisplayed()

        // "Mark remaining 2 strong matches" pill appears and invokes callback
        composeTestRule.onNodeWithText("Mark remaining 2 strong matches").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark remaining 2 strong matches").performClick()
        assertTrue(markRemainingCalled)

        // Now verify that when showMarkRemaining is false, the pill is absent
        uiState = uiState.copy(showMarkRemaining = false)
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasText("Mark remaining", substring = true)).assertDoesNotExist()
    }

    @Test
    fun summaryPhase_showsDeleteFilesSizeAndInvokesCallback() {
        val state = ReviewUiState(
            mode = ReviewMode.Search(query = "receipts"),
            marked = setOf(1L, 2L, 3L),
            markedTotalBytes = 1024L * 1024L * 5L, // 5.0 MB
            phase = ReviewPhase.Summary
        )
        var deleteMarkedCalled = false

        composeTestRule.setContent {
            SiftTheme {
                ReviewScreen(
                    uiState = state,
                    onOpenCollection = {},
                    onSubmitSearch = {},
                    onClearSearch = {},
                    onKeep = {},
                    onRemoveOrMark = {},
                    onMarkRemaining = {},
                    onUndo = {},
                    onDeleteCurrentFile = {},
                    onRequestDeleteMarked = { deleteMarkedCalled = true },
                    onClearMarks = {},
                    onRestart = {},
                    onBack = {},
                    onOpenScreenshot = {},
                    onNavigateToCollections = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Delete 3 files \u00B7 5.0 MB").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete 3 files \u00B7 5.0 MB").performClick()
        assertTrue(deleteMarkedCalled)
    }

    @Test
    fun swipeLeftOnTopCard_invokesRemoveOrMarkCallback() {
        val cards = listOf(
            fakeReviewCard(item = fakeScreenshot(id = 1L)),
            fakeReviewCard(item = fakeScreenshot(id = 2L))
        )
        val state = ReviewUiState(
            mode = ReviewMode.Collection(id = "c1", name = "Memes"),
            stack = cards,
            index = 0,
            phase = ReviewPhase.Swiping
        )
        var removeInvoked = false

        composeTestRule.setContent {
            SiftTheme {
                ReviewScreen(
                    uiState = state,
                    onOpenCollection = {},
                    onSubmitSearch = {},
                    onClearSearch = {},
                    onKeep = {},
                    onRemoveOrMark = { removeInvoked = true },
                    onMarkRemaining = {},
                    onUndo = {},
                    onDeleteCurrentFile = {},
                    onRequestDeleteMarked = {},
                    onClearMarks = {},
                    onRestart = {},
                    onBack = {},
                    onOpenScreenshot = {},
                    onNavigateToCollections = {}
                )
            }
        }

        // Perform swipe left on the top card
        composeTestRule.onNodeWithTag("swipe_top_card").performTouchInput {
            swipeLeft()
        }
        composeTestRule.waitForIdle()

        assertTrue(removeInvoked)
    }
}
