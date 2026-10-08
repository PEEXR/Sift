package com.peexr.sift.ui.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peexr.sift.data.cleanup.AgeFilter
import com.peexr.sift.ui.fakeCollectionSummary
import com.peexr.sift.ui.theme.SiftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewPickerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun collectionsList_rendersNamesAndReviewCounts_andInvokesCallback() {
        val col1 = fakeCollectionSummary(id = "c1", name = "Memes", reviewCount = 6)
        val col2 = fakeCollectionSummary(id = "c2", name = "Receipts", reviewCount = 3)
        var selectedCollectionId: String? = null

        composeTestRule.setContent {
            SiftTheme {
                ReviewPicker(
                    collections = listOf(col1, col2),
                    onSelectCollection = { selectedCollectionId = it },
                    onSelectSuggestion = {},
                    onNavigateToCollections = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Memes").assertIsDisplayed()
        composeTestRule.onNodeWithText("6 to review").assertIsDisplayed()
        composeTestRule.onNodeWithText("Receipts").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 to review").assertIsDisplayed()

        // Tap on Memes card
        composeTestRule.onNodeWithText("Memes").performClick()
        assertEquals("c1", selectedCollectionId)
    }

    @Test
    fun ageFilterChips_areSelectableAndInvokeCallback() {
        var selectedAgeFilter: AgeFilter? = null

        composeTestRule.setContent {
            SiftTheme {
                ReviewPicker(
                    collections = listOf(fakeCollectionSummary()),
                    ageFilter = AgeFilter.ANY,
                    onAgeFilterChange = { selectedAgeFilter = it },
                    onSelectCollection = {},
                    onSelectSuggestion = {},
                    onNavigateToCollections = {}
                )
            }
        }

        // Tap "Older than 1 month"
        composeTestRule.onNodeWithText("Older than 1 month").performClick()
        assertEquals(AgeFilter.MONTH, selectedAgeFilter)

        // Tap "Older than 3 months"
        composeTestRule.onNodeWithText("Older than 3 months").performClick()
        assertEquals(AgeFilter.QUARTER, selectedAgeFilter)
    }

    @Test
    fun quickCleanupCards_invokeRespectiveCallbacks() {
        var triggeredPreset: CleanupPreset? = null

        composeTestRule.setContent {
            SiftTheme {
                ReviewPicker(
                    collections = listOf(fakeCollectionSummary()),
                    onStartCleanup = { triggeredPreset = it },
                    onSelectCollection = {},
                    onSelectSuggestion = {},
                    onNavigateToCollections = {}
                )
            }
        }

        // Tap "Codes & expiring"
        composeTestRule.onNodeWithText("Codes & expiring").performClick()
        assertEquals(CleanupPreset.CODES, triggeredPreset)

        // Tap "Near-duplicates"
        composeTestRule.onNodeWithText("Near-duplicates").performClick()
        assertEquals(CleanupPreset.DUPLICATES, triggeredPreset)
    }

    @Test
    fun emptyCollections_showsCallToActionAndNavigates() {
        var navigatedToCollections = false

        composeTestRule.setContent {
            SiftTheme {
                ReviewPicker(
                    collections = emptyList(),
                    onSelectCollection = {},
                    onSelectSuggestion = {},
                    onNavigateToCollections = { navigatedToCollections = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Create a collection first, then review what Sift put in it").assertIsDisplayed()
        composeTestRule.onNodeWithText("Go to Collections").assertIsDisplayed()

        composeTestRule.onNodeWithText("Go to Collections").performClick()
        assertTrue(navigatedToCollections)
    }
}
