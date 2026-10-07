package com.peexr.sift.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.peexr.sift.data.local.SettingsManager
import com.peexr.sift.ui.cleanup.CleanupScreen
import com.peexr.sift.ui.cleanup.CleanupViewModel
import com.peexr.sift.ui.collections.CollectionsListScreen
import com.peexr.sift.ui.collections.CollectionsViewModel
import com.peexr.sift.ui.library.LibraryRoute
import com.peexr.sift.ui.library.LibraryViewModel
import com.peexr.sift.ui.settings.SettingsDialog
import com.peexr.sift.ui.theme.SiftTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiftMainApp(
    libraryViewModel: LibraryViewModel,
    cleanupViewModel: CleanupViewModel,
    settingsManager: SettingsManager,
    collectionsViewModel: CollectionsViewModel? = null
) {
    var currentDestination by remember { mutableStateOf<SiftDestination>(SiftDestination.Library) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val destinations = listOf(
        SiftDestination.Library,
        SiftDestination.Collections,
        SiftDestination.Review
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Crossfade(
                targetState = currentDestination,
                label = "NavigationCrossfade",
                modifier = Modifier.fillMaxSize()
            ) { destination ->
                when (destination) {
                    is SiftDestination.Library -> {
                        LibraryRoute(
                            viewModel = libraryViewModel,
                            onOpenSettings = { showSettingsDialog = true }
                        )
                    }
                    is SiftDestination.Collections -> {
                        CollectionsListScreen()
                    }
                    is SiftDestination.Review -> {
                        CleanupScreen(viewModel = cleanupViewModel)
                    }
                }
            }

            FloatingPillNav(
                destinations = destinations,
                currentDestination = currentDestination,
                onDestinationSelected = { currentDestination = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentApiKey = settingsManager.typeSafeApiKey ?: "",
            onDismiss = { showSettingsDialog = false },
            onSaveKey = { newKey ->
                settingsManager.typeSafeApiKey = newKey
            }
        )
    }
}
