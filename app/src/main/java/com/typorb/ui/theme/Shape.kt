package com.typorb.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shape scale for the light UI.
 *
 * The design system asks for 16–24dp corners; the floating orb is tighter still (16dp on a 48dp
 * square) so it reads as a compact control rather than a card.
 */
object TyporbShapes {
    /** Inputs, chips, small buttons. */
    val Small = RoundedCornerShape(16.dp)

    /** Cards and panels. */
    val Medium = RoundedCornerShape(20.dp)

    /** Hero cards and sheets. */
    val Large = RoundedCornerShape(24.dp)

    /** The floating orb's corner radius (user-adjustable in Settings). */
    fun overlayCorner(radiusDp: Int) = RoundedCornerShape(radiusDp.dp)

    /** Pills, capsules, the navigation bar and badges. */
    val Capsule = RoundedCornerShape(percent = 50)

    /** The floating bottom bar's own radius — pill-shaped per the design system. */
    val Bar = RoundedCornerShape(28.dp)
}

/**
 * Elevation tokens.
 *
 * All three are soft and diffuse rather than dark, which is what separates an elevated white card
 * from the off-white canvas without needing a hard border.
 */
object TyporbElevation {
    /** Resting cards. */
    val Card = 3.dp

    /** The floating navigation bar, which must clearly hover above scrolling content. */
    val FloatingBar = 10.dp

    /** The floating overlay orb, which hovers over other apps entirely. */
    val Overlay = 8.dp
}

/** Reusable gradient brushes, built once rather than per composition. */
object TyporbGradients {
    /** The signature cobalt → indigo accent ramp. */
    val Accent = listOf(TyporbPalette.Cobalt, TyporbPalette.Indigo)

    /** Horizontal variant for wide fills such as the selected segment. */
    val AccentHorizontal = Brush.horizontalGradient(Accent)

    /** Vertical variant for the waveform bars. */
    val AccentVertical = Brush.verticalGradient(TyporbPalette.WaveformGradient)

    /** Soft indigo wash used behind selected chips and the onboarding orb. */
    fun wash(alpha: Float = 0.10f): Brush = Brush.horizontalGradient(
        Accent.map { it.copy(alpha = alpha) },
    )

    /** Faint indigo-tinted halo behind the primary orb. */
    fun halo(alpha: Float = 0.16f): Brush = Brush.radialGradient(
        listOf(
            TyporbPalette.Indigo.copy(alpha = alpha),
            Color.Transparent,
        ),
    )
}