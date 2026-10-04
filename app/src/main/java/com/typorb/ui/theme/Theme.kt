package com.typorb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TyporbColorScheme = lightColorScheme(
    primary = TyporbPalette.Cobalt,
    onPrimary = TyporbPalette.OnAccent,
    primaryContainer = TyporbPalette.Indigo,
    onPrimaryContainer = TyporbPalette.OnAccent,
    secondary = TyporbPalette.Indigo,
    onSecondary = TyporbPalette.OnAccent,
    tertiary = TyporbPalette.Emerald,
    onTertiary = TyporbPalette.OnAccent,
    background = TyporbPalette.Background,
    onBackground = TyporbPalette.TextPrimary,
    surface = TyporbPalette.Surface,
    onSurface = TyporbPalette.TextPrimary,
    surfaceVariant = TyporbPalette.SurfaceSunken,
    onSurfaceVariant = TyporbPalette.TextSecondary,
    surfaceContainerHighest = TyporbPalette.SurfaceSunken,
    error = TyporbPalette.Danger,
    onError = Color.White,
    errorContainer = TyporbPalette.DangerTint,
    onErrorContainer = TyporbPalette.Danger,
    outline = TyporbPalette.BorderStrong,
    outlineVariant = TyporbPalette.Border,
)

/**
 * Typorb is light-only by design: the app shell and the floating overlay share one identity, so
 * there is no dark variant to keep in sync.
 */
@Composable
fun TyporbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TyporbColorScheme,
        typography = TyporbTypography,
        content = content,
    )
}