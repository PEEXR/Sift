package com.peexr.sift.ui.collections

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.peexr.sift.data.model.CollectionSummary
import com.peexr.sift.ui.navigation.FloatingNavClearance
import com.peexr.sift.ui.theme.SiftTheme
import com.peexr.sift.ui.theme.collectionColors

/**
 * Thin route composable wiring [CollectionsViewModel] to the stateless [CollectionsListScreen].
 */
@Composable
fun CollectionsRoute(
    viewModel: CollectionsViewModel,
    onOpenCollection: (String) -> Unit,
    openCreateSheetOnInit: Boolean = false,
    onConsumedCreateSheet: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    CollectionsListScreen(
        state = state,
        onOpenCollection = onOpenCollection,
        onCreate = viewModel::createCollection,
        onDismissStatus = viewModel::clearStatusMessage,
        openCreateSheetOnInit = openCreateSheetOnInit,
        onConsumedCreateSheet = onConsumedCreateSheet,
        modifier = modifier
    )
}

/**
 * Stateless collections list screen displaying tonal collection cards, an expressive
 * FAB for creating collections, an empty state when none exist, and a creation bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsListScreen(
    state: CollectionsUiState,
    onOpenCollection: (String) -> Unit,
    onCreate: (name: String, description: String) -> Unit,
    onDismissStatus: () -> Unit,
    openCreateSheetOnInit: Boolean = false,
    onConsumedCreateSheet: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateSheet by remember { mutableStateOf(openCreateSheetOnInit) }
    var newCollectionName by remember { mutableStateOf("") }
    var newCollectionDescription by remember { mutableStateOf("") }

    LaunchedEffect(openCreateSheetOnInit) {
        if (openCreateSheetOnInit) {
            showCreateSheet = true
            onConsumedCreateSheet()
        }
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onDismissStatus()
        }
    }

    Scaffold(
        snackbarHost = {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 20.dp,
                    bottom = FloatingNavClearance + 72.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Collections",
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontSize = 58.sp,
                                lineHeight = 56.sp,
                                letterSpacing = (-2.5).sp
                            ),
                            color = SiftTheme.extended.displayInk
                        )
                        val caption = if (state.collections.isEmpty()) {
                            "Make your first collection"
                        } else {
                            "${state.collections.size} collection${if (state.collections.size == 1) "" else "s"}"
                        }
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }

                if (state.collections.isEmpty()) {
                    item(key = "empty_state") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp, bottom = 24.dp)
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(80.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.FolderSpecial,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                            Text(
                                text = "No collections yet",
                                style = MaterialTheme.typography.headlineMedium,
                                color = SiftTheme.extended.displayInk
                            )
                            Text(
                                text = "Create one and Sift fills it with matching screenshots.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = { showCreateSheet = true },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Create a collection")
                            }
                        }
                    }
                } else {
                    items(
                        items = state.collections,
                        key = { it.id }
                    ) { collection ->
                        CollectionCard(
                            collection = collection,
                            onOpenCollection = onOpenCollection
                        )
                    }
                }
            }

            // 64 dp rounded-square FAB in keepAccent
            Surface(
                shape = MaterialTheme.shapes.large,
                color = SiftTheme.extended.keepAccent,
                contentColor = SiftTheme.extended.onKeepAccent,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = FloatingNavClearance + 16.dp)
                    .height(64.dp)
                    .clip(MaterialTheme.shapes.large)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "New collection"
                    ) {
                        showCreateSheet = true
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "New collection",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }

    if (showCreateSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showCreateSheet = false
                newCollectionName = ""
                newCollectionDescription = ""
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "New collection",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SiftTheme.extended.displayInk
                )
                OutlinedTextField(
                    value = newCollectionName,
                    onValueChange = { newCollectionName = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Memes, Receipts") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newCollectionDescription,
                    onValueChange = { newCollectionDescription = it },
                    label = { Text("What should it fill with?") },
                    supportingText = {
                        Text("Describe it in words \u2014 Sift finds matching screenshots.")
                    },
                    placeholder = { Text("funny memes and reaction images") },
                    maxLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onCreate(newCollectionName.trim(), newCollectionDescription.trim())
                        showCreateSheet = false
                        newCollectionName = ""
                        newCollectionDescription = ""
                    },
                    enabled = newCollectionName.isNotBlank(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        text = "Create",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

/**
 * Tonal card rendering a single collection with name, description, review badge,
 * 3-item thumbnail mosaic, and member count.
 */
@Composable
private fun CollectionCard(
    collection: CollectionSummary,
    onOpenCollection: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val (containerColor, inkColor) = collectionColors(collection.colorHex)
    val semanticsDesc = if (collection.reviewCount > 0) {
        "${collection.name}, ${collection.memberCount} screenshots, ${collection.reviewCount} to review"
    } else {
        "${collection.name}, ${collection.memberCount} screenshots"
    }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = inkColor
        ),
        onClick = { onOpenCollection(collection.id) },
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = semanticsDesc
                role = Role.Button
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Top row: Name & description on left, review pill on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = collection.name,
                        style = MaterialTheme.typography.headlineLarge,
                        color = inkColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "\u201C${collection.description}\u201D",
                        style = MaterialTheme.typography.bodyMedium,
                        color = inkColor.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (collection.reviewCount > 0) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(SiftTheme.extended.keepAccent)
                            )
                            Text(
                                text = "${collection.reviewCount} to review",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                    }
                }
            }

            // Bottom row: Thumbnail mosaic on left, screenshot count on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                CollectionMosaic(
                    previewUris = collection.previewUris,
                    inkColor = inkColor
                )

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Text(
                        text = "${collection.memberCount}",
                        style = MaterialTheme.typography.displaySmall,
                        color = inkColor
                    )
                    Text(
                        text = "screenshots",
                        style = MaterialTheme.typography.labelSmall,
                        color = inkColor.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

/**
 * Mosaic of up to 3 thumbnails with theme-defined shapes (circle, rounded rect, arch)
 * and placeholder fallback tint when fewer than 3 screenshots are present.
 */
@Composable
private fun CollectionMosaic(
    previewUris: List<String>,
    inkColor: Color,
    modifier: Modifier = Modifier
) {
    val shapes = listOf(
        CircleShape,
        MaterialTheme.shapes.medium,
        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy((-10).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 3) {
            val uri = previewUris.getOrNull(i)
            val shape = shapes[i]
            val width = if (i == 1) 46.dp else 56.dp
            val height = 56.dp
            val rotation = when (i) {
                1 -> -6f
                2 -> 6f
                else -> 0f
            }

            Surface(
                shape = shape,
                color = inkColor.copy(alpha = 0.12f),
                border = BorderStroke(2.5.dp, Color.White.copy(alpha = 0.9f)),
                modifier = Modifier
                    .size(width = width, height = height)
                    .rotate(rotation)
                    .clip(shape)
            ) {
                if (!uri.isNullOrEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
