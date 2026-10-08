package com.peexr.sift.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peexr.sift.data.collections.AutoFillSensitivity
import com.peexr.sift.data.embedding.IndexingQuality
import com.peexr.sift.ui.theme.SiftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sections_renderCorrectly() {
        val state = SettingsUiState()

        composeTestRule.setContent {
            SiftTheme {
                SettingsScreen(
                    uiState = state,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onStartModelDownload = {},
                    onRemoveModel = {},
                    onSetAutoIndexEnabled = {},
                    onIndexNow = {},
                    onReindexEverything = {},
                    onSetAutoFillSensitivity = {},
                    onExportCollections = {},
                    onImportCollections = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Smart search").assertIsDisplayed()
        composeTestRule.onNodeWithText("Indexing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Indexing quality").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Collections").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("About").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun togglingAutoIndexSwitch_invokesCallback() {
        var autoIndexEnabled: Boolean? = null
        val state = SettingsUiState(autoIndexNewScreenshots = true)

        composeTestRule.setContent {
            SiftTheme {
                SettingsScreen(
                    uiState = state,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onStartModelDownload = {},
                    onRemoveModel = {},
                    onSetAutoIndexEnabled = { autoIndexEnabled = it },
                    onIndexNow = {},
                    onReindexEverything = {},
                    onSetAutoFillSensitivity = {},
                    onExportCollections = {},
                    onImportCollections = {}
                )
            }
        }

        // Toggle switch
        composeTestRule.onNode(isToggleable()).performClick()
        assertEquals(false, autoIndexEnabled)
    }

    @Test
    fun sensitivityOptions_invokeCallbackWithCorrectValue() {
        var selectedSensitivity: AutoFillSensitivity? = null
        val state = SettingsUiState(autoFillSensitivity = AutoFillSensitivity.BALANCED)

        composeTestRule.setContent {
            SiftTheme {
                SettingsScreen(
                    uiState = state,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onStartModelDownload = {},
                    onRemoveModel = {},
                    onSetAutoIndexEnabled = {},
                    onIndexNow = {},
                    onReindexEverything = {},
                    onSetAutoFillSensitivity = { selectedSensitivity = it },
                    onExportCollections = {},
                    onImportCollections = {}
                )
            }
        }

        // Tap Strict
        composeTestRule.onNodeWithText("Strict").performScrollTo().performClick()
        assertEquals(AutoFillSensitivity.STRICT, selectedSensitivity)

        // Tap Loose
        composeTestRule.onNodeWithText("Loose").performScrollTo().performClick()
        assertEquals(AutoFillSensitivity.LOOSE, selectedSensitivity)

        // Tap Balanced (second "Balanced" node on screen is under Auto-fill sensitivity; first is Indexing quality)
        composeTestRule.onAllNodesWithText("Balanced")[1].performScrollTo().performClick()
        assertEquals(AutoFillSensitivity.BALANCED, selectedSensitivity)
    }

    @Test
    fun reindexEverything_showsConfirmationDialogBeforeInvoking() {
        var reindexCalled = false
        val state = SettingsUiState()

        composeTestRule.setContent {
            SiftTheme {
                SettingsScreen(
                    uiState = state,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onStartModelDownload = {},
                    onRemoveModel = {},
                    onSetAutoIndexEnabled = {},
                    onIndexNow = {},
                    onReindexEverything = { reindexCalled = true },
                    onSetAutoFillSensitivity = {},
                    onExportCollections = {},
                    onImportCollections = {}
                )
            }
        }

        // Tap "Re-index everything" button
        composeTestRule.onNodeWithText("Re-index everything").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Confirmation dialog is shown
        composeTestRule.onNodeWithText("Re-index everything?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()

        // Tap "Re-index" button in dialog
        composeTestRule.onNodeWithText("Re-index").performClick()
        composeTestRule.waitForIdle()

        assertTrue(reindexCalled)
    }

    @Test
    fun licensesDialog_listsOnnxRuntimeAndBricolageGrotesque() {
        val state = SettingsUiState()

        composeTestRule.setContent {
            SiftTheme {
                SettingsScreen(
                    uiState = state,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onStartModelDownload = {},
                    onRemoveModel = {},
                    onSetAutoIndexEnabled = {},
                    onIndexNow = {},
                    onReindexEverything = {},
                    onSetAutoFillSensitivity = {},
                    onExportCollections = {},
                    onImportCollections = {}
                )
            }
        }

        // Tap "Open-source licenses" button
        composeTestRule.onNodeWithText("Open-source licenses").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Dialog header: both the button and dialog title match "Open-source licenses"
        composeTestRule.onAllNodesWithText("Open-source licenses").assertCountEquals(2)

        // Verify ONNX Runtime and Bricolage Grotesque are listed
        composeTestRule.onNodeWithText("ONNX Runtime (MIT)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bricolage Grotesque (SIL OFL 1.1)").assertIsDisplayed()
    }

    @Test
    fun indexingQualityOptions_invokeCallbackWithCorrectValue() {
        var selectedQuality: IndexingQuality? = null
        val state = SettingsUiState(indexingQuality = IndexingQuality.AUTO)

        composeTestRule.setContent {
            SiftTheme {
                SettingsScreen(
                    uiState = state,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onStartModelDownload = {},
                    onRemoveModel = {},
                    onSetAutoIndexEnabled = {},
                    onIndexNow = {},
                    onReindexEverything = {},
                    onSetAutoFillSensitivity = {},
                    onExportCollections = {},
                    onImportCollections = {},
                    onSetIndexingQuality = { selectedQuality = it }
                )
            }
        }

        // Tap Fast
        composeTestRule.onNodeWithText("Fast").performScrollTo().performClick()
        assertEquals(IndexingQuality.FAST, selectedQuality)

        // Tap Balanced (first "Balanced" node on screen is under Indexing quality; second is Auto-fill sensitivity)
        composeTestRule.onAllNodesWithText("Balanced")[0].performScrollTo().performClick()
        assertEquals(IndexingQuality.BALANCED, selectedQuality)

        // Tap Best
        composeTestRule.onNodeWithText("Best").performScrollTo().performClick()
        assertEquals(IndexingQuality.BEST, selectedQuality)
    }
}
