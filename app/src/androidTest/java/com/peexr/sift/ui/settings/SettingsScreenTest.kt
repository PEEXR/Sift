package com.peexr.sift.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peexr.sift.data.collections.AutoFillSensitivity
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
        composeTestRule.onNodeWithText("Collections").assertIsDisplayed()
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

        // Tap Balanced
        composeTestRule.onNodeWithText("Balanced").performScrollTo().performClick()
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

        // Dialog header
        composeTestRule.onNodeWithText("Open-source licenses").assertIsDisplayed()

        // Verify ONNX Runtime and Bricolage Grotesque are listed
        composeTestRule.onNodeWithText("ONNX Runtime (MIT)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bricolage Grotesque (SIL OFL 1.1)").assertIsDisplayed()
    }
}
