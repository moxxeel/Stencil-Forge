package com.maddog.stencilforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AccentRed,
    onPrimary = PaperWhite,
    primaryContainer = AccentRedDark,
    onPrimaryContainer = PaperCream,
    secondary = OnSurfaceLight,
    onSecondary = InkBlack,
    secondaryContainer = SurfaceGray2,
    onSecondaryContainer = OnSurfaceLight,
    background = InkBlack,
    onBackground = PaperWhite,
    surface = SurfaceGray,
    onSurface = PaperWhite,
    surfaceVariant = SurfaceGray2,
    onSurfaceVariant = OnSurfaceMuted,
    error = Color(0xFFCF6679)
)

private val LightColorScheme = lightColorScheme(
    primary = AccentRedDark,
    onPrimary = PaperWhite,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = InkGray,
    onSecondary = PaperWhite,
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = InkBlack,
    background = PaperWhite,
    onBackground = InkBlack,
    surface = PaperCream,
    onSurface = InkBlack,
    surfaceVariant = Color(0xFFEAE4DE),
    onSurfaceVariant = Color(0xFF4A4540),
    error = AccentRed
)

@Composable
fun StencilForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
