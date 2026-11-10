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
    primaryContainer = ClayColors.TintGreen,
    onPrimaryContainer = Color(0xFF2F4A19),
    secondary = ClayColors.SurfaceSoft,
    onSecondary = ClayColors.Ink,
    secondaryContainer = ClayColors.TintGreen,
    onSecondaryContainer = Color(0xFF2F4A19),
    tertiary = ClayColors.BrandTeal,
    onTertiary = ClayColors.OnPrimary,
    tertiaryContainer = ClayColors.TintMint,
    onTertiaryContainer = Color(0xFF1E3D31),
    background = ClayColors.Canvas,
    onBackground = ClayColors.Ink,
    surface = ClayColors.SurfaceCard,
    onSurface = ClayColors.Ink,
    surfaceVariant = ClayColors.SurfaceSoft,
    onSurfaceVariant = ClayColors.Muted,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = ClayColors.Canvas,
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = ClayColors.SurfaceSoft,
    surfaceContainerHighest = ClayColors.SurfaceStrong,
    inverseSurface = ClayColors.SurfaceDark,
    inverseOnSurface = ClayColors.Canvas,
    inversePrimary = Color(0xFFA8CE86),
    outline = ClayColors.Hairline,
    outlineVariant = ClayColors.HairlineSoft,
    error = ClayColors.Error,
    onError = ClayColors.OnPrimary,
    errorContainer = Color(0xFFF7E4E0),
    onErrorContainer = Color(0xFF7A2C1E),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9CC673),
    onPrimary = Color(0xFF1B2A10),
    primaryContainer = Color(0xFF2C421C),
    onPrimaryContainer = Color(0xFFD6EABF),
    secondary = ClayColors.SurfaceDarkElevated,
    onSecondary = ClayColors.OnDark,
    secondaryContainer = Color(0xFF2A3520),
    onSecondaryContainer = Color(0xFFD6EABF),
    tertiary = Color(0xFF7FB68F),
    onTertiary = Color(0xFF0E2418),
    tertiaryContainer = Color(0xFF1C3A2A),
    onTertiaryContainer = Color(0xFFCFE9D9),
    background = ClayColors.SurfaceDark,
    onBackground = ClayColors.OnDark,
    surface = ClayColors.SurfaceDark,
    onSurface = ClayColors.OnDark,
    surfaceVariant = ClayColors.SurfaceDarkElevated,
    onSurfaceVariant = ClayColors.OnDarkSoft,
    surfaceContainerLowest = Color(0xFF0A0C06),
    surfaceContainerLow = Color(0xFF12140C),
    surfaceContainer = ClayColors.SurfaceDarkElevated,
    surfaceContainerHigh = Color(0xFF22271A),
    surfaceContainerHighest = Color(0xFF2B3121),
    inverseSurface = ClayColors.Canvas,
    inverseOnSurface = ClayColors.Ink,
    inversePrimary = ClayColors.Primary,
    outline = Color(0xFF3A4030),
    outlineVariant = Color(0xFF242A1B),
    error = Color(0xFFE08A78),
    onError = Color(0xFF3A1209),
    errorContainer = Color(0xFF4C1A12),
    onErrorContainer = Color(0xFFF6D4CC),
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
