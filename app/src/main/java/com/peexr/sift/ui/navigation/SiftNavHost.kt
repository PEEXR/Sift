package com.peexr.sift.ui.navigation

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peexr.sift.data.local.SettingsManager
import com.peexr.sift.data.repository.ScreenshotRepository
import com.peexr.sift.ui.cleanup.CleanupScreen
import com.peexr.sift.ui.cleanup.CleanupViewModel
import com.peexr.sift.ui.collections.CollectionDetailRoute
import com.peexr.sift.ui.collections.CollectionDetailViewModel
import com.peexr.sift.ui.collections.CollectionsRoute
import com.peexr.sift.ui.collections.CollectionsViewModel
import com.peexr.sift.ui.library.LibraryRoute
import com.peexr.sift.ui.library.LibraryViewModel
import com.peexr.sift.ui.settings.SettingsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiftMainApp(
    libraryViewModel: LibraryViewModel,
    cleanupViewModel: CleanupViewModel,
    settingsManager: SettingsManager,
    repository: ScreenshotRepository,
    collectionsViewModel: CollectionsViewModel
) {
    val context = LocalContext.current
    var currentDestination by remember { mutableStateOf<SiftDestination>(SiftDestination.Library) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var openCollectionId by remember { mutableStateOf<String?>(null) }

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
                        CollectionsRoute(
                            viewModel = collectionsViewModel,
                            onOpenCollection = { id -> openCollectionId = id }
                        )
                    }
                    is SiftDestination.Review -> {
                        CleanupScreen(viewModel = cleanupViewModel)
                    }
                }
            }

            // Collection Detail overlay on top of tabs with slide and fade transition
            AnimatedVisibility(
                visible = openCollectionId != null,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                openCollectionId?.let { id ->
                    val detailViewModel: CollectionDetailViewModel = viewModel(
                        key = id,
                        factory = CollectionDetailViewModel.Factory(repository, id)
                    )
                    BackHandler {
                        openCollectionId = null
                    }
                    CollectionDetailRoute(
                        viewModel = detailViewModel,
                        onBack = { openCollectionId = null },
                        onReview = {
                            openCollectionId = null
                            currentDestination = SiftDestination.Review
                        },
                        onOpenScreenshot = { item ->
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(item.uri), "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e("SiftMainApp", "Failed to open screenshot ${item.id}", e)
                            }
                        }
                    )
                }
            }

            // Floating pill nav: hidden while a collection is open
            AnimatedVisibility(
                visible = openCollectionId == null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                FloatingPillNav(
                    destinations = destinations,
                    currentDestination = currentDestination,
                    onDestinationSelected = { currentDestination = it }
                )
            }
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
