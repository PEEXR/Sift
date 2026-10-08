package com.peexr.sift

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.peexr.sift.ui.collections.CollectionsViewModel
import com.peexr.sift.ui.library.LibraryViewModel
import com.peexr.sift.ui.navigation.SiftMainApp
import com.peexr.sift.ui.onboarding.OnboardingScreen
import com.peexr.sift.ui.onboarding.shouldShowOnboarding
import com.peexr.sift.ui.review.ReviewViewModel
import com.peexr.sift.ui.theme.SiftTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.any { it.value }
        if (granted) {
            libraryViewModel.syncScreenshots()
        }
    }

    private val libraryViewModel: LibraryViewModel by viewModels {
        LibraryViewModel.Factory(
            repository = (application as SiftApplication).repository,
            app = application as SiftApplication
        )
    }

    private val collectionsViewModel: CollectionsViewModel by viewModels {
        CollectionsViewModel.Factory((application as SiftApplication).repository)
    }

    private val reviewViewModel: ReviewViewModel by viewModels {
        val app = application as SiftApplication
        ReviewViewModel.Factory(
            repository = app.repository,
            appScope = app.appScope
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighestRefreshRate()

        val app = application as SiftApplication
        val hasMedia = hasMediaPermission()
        val modelInstalled = app.modelStore.isInstalled()
        val showOnboarding = shouldShowOnboarding(
            onboardingDone = app.settingsManager.onboardingDone,
            hasMediaPermission = hasMedia,
            modelInstalled = modelInstalled
        )

        setContent {
            var isOnboarding by remember { mutableStateOf(showOnboarding) }
            var startOnCollectionsWithCreateSheet by remember { mutableStateOf(false) }

            SiftTheme {
                if (isOnboarding) {
                    OnboardingScreen(
                        app = app,
                        onPhotosGranted = { libraryViewModel.syncScreenshots() },
                        onFinish = { openCollectionsCreateSheet ->
                            app.settingsManager.onboardingDone = true
                            startOnCollectionsWithCreateSheet = openCollectionsCreateSheet
                            isOnboarding = false
                            libraryViewModel.autoIndexIfNeeded()
                        }
                    )
                } else {
                    SiftMainApp(
                        libraryViewModel = libraryViewModel,
                        reviewViewModel = reviewViewModel,
                        repository = app.repository,
                        collectionsViewModel = collectionsViewModel,
                        startOnCollectionsWithCreateSheet = startOnCollectionsWithCreateSheet
                    )
                }
            }
        }

        if (!showOnboarding) {
            if (hasMedia && modelInstalled) {
                app.settingsManager.onboardingDone = true
            }
            checkAndRequestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        libraryViewModel.autoIndexIfNeeded()
    }

    /**
     * Some OEM "smart refresh" modes keep unknown apps at 60 Hz. Ask for the panel's fastest mode at the
     * current resolution; the system may still lower it (battery saver, thermal), which is fine.
     */
    private fun requestHighestRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null
        val current = display?.mode ?: return
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }

    private fun hasMediaPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            libraryViewModel.syncScreenshots()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
