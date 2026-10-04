package com.typorb.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Shape scale for the glass UI.
 *
 * The design system asks for 16–24dp corners; the overlay pill is deliberately tighter (14dp on a
 * 48dp square) so it still reads as a compact floating control rather than a card.
 */
object TyporbShapes {
    /** Chips, small buttons, inputs. */
    val Small = RoundedCornerShape(16.dp)

    /** Cards, panels, the floating bottom navigation bar. */
    val Medium = RoundedCornerShape(20.dp)

    /** Hero cards and sheets. */
    val Large = RoundedCornerShape(24.dp)

    /** The 48dp floating orb's corner radius (user-adjustable in Settings). */
    fun overlayCorner(radiusDp: Int) = RoundedCornerShape(radiusDp.dp)

    /** Pills and capsules. */
    val Capsule = RoundedCornerShape(percent = 50)
}

/**
 * Reusable gradient brushes.
 *
 * Gradients are created as top-level constants rather than per-composition so recomposition does not
 * rebuild the shader.
 */
object TyporbGradients {
    /** The signature cyan → violet accent ramp. */
    val Accent = listOf(TyporbPalette.NeonCyan, TyporbPalette.Violet)

    /** Horizontal variant for wide fills such as the engine toggle's selected track. */
    val AccentHorizontal = Brush.horizontalGradient(Accent)

    /** Vertical variant for the waveform bars. */
    val AccentVertical = Brush.verticalGradient(Accent)

    /** Diagonal sheen laid over glass cards to fake a light source. */
    fun cardSheen(alpha: Float = 0.06f): Brush = Brush.linearGradient(
        colors = listOf(
            TyporbPalette.TextPrimary.copy(alpha = alpha),
            TyporbPalette.TextPrimary.copy(alpha = 0f),
        ),
        start = androidx.compose.ui.geometry.Offset.Zero,
        end = androidx.compose.ui.geometry.Offset.Infinite,
    )
}