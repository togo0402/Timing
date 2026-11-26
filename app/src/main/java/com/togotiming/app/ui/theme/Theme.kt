package com.togotiming.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Timing v1.1 Fluid Glass 设计 token */
object TimingColors {
    val Blue = Color(0xFF3D8EFF)
    val BlueDeep = Color(0xFF2470D8)
    val BlueSoft = Color(0xFF6FB1FF)

    val LightBgTop = Color(0xFFF7F9FE)
    val LightBgBottom = Color(0xFFEEF3FC)
    val LightInk = Color(0xFF101828)
    val LightMuted = Color(0xFF667085)
    val LightGlass = Color(0xB8FFFFFF)
    val LightGlassSoft = Color(0x73FFFFFF)
    val LightBorder = Color(0xE6FFFFFF)
    val LightDivider = Color(0x12000000)

    val DarkBgTop = Color(0xFF0B111C)
    val DarkBgBottom = Color(0xFF0A0F18)
    val DarkInk = Color(0xFFEAF1FF)
    val DarkMuted = Color(0xFF8FA3C4)
    val DarkGlass = Color(0x1AFFFFFF)
    val DarkGlassSoft = Color(0x0AFFFFFF)
    val DarkBorder = Color(0x1FFFFFFF)
    val DarkDivider = Color(0x14FFFFFF)

    fun isDark(color: Color): Boolean = color.luminance() < 0.5f
}

private val LightScheme = lightColorScheme(
    primary = TimingColors.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF0A2B57),
    secondary = Color(0xFF6B7A94),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EEF8),
    onSecondaryContainer = Color(0xFF1B2A44),
    tertiary = Color(0xFF8E6BFF),
    background = TimingColors.LightBgTop,
    onBackground = TimingColors.LightInk,
    surface = Color.White,
    onSurface = TimingColors.LightInk,
    surfaceVariant = Color(0xFFEEF3FC),
    onSurfaceVariant = TimingColors.LightMuted,
    outline = Color(0xFFD5DDEA),
    error = Color(0xFFE5484D),
)

private val DarkScheme = darkColorScheme(
    primary = TimingColors.BlueSoft,
    onPrimary = Color(0xFF062A56),
    primaryContainer = Color(0xFF16325C),
    onPrimaryContainer = Color(0xFFD6E7FF),
    secondary = Color(0xFF9FB2D0),
    onSecondary = Color(0xFF13233A),
    secondaryContainer = Color(0xFF1A2436),
    onSecondaryContainer = Color(0xFFDCE8FF),
    tertiary = Color(0xFFB9A6FF),
    background = TimingColors.DarkBgTop,
    onBackground = TimingColors.DarkInk,
    surface = Color(0xFF121A29),
    onSurface = TimingColors.DarkInk,
    surfaceVariant = Color(0xFF1A2436),
    onSurfaceVariant = TimingColors.DarkMuted,
    outline = Color(0xFF2C3A52),
    error = Color(0xFFFF6B70),
)

@Composable
fun TimingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
