package com.peexr.sift.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peexr.sift.ui.theme.SiftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingPillNavTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun threeDestinationsRender_selectedShowsLabel_tappingAnotherInvokesCallback() {
        val destinations = listOf(
            SiftDestination.Library,
            SiftDestination.Collections,
            SiftDestination.Review
        )
        var selectedDestination: SiftDestination? = null

        composeTestRule.setContent {
            SiftTheme {
                FloatingPillNav(
                    destinations = destinations,
                    currentDestination = SiftDestination.Collections,
                    onDestinationSelected = { selectedDestination = it }
                )
            }
        }

        // Selected destination (Collections) displays its text label
        composeTestRule.onNodeWithText("Collections").assertIsDisplayed()

        // Unselected destinations render with content descriptions
        composeTestRule.onNodeWithContentDescription("Library").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Review").assertIsDisplayed()

        // Text labels for unselected destinations should not be present
        composeTestRule.onNodeWithText("Library").assertDoesNotExist()
        composeTestRule.onNodeWithText("Review").assertDoesNotExist()

        // Tap on "Review"
        composeTestRule.onNodeWithContentDescription("Review").performClick()
        assertEquals(SiftDestination.Review, selectedDestination)
    }

    @Test
    fun changingSelectedDestination_updatesDisplayedLabel() {
        val destinations = listOf(
            SiftDestination.Library,
            SiftDestination.Collections,
            SiftDestination.Review
        )

        composeTestRule.setContent {
            SiftTheme {
                FloatingPillNav(
                    destinations = destinations,
                    currentDestination = SiftDestination.Library,
                    onDestinationSelected = {}
                )
            }
        }

        // Library is now selected, so its text label is displayed
        composeTestRule.onNodeWithText("Library").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Collections").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Review").assertIsDisplayed()
    }
}
