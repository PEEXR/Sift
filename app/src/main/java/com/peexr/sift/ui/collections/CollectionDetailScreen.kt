package com.peexr.sift.ui.collections

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.ui.components.ScreenshotTile
import com.peexr.sift.ui.navigation.FloatingNavClearance
import com.peexr.sift.ui.theme.SiftTheme
import com.peexr.sift.ui.theme.collectionColors

/**
 * Route composable connecting [CollectionDetailViewModel] to the stateless [CollectionDetailScreen].
 */
@Composable
fun CollectionDetailRoute(
    viewModel: CollectionDetailViewModel,
    onBack: () -> Unit,
    onReview: (String) -> Unit,
    onOpenScreenshot: (ScreenshotItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    var hasLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.collection) {
        if (uiState.collection != null) {
            hasLoaded = true
        } else if (hasLoaded) {
            // The collection was deleted or removed
            onBack()
        }
    }

    CollectionDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onReview = {
            uiState.collection?.id?.let { onReview(it) }
        },
        onOpenScreenshot = onOpenScreenshot,
        onFilterSelected = viewModel::setFilter,
        onUpdateDescription = viewModel::updateDescription,
        onDelete = {
            viewModel.delete()
            onBack()
        },
        modifier = modifier
    )
}

/**
 * Stateless collection detail page displaying collection header, description chip,
 * review button, All/Auto/Kept filter buttons, and a 3-column screenshot grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    uiState: CollectionDetailUiState,
    onBack: () -> Unit,
    onReview: () -> Unit,
    onOpenScreenshot: (ScreenshotItem) -> Unit,
    onFilterSelected: (MemberFilter) -> Unit,
    onUpdateDescription: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val collection = uiState.collection
    if (collection == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator()
        }
        return
    }

    val (containerColor, inkColor) = collectionColors(collection.colorHex)
    val dark = isSystemInDarkTheme()
    val pageInk = if (dark) containerColor else inkColor
    val backgroundColor = containerColor.copy(alpha = if (dark) 0.12f else 0.35f).compositeOver(MaterialTheme.colorScheme.background)

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = backgroundColor,
        contentWindowInsets = WindowInsets.statusBars,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = FloatingNavClearance
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Header block spans all columns
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Back & overflow buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = containerColor,
                            contentColor = inkColor,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Back to collections",
                                    onClick = onBack
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to collections",
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Box {
                            Surface(
                                shape = CircleShape,
                                color = containerColor,
                                contentColor = inkColor,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "More options",
                                        onClick = { showMenu = true }
                                    )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More options",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Edit description") },
                                    onClick = {
                                        showMenu = false
                                        showEditDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Delete collection",
                                            color = SiftTheme.extended.removeAccent
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showDeleteDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = SiftTheme.extended.removeAccent
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // Title, chip, and caption
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = collection.name,
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontSize = 68.sp,
                                lineHeight = 64.sp,
                                letterSpacing = (-3.0).sp
                            ),
                            color = pageInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Fills with chip
                        Surface(
                            shape = CircleShape,
                            color = containerColor,
                            contentColor = inkColor,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Edit description",
                                    onClick = { showEditDialog = true }
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (uiState.isRefilling) {
                                    LoadingIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = pageInk
                                    )
                                }
                                Text(
                                    text = "Fills with \u201C${collection.description}\u201D",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Caption: "N screenshots · X MB"
                        Text(
                            text = "${collection.memberCount} screenshot${if (collection.memberCount == 1) "" else "s"} \u00B7 ${formatBytes(collection.totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = pageInk.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }

                    // Big dark pill button "Review N unsure" (hidden when reviewCount is 0)
                    if (collection.reviewCount > 0) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .clip(CircleShape)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Review ${collection.reviewCount} unsure",
                                    onClick = onReview
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 26.dp, end = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "Review ${collection.reviewCount} unsure",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.inverseOnSurface
                                    )
                                    Text(
                                        text = "Swipe to keep or remove",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = containerColor
                                    )
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = SiftTheme.extended.keepAccent,
                                    contentColor = SiftTheme.extended.onKeepAccent,
                                    modifier = Modifier.size(54.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Review unsure screenshots",
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Filter row: All / Auto / Kept
                    CollectionFilterRow(
                        currentFilter = uiState.filter,
                        allCount = uiState.allCount,
                        autoCount = uiState.autoCount,
                        keptCount = uiState.keptCount,
                        inkColor = pageInk,
                        onInkColor = if (dark) inkColor else Color.White,
                        onFilterSelected = onFilterSelected
                    )
                }
            }

            // Grid items or empty filter state
            if (uiState.members.isEmpty()) {
                item(key = "empty_filter", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Nothing here yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = pageInk.copy(alpha = 0.8f)
                        )
                        if (uiState.isRefilling) {
                            Text(
                                text = "Sift is still looking\u2026",
                                style = MaterialTheme.typography.bodyMedium,
                                color = pageInk.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            } else {
                items(
                    items = uiState.members,
                    key = { it.item.id }
                ) { member ->
                    ScreenshotTile(
                        item = member.item,
                        kept = member.kept,
                        onClick = onOpenScreenshot
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = "Delete collection?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = SiftTheme.extended.displayInk
                )
            },
            text = {
                Text(
                    text = "Screenshots stay on your phone. Only the collection is removed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(
                        text = "Delete",
                        color = SiftTheme.extended.removeAccent,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    if (showEditDialog) {
        var editedDescription by remember(collection.description) {
            mutableStateOf(collection.description)
        }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = {
                Text(
                    text = "Edit description",
                    style = MaterialTheme.typography.headlineSmall,
                    color = SiftTheme.extended.displayInk
                )
            },
            text = {
                OutlinedTextField(
                    value = editedDescription,
                    onValueChange = { editedDescription = it },
                    label = { Text("What should it fill with?") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateDescription(editedDescription.trim())
                        showEditDialog = false
                    },
                    enabled = editedDescription.isNotBlank(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = MaterialTheme.shapes.extraLarge
        )
    }
}

/**
 * Filter row segmented control for All / Auto / Kept members.
 * Selected button uses collection ink with a 14 dp corner radius; unselected buttons are fully rounded.
 */
@Composable
private fun CollectionFilterRow(
    currentFilter: MemberFilter,
    allCount: Int,
    autoCount: Int,
    keptCount: Int,
    inkColor: Color,
    onInkColor: Color,
    onFilterSelected: (MemberFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val filters = listOf(
        Triple(MemberFilter.ALL, "All", allCount),
        Triple(MemberFilter.AUTO, "Auto", autoCount),
        Triple(MemberFilter.KEPT, "Kept", keptCount)
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        filters.forEach { (filter, label, count) ->
            val isSelected = currentFilter == filter
            val cornerRadius by animateDpAsState(
                targetValue = if (isSelected) 14.dp else 999.dp,
                label = "FilterCornerRadius"
            )
            val buttonContainer = if (isSelected) inkColor else MaterialTheme.colorScheme.surfaceContainerHigh
            val buttonContent = if (isSelected) onInkColor else inkColor

            Surface(
                shape = RoundedCornerShape(cornerRadius),
                color = buttonContainer,
                contentColor = buttonContent,
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(cornerRadius))
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onFilterSelected(filter) }
                    )
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 18.dp)
                ) {
                    Text(
                        text = "$label $count",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = buttonContent
                    )
                }
            }
        }
    }
}
