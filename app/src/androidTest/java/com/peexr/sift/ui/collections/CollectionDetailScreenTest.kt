package com.peexr.sift.ui.collections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peexr.sift.ui.fakeCollectionSummary
import com.peexr.sift.ui.theme.SiftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun titleAndFillsWithChip_renderCorrectly() {
        val collection = fakeCollectionSummary(
            id = "c1",
            name = "Memes",
            description = "funny memes and reaction images"
        )
        val state = CollectionDetailUiState(
            collection = collection,
            allCount = 10,
            autoCount = 7,
            keptCount = 3
        )

        composeTestRule.setContent {
            SiftTheme {
                CollectionDetailScreen(
                    uiState = state,
                    onBack = {},
                    onReview = {},
                    onOpenScreenshot = {},
                    onFilterSelected = {},
                    onUpdateDescription = {},
                    onDelete = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Memes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fills with \u201Cfunny memes and reaction images\u201D").assertIsDisplayed()
    }

    @Test
    fun filterButtons_showCountsAndInvokeFilterCallback() {
        val collection = fakeCollectionSummary(id = "c1", name = "Memes")
        val state = CollectionDetailUiState(
            collection = collection,
            filter = MemberFilter.ALL,
            allCount = 15,
            autoCount = 10,
            keptCount = 5
        )
        var selectedFilter: MemberFilter? = null

        composeTestRule.setContent {
            SiftTheme {
                CollectionDetailScreen(
                    uiState = state,
                    onBack = {},
                    onReview = {},
                    onOpenScreenshot = {},
                    onFilterSelected = { selectedFilter = it },
                    onUpdateDescription = {},
                    onDelete = {}
                )
            }
        }

        composeTestRule.onNodeWithText("All 15").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto 10").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kept 5").assertIsDisplayed()

        // Tap Auto
        composeTestRule.onNodeWithText("Auto 10").performClick()
        assertEquals(MemberFilter.AUTO, selectedFilter)

        // Tap Kept
        composeTestRule.onNodeWithText("Kept 5").performClick()
        assertEquals(MemberFilter.KEPT, selectedFilter)
    }

    @Test
    fun reviewUnsureButton_visibleOnlyWhenReviewCountGreaterThanZero_andInvokesCallback() {
        val collectionWithReviews = fakeCollectionSummary(id = "c1", name = "Memes", reviewCount = 4)
        val stateWithReviews = CollectionDetailUiState(
            collection = collectionWithReviews,
            allCount = 10,
            autoCount = 4,
            keptCount = 6
        )
        var state by mutableStateOf(stateWithReviews)
        var reviewInvoked = false

        composeTestRule.setContent {
            SiftTheme {
                CollectionDetailScreen(
                    uiState = state,
                    onBack = {},
                    onReview = { reviewInvoked = true },
                    onOpenScreenshot = {},
                    onFilterSelected = {},
                    onUpdateDescription = {},
                    onDelete = {}
                )
            }
        }

        // Button is displayed
        composeTestRule.onNodeWithText("Review 4 unsure").assertIsDisplayed()
        composeTestRule.onNodeWithText("Review 4 unsure").performClick()
        assertTrue(reviewInvoked)

        // Now test when reviewCount == 0
        val collectionNoReviews = fakeCollectionSummary(id = "c2", name = "Receipts", reviewCount = 0)
        state = CollectionDetailUiState(
            collection = collectionNoReviews,
            allCount = 10,
            autoCount = 0,
            keptCount = 10
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Review 0 unsure").assertDoesNotExist()
    }

    @Test
    fun overflowMenu_showsEditDescriptionAndDelete_andDeleteShowsConfirmationDialog() {
        val collection = fakeCollectionSummary(id = "c1", name = "Memes")
        val state = CollectionDetailUiState(collection = collection)
        var deleteInvoked = false

        composeTestRule.setContent {
            SiftTheme {
                CollectionDetailScreen(
                    uiState = state,
                    onBack = {},
                    onReview = {},
                    onOpenScreenshot = {},
                    onFilterSelected = {},
                    onUpdateDescription = {},
                    onDelete = { deleteInvoked = true }
                )
            }
        }

        // Tap overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()

        // Menu items visible
        composeTestRule.onNodeWithText("Edit description").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete collection").assertIsDisplayed()

        // Tap Delete collection
        composeTestRule.onNodeWithText("Delete collection").performClick()
        composeTestRule.waitForIdle()

        // Confirmation dialog visible
        composeTestRule.onNodeWithText("Delete collection?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Screenshots stay on your phone. Only the collection is removed.").assertIsDisplayed()

        // Confirm deletion
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.waitForIdle()

        assertTrue(deleteInvoked)
    }
}
