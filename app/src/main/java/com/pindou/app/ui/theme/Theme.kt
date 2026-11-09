package com.pindou.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = ClayColors.Primary,
    onPrimary = ClayColors.OnPrimary,
    primaryContainer = Color(0xFFFFDCE7),
    onPrimaryContainer = Color(0xFF8A1839),
    secondary = ClayColors.BrandPink,
    onSecondary = ClayColors.OnPrimary,
    secondaryContainer = Color(0xFFFFE1EC),
    onSecondaryContainer = Color(0xFF8A1F4A),
    tertiary = ClayColors.BrandTeal,
    onTertiary = ClayColors.OnPrimary,
    tertiaryContainer = Color(0xFFCFF0E8),
    onTertiaryContainer = ClayColors.BrandTeal,
    background = ClayColors.Canvas,
    onBackground = ClayColors.Ink,
    surface = ClayColors.Canvas,
    onSurface = ClayColors.Ink,
    surfaceVariant = ClayColors.SurfaceCard,
    onSurfaceVariant = ClayColors.Body,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = ClayColors.SurfaceSoft,
    surfaceContainer = ClayColors.SurfaceSoft,
    surfaceContainerHigh = ClayColors.SurfaceCard,
    surfaceContainerHighest = ClayColors.SurfaceStrong,
    inverseSurface = ClayColors.SurfaceDark,
    inverseOnSurface = ClayColors.Canvas,
    inversePrimary = Color(0xFFFF6E9E),
    outline = ClayColors.MutedSoft,
    outlineVariant = ClayColors.Hairline,
    error = ClayColors.Error,
    onError = ClayColors.OnPrimary,
    errorContainer = Color(0xFFFDE2E1),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFF6E9E),
    onPrimary = Color(0xFF2B0A16),
    primaryContainer = Color(0xFF8A1839),
    onPrimaryContainer = Color(0xFFFFDCE7),
    secondary = Color(0xFF3FCFB4),
    onSecondary = ClayColors.OnDark,
    secondaryContainer = Color(0xFF4A1030),
    onSecondaryContainer = Color(0xFFFFB3CE),
    tertiary = Color(0xFFFFC25E),
    onTertiary = ClayColors.SurfaceDark,
    tertiaryContainer = Color(0xFF4D3A10),
    onTertiaryContainer = ClayColors.BrandMint,
    background = ClayColors.SurfaceDark,
    onBackground = ClayColors.OnDark,
    surface = ClayColors.SurfaceDark,
    onSurface = ClayColors.OnDark,
    surfaceVariant = ClayColors.SurfaceDarkElevated,
    onSurfaceVariant = ClayColors.OnDarkSoft,
    surfaceContainerLowest = Color(0xFF0C0C10),
    surfaceContainerLow = Color(0xFF191A20),
    surfaceContainer = ClayColors.SurfaceDarkElevated,
    surfaceContainerHigh = Color(0xFF24252D),
    surfaceContainerHighest = Color(0xFF2B2C35),
    inverseSurface = ClayColors.Canvas,
    inverseOnSurface = ClayColors.Ink,
    inversePrimary = ClayColors.Primary,
    outline = Color(0xFF5D5F6B),
    outlineVariant = Color(0xFF2E2F38),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0A08),
    errorContainer = Color(0xFF5C1616),
    onErrorContainer = Color(0xFFFFD4D0),
)

@Composable
fun PindouTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = ClayTypography,
        shapes = ClayShapes,
        content = content,
    )
}
