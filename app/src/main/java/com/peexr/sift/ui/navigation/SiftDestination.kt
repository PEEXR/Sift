package com.peexr.sift.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Style
import androidx.compose.ui.graphics.vector.ImageVector

sealed class SiftDestination(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Library : SiftDestination(
        route = "library",
        title = "Library",
        icon = Icons.Rounded.GridView
    )

    data object Collections : SiftDestination(
        route = "collections",
        title = "Collections",
        icon = Icons.Rounded.FolderSpecial
    )

    data object Review : SiftDestination(
        route = "review",
        title = "Review",
        icon = Icons.Rounded.Style
    )
}
