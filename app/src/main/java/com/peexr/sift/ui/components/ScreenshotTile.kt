package com.peexr.sift.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.ui.theme.SiftTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared screenshot thumbnail tile displaying an image cropped to aspect ratio 0.62
 * with medium rounded corners. Displays an optional check badge when [kept] is true.
 */
@Composable
fun ScreenshotTile(
    item: ScreenshotItem,
    onClick: (ScreenshotItem) -> Unit,
    modifier: Modifier = Modifier,
    kept: Boolean = false
) {
    val context = LocalContext.current
    val formattedDate = remember(item.dateAdded) {
        val instant = Instant.ofEpochSecond(item.dateAdded)
        val localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
        localDate.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.62f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onClick(item) }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.uri)
                .crossfade(true)
                .build(),
            contentDescription = "Screenshot from $formattedDate",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (kept) {
            Surface(
                shape = CircleShape,
                color = SiftTheme.extended.keepAccent,
                contentColor = SiftTheme.extended.onKeepAccent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Kept in this collection",
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
