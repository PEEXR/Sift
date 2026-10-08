package com.peexr.sift.ui.review

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.ui.theme.BricolageGrotesque
import com.peexr.sift.ui.theme.SiftTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Controller to programmatically trigger swipe actions from external buttons.
 */
class SwipeStackState {
    internal var triggerAction: ((isKeep: Boolean) -> Unit)? = null

    fun swipeRight() {
        triggerAction?.invoke(true)
    }

    fun swipeLeft() {
        triggerAction?.invoke(false)
    }
}

@Composable
fun rememberSwipeStackState(): SwipeStackState = remember { SwipeStackState() }

/**
 * Responsive swipe card stack displaying the top 3 cards with Material 3 Expressive spring physics.
 */
@Composable
fun SwipeCardStack(
    stack: List<ReviewCard>,
    index: Int,
    isSearchMode: Boolean,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
    onOpenScreenshot: (ScreenshotItem) -> Unit,
    onDragFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    swipeState: SwipeStackState = rememberSwipeStackState()
) {
    val visibleCards = remember(stack, index) {
        stack.drop(index).take(3)
    }

    if (visibleCards.isEmpty()) {
        Box(modifier = modifier)
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(500.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        // Render from back (depth 2) to front (depth 0)
        for (i in visibleCards.indices.reversed()) {
            val card = visibleCards[i]
            val depth = i

            if (depth == 0) {
                InteractiveTopCard(
                    card = card,
                    isSearchMode = isSearchMode,
                    onKeep = onKeep,
                    onRemove = onRemove,
                    onOpenScreenshot = onOpenScreenshot,
                    onDragFractionChange = onDragFractionChange,
                    swipeState = swipeState
                )
            } else {
                PeekBackgroundCard(
                    card = card,
                    depth = depth
                )
            }
        }
    }
}

@Composable
private fun PeekBackgroundCard(
    card: ReviewCard,
    depth: Int,
    modifier: Modifier = Modifier
) {
    val scale = if (depth == 1) 0.95f else 0.90f
    val yOffsetDp = if (depth == 1) 18.dp else 36.dp
    val rotation = if (depth == 1) -3f else 4f

    Surface(
        shape = RoundedCornerShape(36.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxSize()
            .offset(y = yOffsetDp)
            .scale(scale)
            .rotate(rotation)
    ) {
        CardContentLayout(
            card = card,
            interactive = false
        )
    }
}

@Composable
private fun InteractiveTopCard(
    card: ReviewCard,
    isSearchMode: Boolean,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
    onOpenScreenshot: (ScreenshotItem) -> Unit,
    onDragFractionChange: (Float) -> Unit,
    swipeState: SwipeStackState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val thresholdPx = with(density) { 110.dp.toPx() }

    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var isDragging by remember { mutableFloatStateOf(0f) }

    // Register programmatic button triggers
    LaunchedEffect(swipeState, card.item.id) {
        swipeState.triggerAction = { isKeep ->
            scope.launch {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val targetX = if (isKeep) screenWidthPx * 1.5f else -screenWidthPx * 1.5f
                offsetX.animateTo(targetX, tween(240))
                onDragFractionChange(0f)
                if (isKeep) onKeep() else onRemove()
                offsetX.snapTo(0f)
                offsetY.snapTo(0f)
            }
        }
    }

    val dragDx = offsetX.value
    val rotation = (dragDx / 16f).coerceIn(-25f, 25f)
    val keepStampAlpha = (dragDx / thresholdPx).coerceIn(0f, 1f)
    val removeStampAlpha = (-dragDx / thresholdPx).coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(36.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 14.dp,
        modifier = modifier
            .testTag("swipe_top_card")
            .fillMaxSize()
            .offset { IntOffset(dragDx.roundToInt(), offsetY.value.roundToInt()) }
            .rotate(rotation)
            .pointerInput(card.item.id) {
                detectTapGestures(
                    onTap = {
                        if (abs(offsetX.value) < 10f) {
                            onOpenScreenshot(card.item)
                        }
                    }
                )
            }
            .pointerInput(card.item.id) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = 1f
                    },
                    onDragEnd = {
                        isDragging = 0f
                        scope.launch {
                            if (offsetX.value > thresholdPx) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                offsetX.animateTo(screenWidthPx * 1.5f, tween(200))
                                onDragFractionChange(0f)
                                onKeep()
                                offsetX.snapTo(0f)
                                offsetY.snapTo(0f)
                            } else if (offsetX.value < -thresholdPx) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                offsetX.animateTo(-screenWidthPx * 1.5f, tween(200))
                                onDragFractionChange(0f)
                                onRemove()
                                offsetX.snapTo(0f)
                                offsetY.snapTo(0f)
                            } else {
                                onDragFractionChange(0f)
                                launch {
                                    offsetX.animateTo(
                                        0f,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                                    )
                                }
                                launch {
                                    offsetY.animateTo(
                                        0f,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                                    )
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        isDragging = 0f
                        onDragFractionChange(0f)
                        scope.launch {
                            offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            offsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            offsetX.snapTo(offsetX.value + dragAmount.x)
                            offsetY.snapTo(offsetY.value + dragAmount.y * 0.25f)
                            val fraction = (offsetX.value / 160f).coerceIn(-1f, 1f)
                            onDragFractionChange(fraction)
                        }
                    }
                )
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CardContentLayout(
                card = card,
                interactive = true
            )

            // KEEP stamp (top-left)
            if (keepStampAlpha > 0.01f) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SiftTheme.extended.keepAccent.copy(alpha = 0.92f),
                    border = BorderStroke(4.dp, SiftTheme.extended.onKeepAccent),
                    modifier = Modifier
                        .padding(top = 30.dp, start = 24.dp)
                        .rotate(-12f)
                        .graphicsLayer { alpha = keepStampAlpha }
                ) {
                    Text(
                        text = "KEEP",
                        fontFamily = BricolageGrotesque,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 30.sp,
                        color = SiftTheme.extended.onKeepAccent,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // REMOVE or DELETE stamp (top-right)
            if (removeStampAlpha > 0.01f) {
                val stampText = if (isSearchMode) "DELETE" else "REMOVE"
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SiftTheme.extended.removeAccent.copy(alpha = 0.95f),
                    border = BorderStroke(4.dp, SiftTheme.extended.onRemoveAccent),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 30.dp, end = 24.dp)
                        .rotate(12f)
                        .graphicsLayer { alpha = removeStampAlpha }
                ) {
                    Text(
                        text = stampText,
                        fontFamily = BricolageGrotesque,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 30.sp,
                        color = SiftTheme.extended.onRemoveAccent,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CardContentLayout(
    card: ReviewCard,
    interactive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val formattedDate = remember(card.item.dateAdded) {
        try {
            val instant = Instant.ofEpochSecond(card.item.dateAdded)
            val localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            localDate.format(DateTimeFormatter.ofPattern("MMMM d", Locale.US))
        } catch (_: Exception) {
            ""
        }
    }

    var aspect by remember(card.item.uri) { mutableStateOf<Float?>(null) }
    val imageMode = cardImageMode(aspect)
    val snippet = remember(card.item.ocrText) { ocrSnippet(card.item.ocrText) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Screenshot image area: fixed frame, weight(1f), letterboxed backdrop + Fit/Tall foreground
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            // 1. Backdrop: blurred and dimmed so letterbox space never looks like empty black bars
            val backdropRequest = remember(card.item.uri) {
                ImageRequest.Builder(context)
                    .data(card.item.uri)
                    .crossfade(true)
                    .size(Size(160, 240))
                    .build()
            }
            AsyncImage(
                model = backdropRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp)
            )

            // Dim overlay: scrim at ~35% alpha
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
            )

            // 2 & 3. Foreground: top card loads full-size image (1080x1440), peeking cards keep a cheap load (160x240)
            val foregroundSize = if (interactive) Size(1080, 1440) else Size(160, 240)
            val foregroundRequest = remember(card.item.uri, interactive) {
                ImageRequest.Builder(context)
                    .data(card.item.uri)
                    .crossfade(true)
                    .size(foregroundSize)
                    .build()
            }

            when (imageMode) {
                CardImageMode.FIT -> {
                    AsyncImage(
                        model = foregroundRequest,
                        contentDescription = "Screenshot from $formattedDate",
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.Center,
                        onSuccess = { state ->
                            aspect = state.painter.intrinsicSize.run {
                                if (height > 0f) width / height else null
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                }

                CardImageMode.TALL_TOP -> {
                    AsyncImage(
                        model = foregroundRequest,
                        contentDescription = "Screenshot from $formattedDate",
                        contentScale = ContentScale.FillWidth,
                        alignment = Alignment.TopCenter,
                        onSuccess = { state ->
                            aspect = state.painter.intrinsicSize.run {
                                if (height > 0f) width / height else null
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(26.dp))
                    )

                    // 56 dp bottom fade to backdrop colour for very tall captures
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.surfaceContainer
                                    )
                                )
                            )
                    )
                }
            }
        }

        // Bottom info row: Date on left, Match confidence pill on right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = if (snippet.isNotBlank()) 10.dp else 12.dp,
                    bottom = if (snippet.isNotBlank()) 4.dp else 12.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formattedDate,
                style = MaterialTheme.typography.headlineSmall,
                color = SiftTheme.extended.displayInk
            )

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(32.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 14.dp)
                ) {
                    Text(
                        text = card.confidenceLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // OCR snippet line under the date row (only shown when not blank)
        if (snippet.isNotBlank()) {
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp)
            )
        }
    }
}
