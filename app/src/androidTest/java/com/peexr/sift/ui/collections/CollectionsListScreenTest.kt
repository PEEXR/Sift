package com.peexr.sift.ui.collections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peexr.sift.ui.fakeCollectionSummary
import com.peexr.sift.ui.theme.SiftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionsListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyState_showsNoCollectionsYetAndCreateButton() {
        composeTestRule.setContent {
            SiftTheme {
                CollectionsListScreen(
                    state = CollectionsUiState(collections = emptyList()),
                    onOpenCollection = {},
                    onCreate = { _, _ -> },
                    onDismissStatus = {}
                )
            }
        }

        composeTestRule.onNodeWithText("No collections yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create a collection").assertIsDisplayed()
    }

    @Test
    fun twoCollections_rendersNamesDescriptionsInQuotesAndReviewBadges() {
        val col1 = fakeCollectionSummary(
            id = "c1",
            name = "Memes",
            description = "funny reaction images",
            reviewCount = 3
        )
        val col2 = fakeCollectionSummary(
            id = "c2",
            name = "Receipts",
            description = "expense slips and bills",
            reviewCount = 7
        )

        composeTestRule.setContent {
            SiftTheme {
                CollectionsListScreen(
                    state = CollectionsUiState(collections = listOf(col1, col2)),
                    onOpenCollection = {},
                    onCreate = { _, _ -> },
                    onDismissStatus = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Memes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Receipts").assertIsDisplayed()
        composeTestRule.onNodeWithText("\u201Cfunny reaction images\u201D").assertIsDisplayed()
        composeTestRule.onNodeWithText("\u201Cexpense slips and bills\u201D").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 to review").assertIsDisplayed()
        composeTestRule.onNodeWithText("7 to review").assertIsDisplayed()
    }

    @Test
    fun tappingCard_invokesOnOpenCollectionWithId() {
        val col1 = fakeCollectionSummary(id = "c1", name = "Memes")
        val col2 = fakeCollectionSummary(id = "c2", name = "Receipts")
        var openedId: String? = null

        composeTestRule.setContent {
            SiftTheme {
                CollectionsListScreen(
                    state = CollectionsUiState(collections = listOf(col1, col2)),
                    onOpenCollection = { openedId = it },
                    onCreate = { _, _ -> },
                    onDismissStatus = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Memes").performClick()
        assertEquals("c1", openedId)
    }

    @Test
    fun createSheetViaFab_validatesInputAndInvokesOnCreate() {
        var createdName: String? = null
        var createdDescription: String? = null

        composeTestRule.setContent {
            SiftTheme {
                CollectionsListScreen(
                    state = CollectionsUiState(collections = emptyList()),
                    onOpenCollection = {},
                    onCreate = { name, desc ->
                        createdName = name
                        createdDescription = desc
                    },
                    onDismissStatus = {}
                )
            }
        }

        // Tap the FAB to open the bottom sheet
        composeTestRule.onNodeWithText("New collection").performClick()
        composeTestRule.waitForIdle()

        // "Create" button is disabled while name is blank
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()

        // Type a name
        composeTestRule.onNodeWithText("Name").performTextInput("Travel")
        composeTestRule.waitForIdle()

        // "Create" is now enabled
        composeTestRule.onNodeWithText("Create").assertIsEnabled()

        // Type description
        composeTestRule.onNodeWithText("What should it fill with?")
            .performTextInput("boarding passes and tickets")
        composeTestRule.waitForIdle()

        // Tap "Create"
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Travel", createdName)
        assertEquals("boarding passes and tickets", createdDescription)
    }
}
