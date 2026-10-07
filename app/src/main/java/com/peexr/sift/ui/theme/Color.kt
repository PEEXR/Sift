package com.peexr.sift.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Brand / Primary
val SiftViolet = Color(0xFF6B3FD4)
val SiftInk = Color(0xFF1E1530)
val SiftDisplayInk = Color(0xFF2A1450)

// Accents
val KeepLime = Color(0xFFC6F24E)
val OnKeepLime = Color(0xFF1E2B00)
val RemoveCoral = Color(0xFFFF6B57)
val OnRemoveCoral = Color(0xFF3A0A00)

// Surfaces & Containers (Light)
val SiftBackgroundLight = Color(0xFFFBF7FF)
val SiftSurfaceLight = Color(0xFFFBF7FF)
val SiftSurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SiftSurfaceContainerLowLight = Color(0xFFF8F1FF)
val SiftSurfaceContainerLight = Color(0xFFF3ECFF)
val SiftSurfaceContainerHighLight = Color(0xFFECE3FA)
val SiftSurfaceContainerHighestLight = Color(0xFFE9DEF8)
val SiftSurfaceVariantLight = Color(0xFFE9DEF8)
val SiftOnSurfaceVariantLight = Color(0xFF5B5470)
val SiftOutlineLight = Color(0xFF8E86A6)
val SiftOutlineVariantLight = Color(0xFFCDBDF5)

// Inverse
val InverseSurface = Color(0xFF2A1450)
val InverseOnSurface = Color(0xFFFFFFFF)

// Dark Theme Brand & Roles
val SiftVioletDark = Color(0xFFD0BCFF)
val SiftInkDark = Color(0xFFEDE0F7)
val SiftDisplayInkDark = Color(0xFFE5D8FF)

val KeepLimeDark = Color(0xFFC6F24E)
val OnKeepLimeDark = Color(0xFF1E2B00)
val RemoveCoralDark = Color(0xFFFF6B57)
val OnRemoveCoralDark = Color(0xFF3A0A00)

val SiftBackgroundDark = Color(0xFF140B24)
val SiftSurfaceDark = Color(0xFF140B24)
val SiftSurfaceContainerLowestDark = Color(0xFF0F071C)
val SiftSurfaceContainerLowDark = Color(0xFF1A112B)
val SiftSurfaceContainerDark = Color(0xFF1E1530)
val SiftSurfaceContainerHighDark = Color(0xFF281D3C)
val SiftSurfaceContainerHighestDark = Color(0xFF33274A)
val SiftSurfaceVariantDark = Color(0xFF49454F)
val SiftOnSurfaceVariantDark = Color(0xFFCAC4D0)
val SiftOutlineDark = Color(0xFF958E9E)
val SiftOutlineVariantDark = Color(0xFF4A4453)

val InverseSurfaceDark = Color(0xFFEDE0F7)
val InverseOnSurfaceDark = Color(0xFF140B24)

// Six collection tonal pairs (container, ink) in spec order:
// pink, lilac, sky, lime, peach, mint
val CollectionPalette: List<Pair<Color, Color>> = listOf(
    Pair(Color(0xFFFFD8EC), Color(0xFF3E0A2A)), // pink
    Pair(Color(0xFFE5D8FF), Color(0xFF23005C)), // lilac
    Pair(Color(0xFFCFE5FF), Color(0xFF00264D)), // sky
    Pair(Color(0xFFE4F7B5), Color(0xFF1E2B00)), // lime
    Pair(Color(0xFFFFDCC8), Color(0xFF3A1500)), // peach
    Pair(Color(0xFFC9F2DE), Color(0xFF00311F))  // mint
)

@Immutable
data class SiftExtendedColors(
    val displayInk: Color,
    val keepAccent: Color,
    val onKeepAccent: Color,
    val removeAccent: Color,
    val onRemoveAccent: Color,
    val inverseSurface: Color = InverseSurface,
    val onInverseSurface: Color = InverseOnSurface,
    val collectionPalette: List<Pair<Color, Color>> = CollectionPalette
)

val LightExtendedColors = SiftExtendedColors(
    displayInk = SiftDisplayInk,
    keepAccent = KeepLime,
    onKeepAccent = OnKeepLime,
    removeAccent = RemoveCoral,
    onRemoveAccent = OnRemoveCoral,
    inverseSurface = InverseSurface,
    onInverseSurface = InverseOnSurface,
    collectionPalette = CollectionPalette
)

val DarkExtendedColors = SiftExtendedColors(
    displayInk = SiftDisplayInkDark,
    keepAccent = KeepLimeDark,
    onKeepAccent = OnKeepLimeDark,
    removeAccent = RemoveCoralDark,
    onRemoveAccent = OnRemoveCoralDark,
    inverseSurface = InverseSurfaceDark,
    onInverseSurface = InverseOnSurfaceDark,
    collectionPalette = CollectionPalette
)

// Legacy accent aliases for compatibility
val MemeAccent = KeepLime
val QuoteAccent = Color(0xFFFF9100)
val ReceiptAccent = Color(0xFF2979FF)
val JunkAccent = RemoveCoral
