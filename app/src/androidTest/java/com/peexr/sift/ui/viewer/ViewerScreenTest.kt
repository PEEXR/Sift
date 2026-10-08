package com.peexr.sift.ui.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peexr.sift.ui.fakeCollectionSummary
import com.peexr.sift.ui.fakeScreenshot
import com.peexr.sift.ui.fakeScreenshotDetail
import com.peexr.sift.ui.fakeViewerCollectionRow
import com.peexr.sift.ui.theme.SiftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingCollections_opensSheetListingCollectionsWithCheckboxesThatInvokeToggle() {
        val row1 = fakeViewerCollectionRow(
            summary = fakeCollectionSummary(id = "c1", name = "Memes"),
            isMember = true
        )
        val row2 = fakeViewerCollectionRow(
            summary = fakeCollectionSummary(id = "c2", name = "Receipts"),
            isMember = false
        )
        val state = ViewerUiState(
            detail = fakeScreenshotDetail(),
            collections = listOf(row1, row2)
        )
        var toggledId: String? = null
        var toggledState: Boolean? = null

        composeTestRule.setContent {
            SiftTheme {
                ViewerScreen(
                    state = state,
                    onClose = {},
                    onToggleCollection = { id, member ->
                        toggledId = id
                        toggledState = member
                    },
                    onCreateCollection = { _, _ -> },
                    onDelete = {}
                )
            }
        }

        // Tap Collections button in bottom bar
        composeTestRule.onNodeWithText("Collections").performClick()
        composeTestRule.waitForIdle()

        // Sheet title and collection names are displayed
        composeTestRule.onNodeWithText("Add to collections").assertIsDisplayed()
        composeTestRule.onNodeWithText("Memes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Receipts").assertIsDisplayed()

        // Tap Receipts to toggle it on
        composeTestRule.onNodeWithText("Receipts").performClick()
        composeTestRule.waitForIdle()
        assertEquals("c2", toggledId)
        assertEquals(true, toggledState)

        // Tap Memes to toggle it off
        composeTestRule.onNodeWithText("Memes").performClick()
        composeTestRule.waitForIdle()
        assertEquals("c1", toggledId)
        assertEquals(false, toggledState)
    }

    @Test
    fun tappingText_whenNotProcessed_showsNotIndexedYet() {
        val detail = fakeScreenshotDetail(
            item = fakeScreenshot(isProcessed = false, ocrText = "")
        )
        val state = ViewerUiState(detail = detail)

        composeTestRule.setContent {
            SiftTheme {
                ViewerScreen(
                    state = state,
                    onClose = {},
                    onToggleCollection = { _, _ -> },
                    onCreateCollection = { _, _ -> },
                    onDelete = {}
                )
            }
        }

        // Tap Text button
        composeTestRule.onNodeWithText("Text").performClick()
        composeTestRule.waitForIdle()

        // Text sheet title and unindexed message are displayed
        composeTestRule.onNodeWithText("Text in this screenshot").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not indexed yet — run indexing from the Library to read this screenshot.").assertIsDisplayed()
    }

    @Test
    fun tappingText_whenProcessed_showsExtractedTextAndCopyAllButton() {
        val detail = fakeScreenshotDetail(
            item = fakeScreenshot(isProcessed = true, ocrText = "WiFi Password: SecretPassword123")
        )
        val state = ViewerUiState(detail = detail)

        composeTestRule.setContent {
            SiftTheme {
                ViewerScreen(
                    state = state,
                    onClose = {},
                    onToggleCollection = { _, _ -> },
                    onCreateCollection = { _, _ -> },
                    onDelete = {}
                )
            }
        }

        // Tap Text button
        composeTestRule.onNodeWithText("Text").performClick()
        composeTestRule.waitForIdle()

        // Text and Copy all button are displayed
        composeTestRule.onNodeWithText("WiFi Password: SecretPassword123").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy all").assertIsDisplayed()
    }

    @Test
    fun tappingDelete_showsConfirmationDialog() {
        val state = ViewerUiState(detail = fakeScreenshotDetail())

        composeTestRule.setContent {
            SiftTheme {
                ViewerScreen(
                    state = state,
                    onClose = {},
                    onToggleCollection = { _, _ -> },
                    onCreateCollection = { _, _ -> },
                    onDelete = {}
                )
            }
        }

        // Tap Delete in bottom bar
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.waitForIdle()

        // Confirmation dialog is displayed
        composeTestRule.onNodeWithText("Delete this screenshot?").assertIsDisplayed()
        composeTestRule.onNodeWithText("It is removed from your phone.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
