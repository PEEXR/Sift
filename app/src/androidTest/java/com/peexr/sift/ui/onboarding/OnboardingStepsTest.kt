package com.peexr.sift.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peexr.sift.data.embedding.ModelState
import com.peexr.sift.ui.theme.SiftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingStepsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun welcomeStep_rendersHeadline() {
        composeTestRule.setContent {
            SiftTheme {
                WelcomeStepContent(onGetStarted = {})
            }
        }

        composeTestRule.onNodeWithText("Find any screenshot").assertIsDisplayed()
        composeTestRule.onNodeWithText("Get started").assertIsDisplayed()
    }

    @Test
    fun permissionsStep_rendersHeadline_andContinueDisabledUntilPermissionGranted() {
        // When hasPhotosPermission = false, Continue is disabled
        composeTestRule.setContent {
            SiftTheme {
                PermissionsStepContent(
                    hasPhotosPermission = false,
                    isPhotosPermanentlyDenied = false,
                    hasNotificationPermission = false,
                    onRequestPhotos = {},
                    onOpenSettings = {},
                    onRequestNotifications = {},
                    onContinue = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Two quick permissions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").assertIsNotEnabled()

        // When hasPhotosPermission = true, Continue is enabled
        composeTestRule.setContent {
            SiftTheme {
                PermissionsStepContent(
                    hasPhotosPermission = true,
                    isPhotosPermanentlyDenied = false,
                    hasNotificationPermission = false,
                    onRequestPhotos = {},
                    onOpenSettings = {},
                    onRequestNotifications = {},
                    onContinue = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun modelStep_rendersHeadline_andShowsDownloadProgressReadyStates() {
        // 1. Download prompt state
        composeTestRule.setContent {
            SiftTheme {
                ModelStepContent(
                    modelState = ModelState.NotInstalled,
                    isInstalled = false,
                    onDownloadModel = {},
                    onNext = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Smart search model").assertIsDisplayed()
        composeTestRule.onNodeWithText("316 MB on-device AI").assertIsDisplayed()
        composeTestRule.onNodeWithText("Download 316 MB").assertIsDisplayed()

        // 2. Progress / Downloading state
        composeTestRule.setContent {
            SiftTheme {
                ModelStepContent(
                    modelState = ModelState.Downloading(bytesDone = 50L, bytesTotal = 100L, currentFile = "model"),
                    isInstalled = false,
                    onDownloadModel = {},
                    onNext = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Downloading… 50%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Skip for now").assertIsDisplayed()

        // 3. Ready state
        composeTestRule.setContent {
            SiftTheme {
                ModelStepContent(
                    modelState = ModelState.Ready,
                    isInstalled = true,
                    onDownloadModel = {},
                    onNext = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Model ready").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test
    fun readyStep_rendersHeadline() {
        composeTestRule.setContent {
            SiftTheme {
                ReadyStepContent(
                    onCreateCollection = {},
                    onMaybeLater = {}
                )
            }
        }

        composeTestRule.onNodeWithText("You're set").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create a collection").assertIsDisplayed()
        composeTestRule.onNodeWithText("Maybe later").assertIsDisplayed()
    }
}
