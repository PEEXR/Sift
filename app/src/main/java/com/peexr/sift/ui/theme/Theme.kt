package com.peexr.sift.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalSiftExtendedColors = staticCompositionLocalOf {
    LightExtendedColors
}

object SiftTheme {
    val extended: SiftExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSiftExtendedColors.current
}

private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = SiftViolet,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5D8FF),
    onPrimaryContainer = Color(0xFF23005C),
    secondary = Color(0xFF6B538C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9DEF8),
    onSecondaryContainer = SiftInk,
    tertiary = KeepLime,
    onTertiary = OnKeepLime,
    tertiaryContainer = Color(0xFFE4F7B5),
    onTertiaryContainer = OnKeepLime,
    error = RemoveCoral,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = SiftBackgroundLight,
    onBackground = SiftInk,
    surface = SiftSurfaceLight,
    onSurface = SiftInk,
    surfaceVariant = SiftSurfaceVariantLight,
    onSurfaceVariant = SiftOnSurfaceVariantLight,
    surfaceContainerLowest = SiftSurfaceContainerLowestLight,
    surfaceContainerLow = SiftSurfaceContainerLowLight,
    surfaceContainer = SiftSurfaceContainerLight,
    surfaceContainerHigh = SiftSurfaceContainerHighLight,
    surfaceContainerHighest = SiftSurfaceContainerHighestLight,
    outline = SiftOutlineLight,
    outlineVariant = SiftOutlineVariantLight,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    inversePrimary = SiftVioletDark
)

private val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = SiftVioletDark,
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFE5D8FF),
    secondary = Color(0xFFCBC2DB),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4358),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = KeepLimeDark,
    onTertiary = OnKeepLimeDark,
    tertiaryContainer = Color(0xFF3A4C00),
    onTertiaryContainer = Color(0xFFE4F7B5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = SiftBackgroundDark,
    onBackground = SiftInkDark,
    surface = SiftSurfaceDark,
    onSurface = SiftInkDark,
    surfaceVariant = SiftSurfaceVariantDark,
    onSurfaceVariant = SiftOnSurfaceVariantDark,
    surfaceContainerLowest = SiftSurfaceContainerLowestDark,
    surfaceContainerLow = SiftSurfaceContainerLowDark,
    surfaceContainer = SiftSurfaceContainerDark,
    surfaceContainerHigh = SiftSurfaceContainerHighDark,
    surfaceContainerHighest = SiftSurfaceContainerHighestDark,
    outline = SiftOutlineDark,
    outlineVariant = SiftOutlineVariantDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    inversePrimary = SiftViolet
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SiftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalSiftExtendedColors provides extendedColors
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = SiftShapes,
            typography = SiftTypography,
            content = content
        )
    }
}
