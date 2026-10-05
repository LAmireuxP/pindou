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
    primaryContainer = ClayColors.SurfaceStrong,
    onPrimaryContainer = ClayColors.Ink,
    secondary = ClayColors.BrandPink,
    onSecondary = ClayColors.OnPrimary,
    secondaryContainer = Color(0xFFFFD9E6),
    onSecondaryContainer = Color(0xFF8A1F4A),
    tertiary = ClayColors.BrandTeal,
    onTertiary = ClayColors.OnPrimary,
    tertiaryContainer = Color(0xFFD2E4E0),
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
    inversePrimary = ClayColors.BrandPeach,
    outline = ClayColors.Muted,
    outlineVariant = ClayColors.Hairline,
    error = ClayColors.Error,
    onError = ClayColors.OnPrimary,
    errorContainer = Color(0xFFFDE2E1),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColorScheme = darkColorScheme(
    primary = ClayColors.BrandPeach,
    onPrimary = ClayColors.SurfaceDark,
    primaryContainer = ClayColors.SurfaceDarkElevated,
    onPrimaryContainer = ClayColors.BrandPeach,
    secondary = ClayColors.BrandPink,
    onSecondary = ClayColors.OnDark,
    secondaryContainer = Color(0xFF4A1030),
    onSecondaryContainer = Color(0xFFFFB3CE),
    tertiary = ClayColors.BrandMint,
    onTertiary = ClayColors.SurfaceDark,
    tertiaryContainer = Color(0xFF15332C),
    onTertiaryContainer = ClayColors.BrandMint,
    background = ClayColors.SurfaceDark,
    onBackground = ClayColors.OnDark,
    surface = ClayColors.SurfaceDark,
    onSurface = ClayColors.OnDark,
    surfaceVariant = ClayColors.SurfaceDarkElevated,
    onSurfaceVariant = ClayColors.OnDarkSoft,
    surfaceContainerLowest = Color(0xFF050D0D),
    surfaceContainerLow = ClayColors.SurfaceDark,
    surfaceContainer = ClayColors.SurfaceDarkElevated,
    surfaceContainerHigh = Color(0xFF243434),
    surfaceContainerHighest = Color(0xFF2E3E3E),
    inverseSurface = ClayColors.Canvas,
    inverseOnSurface = ClayColors.Ink,
    inversePrimary = ClayColors.Primary,
    outline = Color(0xFF4A5A5A),
    outlineVariant = ClayColors.SurfaceDarkElevated,
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
