package com.peexr.sift.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import com.peexr.sift.SiftApplication
import com.peexr.sift.data.repository.ScreenshotRepository
import com.peexr.sift.ui.collections.CollectionDetailRoute
import com.peexr.sift.ui.collections.CollectionDetailViewModel
import com.peexr.sift.ui.collections.CollectionsRoute
import com.peexr.sift.ui.collections.CollectionsViewModel
import com.peexr.sift.ui.library.LibraryRoute
import com.peexr.sift.ui.library.LibraryViewModel
import com.peexr.sift.ui.review.ReviewRoute
import com.peexr.sift.ui.review.ReviewViewModel
import com.peexr.sift.ui.settings.SettingsRoute
import com.peexr.sift.ui.settings.SettingsViewModel
import com.peexr.sift.ui.viewer.ViewerRoute
import com.peexr.sift.ui.viewer.ViewerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiftMainApp(
    libraryViewModel: LibraryViewModel,
    reviewViewModel: ReviewViewModel,
    repository: ScreenshotRepository,
    collectionsViewModel: CollectionsViewModel,
    startOnCollectionsWithCreateSheet: Boolean = false
) {
    var currentDestination by remember {
        mutableStateOf<SiftDestination>(
            if (startOnCollectionsWithCreateSheet) SiftDestination.Collections else SiftDestination.Library
        )
    }
    var openCreateSheetOnCollections by remember { mutableStateOf(startOnCollectionsWithCreateSheet) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var openCollectionId by remember { mutableStateOf<String?>(null) }
    var reviewCollectionId by remember { mutableStateOf<String?>(null) }
    var viewerScreenshotId by remember { mutableStateOf<Long?>(null) }

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
                            onOpenSettings = { showSettingsScreen = true },
                            onOpenScreenshot = { item -> viewerScreenshotId = item.id }
                        )
                    }
                    is SiftDestination.Collections -> {
                        CollectionsRoute(
                            viewModel = collectionsViewModel,
                            onOpenCollection = { id -> openCollectionId = id },
                            openCreateSheetOnInit = openCreateSheetOnCollections,
                            onConsumedCreateSheet = { openCreateSheetOnCollections = false }
                        )
                    }
                    is SiftDestination.Review -> {
                        ReviewRoute(
                            viewModel = reviewViewModel,
                            initialCollectionId = reviewCollectionId,
                            onConsumedCollectionId = { reviewCollectionId = null },
                            onOpenScreenshot = { item -> viewerScreenshotId = item.id },
                            onNavigateToCollections = { currentDestination = SiftDestination.Collections }
                        )
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
                        onReview = { reviewId ->
                            reviewCollectionId = reviewId
                            openCollectionId = null
                            currentDestination = SiftDestination.Review
                        },
                        onOpenScreenshot = { item ->
                            viewerScreenshotId = item.id
                        }
                    )
                }
            }

            // Viewer overlay on top of everything with scale and fade transition
            AnimatedVisibility(
                visible = viewerScreenshotId != null,
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f),
                modifier = Modifier.fillMaxSize()
            ) {
                viewerScreenshotId?.let { id ->
                    val viewerViewModel: ViewerViewModel = viewModel(
                        key = "viewer-$id",
                        factory = ViewerViewModel.Factory(repository, id)
                    )
                    BackHandler {
                        viewerScreenshotId = null
                    }
                    ViewerRoute(
                        viewModel = viewerViewModel,
                        onClose = { viewerScreenshotId = null }
                    )
                }
            }

            // Settings overlay on top of tabs with slide and fade transition
            AnimatedVisibility(
                visible = showSettingsScreen,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                val context = LocalContext.current
                val app = context.applicationContext as SiftApplication
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(repository, app)
                )
                BackHandler {
                    showSettingsScreen = false
                }
                SettingsRoute(
                    viewModel = settingsViewModel,
                    onBack = { showSettingsScreen = false }
                )
            }

            // Floating pill nav: hidden while a collection, viewer, or settings is open
            AnimatedVisibility(
                visible = openCollectionId == null && viewerScreenshotId == null && !showSettingsScreen,
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
}
