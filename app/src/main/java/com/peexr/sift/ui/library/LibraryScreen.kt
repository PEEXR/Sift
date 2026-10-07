package com.peexr.sift.ui.library

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.ui.components.ScreenshotTile
import com.peexr.sift.ui.navigation.FloatingNavClearance
import androidx.compose.foundation.layout.navigationBarsPadding
import com.peexr.sift.ui.theme.SiftTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Thin route composable wiring [LibraryViewModel] to the stateless [LibraryScreen].
 */
@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    LibraryScreen(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onStartIndexing = viewModel::startIndexing,
        onDownloadModel = viewModel::downloadModel,
        onRefresh = viewModel::refresh,
        onClearStatusMessage = viewModel::clearStatusMessage,
        onOpenSettings = onOpenSettings,
        modifier = modifier
    )
}

/**
 * Stateless Library screen composable designed per Material 3 Expressive guidelines.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onStartIndexing: () -> Unit,
    onDownloadModel: () -> Unit,
    onRefresh: () -> Unit,
    onClearStatusMessage: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val onOpenScreenshot: (ScreenshotItem) -> Unit = remember(context) {
        { item ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(item.uri), "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("LibraryScreen", "Failed to open screenshot ${item.id}", e)
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // Indexing only progresses while Sift is on screen (the OEM freezer pauses it in the background),
    // so keep the display awake for the duration of a running index.
    val view = LocalView.current
    val indexingRunning = uiState.indexing is IndexingUi.Running
    DisposableEffect(view, indexingRunning) {
        view.keepScreenOn = indexingRunning
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onClearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = {
            // Lift snackbars above the floating pill nav, which overlays the bottom of the screen
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = FloatingNavClearance)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Pull to refresh around grid
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 16.dp + FloatingNavClearance
                    ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(key = "top", span = { GridItemSpan(maxLineSpan) }) {
                        LibraryTopBlock(
                            uiState = uiState,
                            onQueryChange = onQueryChange,
                            onOpenSettings = onOpenSettings,
                            onStartIndexing = onStartIndexing,
                            onDownloadModel = onDownloadModel
                        )
                    }

                    if (uiState.query.isNotBlank()) {
                        // Search Mode
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            val resultsCount = uiState.searchResults?.size ?: 0
                            val modelNotReady = uiState.indexing is IndexingUi.NeedsModel ||
                                uiState.indexing is IndexingUi.Downloading ||
                                uiState.indexing is IndexingUi.ModelFailed

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "$resultsCount result${if (resultsCount == 1) "" else "s"} for \u201C${uiState.query}\u201D",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = SiftTheme.extended.displayInk
                                )
                                if (modelNotReady) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Text-only results until smart search is set up",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (uiState.isSearching) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    LoadingIndicator()
                                }
                            }
                        } else if (uiState.searchResults != null && uiState.searchResults.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No matches. Try different words.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else if (uiState.searchResults != null) {
                            items(
                                items = uiState.searchResults,
                                key = { it.id }
                            ) { item ->
                                ScreenshotTile(item = item, onClick = onOpenScreenshot)
                            }
                        }
                    } else {
                        // Normal Sections Mode
                        if (uiState.sections.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No screenshots found",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            uiState.sections.forEach { section ->
                                item(
                                    key = "section_${section.title}",
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        Text(
                                            text = section.title,
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = SiftTheme.extended.displayInk
                                        )
                                        Text(
                                            text = section.items.size.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                items(
                                    items = section.items,
                                    key = { it.id }
                                ) { item ->
                                    ScreenshotTile(item = item, onClick = onOpenScreenshot)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexingCard(
    indexing: IndexingUi,
    onStartIndexing: () -> Unit,
    onDownloadModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val icon = when (indexing) {
                            is IndexingUi.NeedsModel -> Icons.Default.Download
                            is IndexingUi.Downloading -> Icons.Default.Sync
                            is IndexingUi.ModelFailed -> Icons.Default.Refresh
                            is IndexingUi.Pending -> Icons.Default.AutoAwesome
                            is IndexingUi.Running -> Icons.Default.Sync
                            is IndexingUi.Hidden -> Icons.Default.AutoAwesome
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    when (indexing) {
                        is IndexingUi.NeedsModel -> {
                            Text(
                                text = "Smart search model",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Needed once for smart search and auto-filling collections",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        is IndexingUi.Downloading -> {
                            Text(
                                text = "Downloading model…",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${(indexing.fraction * 100).toInt()}% downloaded",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        is IndexingUi.ModelFailed -> {
                            Text(
                                text = "Model download failed",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = indexing.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        is IndexingUi.Pending -> {
                            val eta = formatEta(indexing.remaining * 11L)
                            Text(
                                text = "Indexing pending",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${indexing.remaining} screenshot${if (indexing.remaining == 1) "" else "s"} waiting · keep Sift open · $eta",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        is IndexingUi.Running -> {
                            val eta = formatEta(indexing.remaining * 11L)
                            Text(
                                text = "Indexing ${indexing.done} of ${indexing.total}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Keep Sift open · $eta left",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        is IndexingUi.Hidden -> Unit
                    }
                }
            }

            // Progress bar
            val showProgress = indexing is IndexingUi.Downloading || indexing is IndexingUi.Running
            if (showProgress) {
                val fraction = when (indexing) {
                    is IndexingUi.Downloading -> indexing.fraction
                    is IndexingUi.Running -> if (indexing.total > 0) (indexing.done.toFloat() / indexing.total.toFloat()).coerceIn(0f, 1f) else 0f
                    else -> 0f
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            // Action button
            when (indexing) {
                is IndexingUi.NeedsModel -> {
                    Button(
                        onClick = onDownloadModel,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Download ${indexing.sizeMb} MB")
                    }
                }
                is IndexingUi.ModelFailed -> {
                    Button(
                        onClick = onDownloadModel,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Retry")
                    }
                }
                is IndexingUi.Pending -> {
                    Button(
                        onClick = onStartIndexing,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Index now")
                    }
                }
                else -> Unit
            }
        }
    }
}

/** Title, search pill and indexing card; the first full-width item of the grid so it scrolls with the photos. */
@Composable
private fun LibraryTopBlock(
    uiState: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onStartIndexing: () -> Unit,
    onDownloadModel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        // Header Row: "Library" + caption + settings icon button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.displayLarge,
                    color = SiftTheme.extended.displayInk
                )
                Text(
                    text = "${uiState.totalCount} screenshot${if (uiState.totalCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            FilledTonalIconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = SiftTheme.extended.displayInk
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search pill (58 dp, CircleShape, surfaceContainerHigh)
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (uiState.query.isEmpty()) {
                        Text(
                            text = "Search your screenshots",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = uiState.query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (uiState.query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Indexing card under search (only when not Hidden)
        if (uiState.indexing !is IndexingUi.Hidden) {
            Spacer(modifier = Modifier.height(14.dp))
            IndexingCard(
                indexing = uiState.indexing,
                onStartIndexing = onStartIndexing,
                onDownloadModel = onDownloadModel,
                modifier = Modifier
            )
        }
    }
}
