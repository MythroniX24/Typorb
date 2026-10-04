package com.typorb.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Typorb's futuristic dark palette: a pitch-black canvas with neon cyan and violet accents, plus
 * translucent "glass" surfaces used by the floating pill.
 */
object TyporbPalette {
    /** #070709 — the app's pitch-black background. */
    val Background = Color(0xFF070709)

    /** Slightly lifted black used for cards. */
    val Surface = Color(0xFF0D0D12)
    val SurfaceElevated = Color(0xFF14141C)

    /** Frosted glass fill for the floating pill (alpha carries the blur illusion). */
    val Glass = Color(0xB3171722)
    val GlassBorder = Color(0x1FFFFFFF)

    /** Neon accents. */
    val NeonCyan = Color(0xFF00F2FE)
    val Violet = Color(0xFF9B51E0)

    /** Text. */
    val TextPrimary = Color(0xFFEDEFF7)
    val TextSecondary = Color(0xFF8C91A6)
    val TextMuted = Color(0xFF5A5F73)

    /** Feedback. */
    val Danger = Color(0xFFFF5470)
    val Success = Color(0xFF2BE8A6)

    /** Live waveform uses the cyan → violet ramp. */
    val WaveformGradient = listOf(NeonCyan, Violet)
}