package com.peexr.sift.ui.review

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peexr.sift.data.cleanup.AgeFilter
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.ui.collections.formatBytes
import com.peexr.sift.ui.navigation.FloatingNavClearance
import com.peexr.sift.ui.theme.BricolageGrotesque
import com.peexr.sift.ui.theme.SiftTheme

/**
 * Route composable connecting [ReviewViewModel] to [ReviewScreen].
 */
@Composable
fun ReviewRoute(
    viewModel: ReviewViewModel,
    initialCollectionId: String? = null,
    onConsumedCollectionId: () -> Unit = {},
    onOpenScreenshot: (ScreenshotItem) -> Unit,
    onNavigateToCollections: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Consume initial collection ID if passed from another screen
    LaunchedEffect(initialCollectionId) {
        if (initialCollectionId != null) {
            viewModel.openCollection(initialCollectionId)
            onConsumedCollectionId()
        }
    }

    // System delete request launcher
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (uiState.mode is ReviewMode.Collection) {
                val currentId = uiState.stack.getOrNull(uiState.index)?.item?.id
                if (currentId != null) {
                    viewModel.onFilesDeleted(setOf(currentId))
                }
            } else {
                viewModel.onFilesDeleted(uiState.marked)
            }
        } else {
            viewModel.onDeleteCancelled()
        }
    }

    LaunchedEffect(uiState.deletePendingIntent) {
        uiState.deletePendingIntent?.let { pendingIntent ->
            deleteLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        }
    }

    ReviewScreen(
        uiState = uiState,
        onOpenCollection = viewModel::openCollection,
        onSubmitSearch = viewModel::submitSearch,
        onClearSearch = viewModel::clearSearch,
        onKeep = viewModel::keep,
        onRemoveOrMark = viewModel::removeOrMark,
        onMarkRemaining = viewModel::markRemaining,
        onUndo = viewModel::undo,
        onDeleteCurrentFile = viewModel::deleteCurrentFile,
        onRequestDeleteMarked = viewModel::requestDeleteMarked,
        onClearMarks = viewModel::clearMarks,
        onRestart = viewModel::restart,
        onBack = viewModel::back,
        onAgeFilterChange = viewModel::setAgeFilter,
        onStartCleanup = viewModel::startCleanup,
        onOpenScreenshot = onOpenScreenshot,
        onNavigateToCollections = onNavigateToCollections,
        modifier = modifier
    )
}

