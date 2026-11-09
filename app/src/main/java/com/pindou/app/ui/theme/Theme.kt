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
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E40AF),
    secondary = ClayColors.SurfaceSoft,
    onSecondary = ClayColors.Ink,
    secondaryContainer = Color(0xFFEFF6FF),
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = ClayColors.BrandTeal,
    onTertiary = ClayColors.OnPrimary,
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = ClayColors.BrandTeal,
    background = ClayColors.Canvas,
    onBackground = ClayColors.Ink,
    surface = ClayColors.SurfaceCard,
    onSurface = ClayColors.Ink,
    surfaceVariant = ClayColors.SurfaceSoft,
    onSurfaceVariant = ClayColors.Muted,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = ClayColors.SurfaceSoft,
    surfaceContainerHighest = ClayColors.SurfaceStrong,
    inverseSurface = ClayColors.SurfaceDark,
    inverseOnSurface = ClayColors.Canvas,
    inversePrimary = Color(0xFF93C5FD),
    outline = ClayColors.Hairline,
    outlineVariant = ClayColors.HairlineSoft,
    error = ClayColors.Error,
    onError = ClayColors.OnPrimary,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColorScheme = darkColorScheme(
    primary = ClayColors.BrandBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF172554),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF27272A),
    onSecondary = ClayColors.OnDark,
    secondaryContainer = Color(0xFF1E3A8A),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = Color(0xFF2DD4BF),
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = ClayColors.SurfaceDark,
    onBackground = ClayColors.OnDark,
    surface = ClayColors.SurfaceDark,
    onSurface = ClayColors.OnDark,
    surfaceVariant = ClayColors.SurfaceDarkElevated,
    onSurfaceVariant = ClayColors.OnDarkSoft,
    surfaceContainerLowest = Color(0xFF050507),
    surfaceContainerLow = Color(0xFF0B0B0F),
    surfaceContainer = ClayColors.SurfaceDarkElevated,
    surfaceContainerHigh = Color(0xFF1F1F23),
    surfaceContainerHighest = Color(0xFF27272A),
    inverseSurface = ClayColors.Canvas,
    inverseOnSurface = ClayColors.Ink,
    inversePrimary = ClayColors.Primary,
    outline = Color(0xFF3F3F46),
    outlineVariant = Color(0xFF27272A),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF5C1616),
    onErrorContainer = Color(0xFFFECACA),
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
