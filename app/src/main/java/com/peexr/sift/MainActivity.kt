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
import androidx.core.content.ContextCompat
import com.peexr.sift.ui.cleanup.CleanupViewModel
import com.peexr.sift.ui.collections.CollectionsViewModel
import com.peexr.sift.ui.library.LibraryViewModel
import com.peexr.sift.ui.navigation.SiftMainApp
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

    private val cleanupViewModel: CleanupViewModel by viewModels {
        CleanupViewModel.Factory((application as SiftApplication).repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as SiftApplication

        setContent {
            SiftTheme {
                SiftMainApp(
                    libraryViewModel = libraryViewModel,
                    cleanupViewModel = cleanupViewModel,
                    settingsManager = app.settingsManager,
                    collectionsViewModel = collectionsViewModel
                )
            }
        }

        checkAndRequestPermissions()
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
