package com.example.planya.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = ChipBg,
    onPrimaryContainer = IndigoDark,
    secondary = Orange,
    onSecondary = Color.White,
    secondaryContainer = ChipBg,
    onSecondaryContainer = IndigoDark,
    tertiary = BadgeBrown,
    onTertiary = Color.White,
    background = ScreenBg,
    onBackground = TextPrimary,
    surface = Color.White,
    surfaceTint = Color.Transparent,
    surfaceVariant = ScreenBg,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = ScreenBg,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = ScreenBg,
    onSurface = TextPrimary,
    onSurfaceVariant = TextMuted,
    outline = OutlineSoft,
    outlineVariant = OutlineSoft
)

@Composable
fun PlanesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = Typography, content = content)
}
