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
    onPrimaryContainer = Color(0xFF11395C),
    secondary = ClayColors.SurfaceSoft,
    onSecondary = ClayColors.Ink,
    secondaryContainer = ClayColors.TintGreen,
    onSecondaryContainer = Color(0xFF11395C),
    tertiary = ClayColors.BrandTeal,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = ClayColors.TintMint,
    onTertiaryContainer = Color(0xFF123B33),
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
    inversePrimary = Color(0xFFA9D7FF),
    outline = ClayColors.Hairline,
    outlineVariant = ClayColors.HairlineSoft,
    error = ClayColors.Error,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF7E4E0),
    onErrorContainer = Color(0xFF7A2C1E),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7EC4FF),
    onPrimary = Color(0xFF08243A),
    primaryContainer = Color(0xFF1B3B54),
    onPrimaryContainer = Color(0xFFCFE8FF),
    secondary = ClayColors.SurfaceDarkElevated,
    onSecondary = ClayColors.OnDark,
    secondaryContainer = Color(0xFF1D3346),
    onSecondaryContainer = Color(0xFFCFE8FF),
    tertiary = Color(0xFF63B59A),
    onTertiary = Color(0xFF0A2620),
    tertiaryContainer = Color(0xFF163A31),
    onTertiaryContainer = Color(0xFFCBEBDF),
    background = ClayColors.SurfaceDark,
    onBackground = ClayColors.OnDark,
    surface = ClayColors.SurfaceDark,
    onSurface = ClayColors.OnDark,
    surfaceVariant = ClayColors.SurfaceDarkElevated,
    onSurfaceVariant = ClayColors.OnDarkSoft,
    surfaceContainerLowest = Color(0xFF0A0F13),
    surfaceContainerLow = Color(0xFF121A20),
    surfaceContainer = ClayColors.SurfaceDarkElevated,
    surfaceContainerHigh = Color(0xFF232D38),
    surfaceContainerHighest = Color(0xFF2C3743),
    inverseSurface = ClayColors.Canvas,
    inverseOnSurface = ClayColors.Ink,
    inversePrimary = ClayColors.Primary,
    outline = Color(0xFF39434E),
    outlineVariant = Color(0xFF232B34),
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
