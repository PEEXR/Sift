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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peexr.sift.data.local.SettingsManager
import com.peexr.sift.ui.cleanup.CleanupScreen
import com.peexr.sift.ui.cleanup.CleanupViewModel
import com.peexr.sift.ui.collections.CollectionsScreen
import com.peexr.sift.ui.collections.CollectionsViewModel
import com.peexr.sift.ui.settings.SettingsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiftMainApp(
    collectionsViewModel: CollectionsViewModel,
    cleanupViewModel: CleanupViewModel,
    settingsManager: SettingsManager
) {
    var currentDestination by remember { mutableStateOf<SiftDestination>(SiftDestination.Collections) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val destinations = listOf(
        SiftDestination.Collections,
        SiftDestination.Cleanup
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Sift",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                destinations.forEach { dest ->
                    val selected = currentDestination == dest
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentDestination = dest },
                        icon = { Icon(dest.icon, contentDescription = dest.title) },
                        label = { Text(dest.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Crossfade(
                targetState = currentDestination,
                label = "NavigationCrossfade"
            ) { destination ->
                when (destination) {
                    is SiftDestination.Collections -> {
                        CollectionsScreen(viewModel = collectionsViewModel)
                    }
                    is SiftDestination.Cleanup -> {
                        CleanupScreen(viewModel = cleanupViewModel)
                    }
                }
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
