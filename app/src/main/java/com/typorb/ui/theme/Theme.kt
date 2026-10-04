package com.typorb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TyporbColorScheme = darkColorScheme(
    primary = TyporbPalette.NeonCyan,
    onPrimary = TyporbPalette.Background,
    secondary = TyporbPalette.Violet,
    onSecondary = TyporbPalette.TextPrimary,
    background = TyporbPalette.Background,
    onBackground = TyporbPalette.TextPrimary,
    surface = TyporbPalette.Surface,
    onSurface = TyporbPalette.TextPrimary,
    surfaceVariant = TyporbPalette.SurfaceElevated,
    onSurfaceVariant = TyporbPalette.TextSecondary,
    error = TyporbPalette.Danger,
    onError = TyporbPalette.Background,
    outline = TyporbPalette.TextMuted,
)

/**
 * Typorb is dark-only by design — the pill and the dashboard share one identity, so there is no
 * light variant to keep in sync.
 */
@Composable
fun TyporbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TyporbColorScheme,
        typography = TyporbTypography,
        content = content,
    )
}