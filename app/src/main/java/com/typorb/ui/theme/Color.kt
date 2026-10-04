package com.typorb.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Typorb's dark palette: a pitch-black canvas with neon cyan and electric violet accents, and
 * translucent "glass" surfaces.
 *
 * Hex values are fixed by the design system so the app shell and the floating overlay always read as
 * one product.
 */
object TyporbPalette {
    /** #070709 — the app's pitch-black background. */
    val Background = Color(0xFF070709)

    /** Frosted glassmorphism fill: #13131A at 60% opacity. */
    val Glass = Color(0x9913131A)

    /** Slightly denser glass for cards that sit on top of other glass. */
    val GlassElevated = Color(0xB313131A)

    /** #2A2A38 — the 1dp hairline that separates glass from the background. */
    val GlassBorder = Color(0xFF2A2A38)

    /** Slightly lifted black used where a solid (non-translucent) surface is needed. */
    val Surface = Color(0xFF0D0D12)
    val SurfaceElevated = Color(0xFF14141C)

    /** Primary accents. */
    val NeonCyan = Color(0xFF00F2FE)
    val Violet = Color(0xFF9B51E0)

    /** Text. High-contrast crisp white, muted slate for secondary copy. */
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8E8E9F)
    val TextMuted = Color(0xFF5A5F73)

    /** Feedback. */
    val Danger = Color(0xFFFF5470)
    val Success = Color(0xFF2BE8A6)
    val Warning = Color(0xFFFFC24B)

    /** Live waveform and accent fills use the cyan → violet ramp. */
    val WaveformGradient = listOf(NeonCyan, Violet)
}