/**
 * Stateless Review screen supporting Picker, Collection mode, and Search mode.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    uiState: ReviewUiState,
    onOpenCollection: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onKeep: () -> Unit,
    onRemoveOrMark: () -> Unit,
    onMarkRemaining: () -> Unit,
    onUndo: () -> Unit,
    onDeleteCurrentFile: () -> Unit,
    onRequestDeleteMarked: () -> Unit,
    onClearMarks: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
    onAgeFilterChange: (AgeFilter) -> Unit = {},
    onStartCleanup: (CleanupPreset) -> Unit = {},
    onOpenScreenshot: (ScreenshotItem) -> Unit,
    onNavigateToCollections: () -> Unit,
    modifier: Modifier = Modifier
) {
    val swipeState = rememberSwipeStackState()
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var searchInput by remember(uiState.query) { mutableStateOf(uiState.query) }

    // Intercept back presses when in collection or search mode
    if (uiState.mode !is ReviewMode.Picker) {
        BackHandler(onBack = onBack)
    }

    // Dynamic background flood tint based on drag distance
    val keepAccent = SiftTheme.extended.keepAccent
    val removeAccent = SiftTheme.extended.removeAccent
    val baseBg = MaterialTheme.colorScheme.background
    val targetBg = when {
        dragFraction > 0.01f -> {
            val alpha = (0.12f + dragFraction * 0.45f).coerceIn(0f, 0.65f)
            keepAccent.copy(alpha = alpha).compositeOver(baseBg)
        }
        dragFraction < -0.01f -> {
            val alpha = (0.10f + (-dragFraction) * 0.40f).coerceIn(0f, 0.60f)
            removeAccent.copy(alpha = alpha).compositeOver(baseBg)
        }
        else -> baseBg
    }
    val backgroundColor by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 220),
        label = "ReviewScreenBackground"
    )

    Scaffold(
        containerColor = backgroundColor,
        contentWindowInsets = WindowInsets.statusBars,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header & Search Pill Block
                ReviewHeaderBlock(
                    mode = uiState.mode,
                    phase = uiState.phase,
                    ageFilter = uiState.ageFilter,
                    onAgeFilterChange = onAgeFilterChange,
                    query = searchInput,
                    onQueryChange = { searchInput = it },
                    onSubmitSearch = { onSubmitSearch(searchInput) },
                    onClearSearch = {
                        searchInput = ""
                        onClearSearch()
                    },
                    onBack = onBack,
                    index = uiState.index,
                    total = uiState.stack.size
                )

                // Main Content Body based on Mode and Phase
                when (uiState.mode) {
                    is ReviewMode.Picker -> {
                        ReviewPicker(
                            collections = uiState.collections,
                            ageFilter = uiState.ageFilter,
                            onAgeFilterChange = onAgeFilterChange,
                            loadingCleanupPreset = uiState.loadingCleanupPreset,
                            onStartCleanup = onStartCleanup,
                            onSelectCollection = onOpenCollection,
                            onSelectSuggestion = { suggestion ->
                                searchInput = suggestion
                                onSubmitSearch(suggestion)
                            },
                            onNavigateToCollections = onNavigateToCollections,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    is ReviewMode.Collection, is ReviewMode.Search, is ReviewMode.Cleanup -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when (uiState.phase) {
                                is ReviewPhase.Loading -> {
                                    LoadingIndicator()
                                }

                                is ReviewPhase.Empty -> {
                                    val emptyMessage = when (uiState.mode) {
                                        is ReviewMode.Search -> "No matches. Try different words."
                                        is ReviewMode.Cleanup -> "Nothing to clean here"
                                        else -> "All caught up in this collection!"
                                    }
                                    ReviewEmptyCard(
                                        message = emptyMessage,
                                        onBack = onBack
                                    )
                                }

                                is ReviewPhase.Swiping -> {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // 3-card stack
                                        SwipeCardStack(
                                            stack = uiState.stack,
                                            index = uiState.index,
                                            isSearchMode = uiState.mode is ReviewMode.Search || uiState.mode is ReviewMode.Cleanup,
                                            onKeep = onKeep,
                                            onRemove = onRemoveOrMark,
                                            onOpenScreenshot = onOpenScreenshot,
                                            onDragFractionChange = { dragFraction = it },
                                            swipeState = swipeState,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        // Optional: "Mark remaining N strong matches" pill in search mode
                                        if (uiState.showMarkRemaining) {
                                            val remaining = (uiState.stack.size - uiState.index).coerceAtLeast(1)
                                            Button(
                                                onClick = onMarkRemaining,
                                                shape = CircleShape,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                                                    contentColor = MaterialTheme.colorScheme.inverseOnSurface
                                                ),
                                                modifier = Modifier
                                                    .padding(top = 10.dp)
                                                    .height(44.dp)
                                            ) {
                                                Text(
                                                    text = "Mark remaining $remaining strong ${if (remaining == 1) "match" else "matches"}",
                                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                                )
                                            }
                                        }

                                        // Outlined "Delete file" button in collection mode
                                        if (uiState.mode is ReviewMode.Collection) {
                                            OutlinedButton(
                                                onClick = onDeleteCurrentFile,
                                                shape = RoundedCornerShape(18.dp),
                                                border = BorderStroke(2.dp, SiftTheme.extended.displayInk),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = SiftTheme.extended.displayInk
                                                ),
                                                modifier = Modifier
                                                    .padding(top = 8.dp)
                                                    .height(48.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Delete,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Delete file",
                                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                                )
                                            }
                                        }

                                        // Bottom controls row: Coral action (80dp), Undo (48dp), Lime keep (80dp)
                                        ReviewBottomControlRow(
                                            mode = uiState.mode,
                                            canUndo = uiState.canUndo,
                                            onLeftClick = { swipeState.swipeLeft() },
                                            onUndoClick = onUndo,
                                            onRightClick = { swipeState.swipeRight() },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                                .padding(bottom = FloatingNavClearance)
                                        )
                                    }
                                }

                                is ReviewPhase.Done -> {
                                    val colName = (uiState.mode as? ReviewMode.Collection)?.name ?: "Collection"
                                    CollectionDoneCard(
                                        collectionName = colName,
                                        kept = uiState.kept,
                                        removed = uiState.removed,
                                        deleted = uiState.deleted,
                                        onBack = onBack,
                                        onRestart = onRestart,
                                        modifier = Modifier.padding(bottom = FloatingNavClearance)
                                    )
                                }

                                is ReviewPhase.Summary -> {
                                    SearchSummaryCard(
                                        markedCount = uiState.marked.size,
                                        keptCount = uiState.kept,
                                        markedBytes = uiState.markedTotalBytes,
                                        onConfirmDelete = onRequestDeleteMarked,
                                        onKeepAll = onClearMarks,
                                        onRestart = onRestart,
                                        modifier = Modifier.padding(bottom = FloatingNavClearance)
                                    )
                                }

                                is ReviewPhase.Freed -> {
                                    SearchFreedCard(
                                        bytesFreed = uiState.phase.bytes,
                                        isCleanup = uiState.mode is ReviewMode.Cleanup,
                                        onNewSearch = onClearSearch,
                                        modifier = Modifier.padding(bottom = FloatingNavClearance)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top header containing back button, search pill, and progress bar.
 */
