package com.dlidar.mobile.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material 3 with high-contrast schemes of its own (no dynamic color), because the scanner is
 * used outdoors and in the dark. The dark scheme is for dark places: a true black background
 * (an OLED screen stays dark around the content), white text and bright accents, with
 * outlines strong enough to see controls at low brightness. The light scheme keeps the same
 * contrast in daylight. Both follow the phone's dark mode.
 */
@Composable
fun DLidarTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatusColors else LightStatusColors) {
        MaterialTheme(colorScheme = if (dark) DarkHighContrast else LightHighContrast, content = content)
    }
}

/** Colors for states the Material roles do not name. Each has at least 7:1 against the background. */
@Immutable
data class StatusColors(
    val connected: Color,
    val connecting: Color,
    val warning: Color,
)

object DLidarColors {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

private val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }

private val DarkStatusColors = StatusColors(
    connected = Color(0xFF5BE584),
    connecting = Color(0xFFFFCB45),
    warning = Color(0xFFFFCB45),
)

private val LightStatusColors = StatusColors(
    connected = Color(0xFF0B6B2B),
    connecting = Color(0xFF7A4F00),
    warning = Color(0xFF7A4F00),
)

private val DarkHighContrast: ColorScheme = darkColorScheme(
    primary = Color(0xFFA6CBFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2B5DA6),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF0B3E8A),
    secondary = Color(0xFFC8D6EC),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF3A4B62),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFFCB45),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF5E4300),
    onTertiaryContainer = Color(0xFFFFFFFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF262C35),
    onSurfaceVariant = Color(0xFFE2E8F2),
    surfaceTint = Color(0xFFA6CBFF),
    inverseSurface = Color(0xFFF0F3F8),
    inverseOnSurface = Color(0xFF000000),
    outline = Color(0xFFAAB4C3),
    outlineVariant = Color(0xFF6E7887),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2E343E),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0D1015),
    surfaceContainer = Color(0xFF14181E),
    surfaceContainerHigh = Color(0xFF1C2129),
    surfaceContainerHighest = Color(0xFF242A33),
)

private val LightHighContrast: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B3E8A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1450AE),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFA6CBFF),
    secondary = Color(0xFF26364C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF3A4B62),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFF5E4300),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF7A5700),
    onTertiaryContainer = Color(0xFFFFFFFF),
    error = Color(0xFF8C1D18),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFDDE3EC),
    onSurfaceVariant = Color(0xFF1A212B),
    surfaceTint = Color(0xFF0B3E8A),
    inverseSurface = Color(0xFF1A1E24),
    inverseOnSurface = Color(0xFFFFFFFF),
    outline = Color(0xFF2B3440),
    outlineVariant = Color(0xFF4D5765),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD5DAE2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5F9),
    surfaceContainer = Color(0xFFEDF0F5),
    surfaceContainerHigh = Color(0xFFE6EAF0),
    surfaceContainerHighest = Color(0xFFDFE4EB),
)
