package com.peexr.sift.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.ui.graphics.vector.ImageVector

sealed class SiftDestination(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Collections : SiftDestination(
        route = "collections",
        title = "Collections",
        icon = Icons.Rounded.FolderSpecial
    )

    data object Cleanup : SiftDestination(
        route = "cleanup",
        title = "Cleanup",
        icon = Icons.Rounded.AutoDelete
    )
}