@Composable
private fun ReviewHeaderBlock(
    mode: ReviewMode,
    phase: ReviewPhase,
    ageFilter: AgeFilter,
    onAgeFilterChange: (AgeFilter) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onBack: () -> Unit,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (mode is ReviewMode.Picker) {
            // Header for picker: large title
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Review",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 58.sp,
                        lineHeight = 56.sp,
                        letterSpacing = (-2.5).sp
                    ),
                    color = SiftTheme.extended.displayInk
                )
                Text(
                    text = "Find screenshots to clean up",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        // Search pill row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (mode !is ReviewMode.Picker) {
                // Back button circle
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = SiftTheme.extended.displayInk,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(
                            role = Role.Button,
                            onClick = onBack
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Search pill
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Find screenshots to clean up",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onSubmitSearch()
                            }),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (query.isNotEmpty() || mode is ReviewMode.Search) {
                        IconButton(
                            onClick = onClearSearch,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Progress bar and context label (shown only in Collection or Search modes with active stack)
        if (mode !is ReviewMode.Picker && total > 0) {
            val contextLabel = when (mode) {
                is ReviewMode.Collection -> "${mode.name} \u00B7 least sure first"
                is ReviewMode.Search -> "$total matches for \u201C${mode.query}\u201D \u00B7 least sure first"
                is ReviewMode.Cleanup -> when (mode.preset) {
                    CleanupPreset.CODES -> "Codes & expiring \u00B7 $total"
                    CleanupPreset.DUPLICATES -> "Near-duplicates \u00B7 $total"
                }
                else -> ""
            }
            val progressLabel = "${(index + 1).coerceAtMost(total)} of $total"
            val progressFraction = (index.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            val animatedFraction by animateFloatAsState(
                targetValue = progressFraction,
                animationSpec = tween(durationMillis = 300),
                label = "ReviewProgress"
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = contextLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = SiftTheme.extended.displayInk
                    )
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = SiftTheme.extended.displayInk
                    )
                }

                // 14 dp rounded progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(CircleShape)
                        .background(SiftTheme.extended.displayInk.copy(alpha = 0.14f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedFraction)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(if (mode is ReviewMode.Search || mode is ReviewMode.Cleanup) SiftTheme.extended.removeAccent else MaterialTheme.colorScheme.primary)
                    )
                }

                // Age filter chips row in header during active search or CODES cleanup swiping
                if ((mode is ReviewMode.Search || (mode is ReviewMode.Cleanup && mode.preset == CleanupPreset.CODES)) && phase == ReviewPhase.Swiping) {
                    AgeFilterRow(
                        selectedFilter = ageFilter,
                        onSelectFilter = onAgeFilterChange,
                        compact = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Bottom controls row: Coral X / Trash (80dp), Undo (48dp), Lime Check (80dp).
 */
@Composable
private fun ReviewBottomControlRow(
    mode: ReviewMode,
    canUndo: Boolean,
    onLeftClick: () -> Unit,
    onUndoClick: () -> Unit,
    onRightClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSearch = mode is ReviewMode.Search || mode is ReviewMode.Cleanup
    val leftAriaLabel = if (isSearch) "Mark for deletion" else "Remove from collection"

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left button (80dp coral circle)
        Surface(
            shape = CircleShape,
            color = SiftTheme.extended.removeAccent,
            contentColor = SiftTheme.extended.onRemoveAccent,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = leftAriaLabel,
                    onClick = onLeftClick
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isSearch) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = leftAriaLabel,
                        modifier = Modifier.size(34.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = leftAriaLabel,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        // Middle button (48dp Undo circle)
        Surface(
            shape = CircleShape,
            color = if (canUndo) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
            contentColor = if (canUndo) SiftTheme.extended.displayInk else SiftTheme.extended.displayInk.copy(alpha = 0.35f),
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    enabled = canUndo,
                    role = Role.Button,
                    onClickLabel = "Undo",
                    onClick = onUndoClick
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Right button (80dp lime circle)
        Surface(
            shape = CircleShape,
            color = SiftTheme.extended.keepAccent,
            contentColor = SiftTheme.extended.onKeepAccent,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Keep",
                    onClick = onRightClick
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Keep",
                    modifier = Modifier.size(38.dp)
                )
            }
        }
    }
}

/**
 * Card displayed when a collection review is complete.
 */
@Composable
private fun CollectionDoneCard(
    collectionName: String,
    kept: Int,
    removed: Int,
    deleted: Int,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(40.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Lime badge
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = SiftTheme.extended.keepAccent,
                contentColor = SiftTheme.extended.onKeepAccent,
                modifier = Modifier
                    .size(68.dp)
                    .rotate(-8f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Text(
                text = "$collectionName\nsorted!",
                fontFamily = BricolageGrotesque,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 50.sp,
                lineHeight = 48.sp,
                letterSpacing = (-2.0).sp,
                color = MaterialTheme.colorScheme.inverseOnSurface
            )

            // Result pill badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = SiftTheme.extended.keepAccent,
                    contentColor = SiftTheme.extended.onKeepAccent,
                    modifier = Modifier.height(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                        Text(
                            text = "$kept kept",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = SiftTheme.extended.removeAccent,
                    contentColor = SiftTheme.extended.onRemoveAccent,
                    modifier = Modifier.height(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                        Text(
                            text = "$removed removed",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    contentColor = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.height(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                        Text(
                            text = "$deleted deleted",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onBack,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.inverseOnSurface,
                        contentColor = MaterialTheme.colorScheme.inverseSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Text(
                        text = "Back to collections",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                OutlinedButton(
                    onClick = onRestart,
                    shape = CircleShape,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.inverseOnSurface),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface
                    ),
                    modifier = Modifier.height(52.dp)
                ) {
                    Text(
                        text = "Review again",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

/**
 * Summary card displayed at the end of search swipe stack.
 */
@Composable
private fun SearchSummaryCard(
    markedCount: Int,
    keptCount: Int,
    markedBytes: Long,
    onConfirmDelete: () -> Unit,
    onKeepAll: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(40.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            val title = if (markedCount > 0) "Delete $markedCount files?" else "Nothing marked"
            val caption = if (markedCount > 0) {
                "$keptCount kept \u00B7 ${formatBytes(markedBytes)} will be freed"
            } else {
                "You kept every match."
            }

            Text(
                text = title,
                fontFamily = BricolageGrotesque,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 46.sp,
                lineHeight = 44.sp,
                letterSpacing = (-1.8).sp,
                color = MaterialTheme.colorScheme.inverseOnSurface
            )

            Text(
                text = caption,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f)
            )

            if (markedCount > 0) {
                Button(
                    onClick = onConfirmDelete,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SiftTheme.extended.removeAccent,
                        contentColor = SiftTheme.extended.onRemoveAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Delete $markedCount files \u00B7 ${formatBytes(markedBytes)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onKeepAll,
                        shape = CircleShape,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.inverseOnSurface),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Keep all")
                    }

                    OutlinedButton(
                        onClick = onRestart,
                        shape = CircleShape,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.inverseOnSurface),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Review again")
                    }
                }
            } else {
                Button(
                    onClick = onRestart,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.inverseOnSurface,
                        contentColor = MaterialTheme.colorScheme.inverseSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Review again")
                }
            }
        }
    }
}

/**
 * Card displayed after confirmed deletion of marked files in search mode.
 */
@Composable
private fun SearchFreedCard(
    bytesFreed: Long,
    isCleanup: Boolean = false,
    onNewSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(40.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = SiftTheme.extended.keepAccent,
                contentColor = SiftTheme.extended.onKeepAccent,
                modifier = Modifier
                    .size(68.dp)
                    .rotate(-8f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Text(
                text = "${formatBytes(bytesFreed)} freed!",
                fontFamily = BricolageGrotesque,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 46.sp,
                lineHeight = 44.sp,
                letterSpacing = (-1.8).sp,
                color = MaterialTheme.colorScheme.inverseOnSurface
            )

            Text(
                text = "Android removed the files from storage.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f)
            )

            Button(
                onClick = onNewSearch,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.inverseOnSurface,
                    contentColor = MaterialTheme.colorScheme.inverseSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = if (isCleanup) "Back to review" else "New search",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun ReviewEmptyCard(
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = SiftTheme.extended.displayInk
            )
            Button(
                onClick = onBack,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Back to review")
            }
        }
    }
}
