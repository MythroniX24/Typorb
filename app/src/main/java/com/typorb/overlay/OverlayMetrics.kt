package com.typorb.overlay

import com.typorb.model.OverlayUiState
import com.typorb.service.KeyboardGeometry

/**
 * The pill's own size per state, and the overlay window bounds that follow from it.
 *
 * Pure — no Android types — for two reasons. The window is sized *outside* Compose, so a state change
 * has to move a real `WindowManager.LayoutParams`, and that is exactly the kind of arithmetic that
 * silently clips the orb when it is wrong. And the morph between two states is a plain interpolation,
 * so it can be asserted on the JVM instead of only being seen on a phone.
 *
 * The window is deliberately larger than the pill by [SHADOW_PADDING_DP] on every side: a window
 * surface is clipped to its own bounds, so a pill-sized window would cut the soft ambient shadow off
 * at the edges.
 */
object OverlayMetrics {

    /** Height of every capsule state; the idle orb is square. */
    const val PILL_HEIGHT_DP = 48

    /**
     * Inflates the overlay window beyond the pill on every side.
     *
     * 14dp covers the 8dp elevation plus its blur radius. It is also why the window follows the state
     * at all: idle, recording, processing and failed are four different rectangles, and the pill is
     * drawn to fill whichever one the window currently is.
     */
    const val SHADOW_PADDING_DP = 14

    const val RECORDING_WIDTH_DP = 160
    const val PROCESSING_WIDTH_DP = 190

    /** Narrowest failure capsule; the message widens it from here — see [errorWidthDp]. */
    const val ERROR_WIDTH_DP = 220

    /** Widest failure capsule, leaving the standard 16dp margin on a 360dp-wide screen. */
    const val MAX_ERROR_WIDTH_DP = 320

    /** Rough advance width of one character of the 13sp capsule label, in dp. */
    private const val CHARACTER_WIDTH_DP = 6.6f

    /** Dot, spacer and the capsule's own horizontal padding, in dp. */
    private const val ERROR_CHROME_DP = 52f

    /** Pill size in dp for [state]; only [OverlayUiState.Idle] is user-resizable. */
    fun pillSizeDp(state: OverlayUiState, idleSizeDp: Int): Pair<Int, Int> = when (state) {
        is OverlayUiState.Idle -> idleSizeDp to idleSizeDp
        is OverlayUiState.Recording -> RECORDING_WIDTH_DP to PILL_HEIGHT_DP
        is OverlayUiState.Processing -> PROCESSING_WIDTH_DP to PILL_HEIGHT_DP
        is OverlayUiState.Failed -> errorWidthDp(state.message) to PILL_HEIGHT_DP
    }

    /**
     * Width a failure capsule needs to show [message] on one line, clamped to a sane range.
     *
     * A fixed width was actively harmful here: the failure text is the only thing a user gets to read
     * when a dictation does not land, and errors such as Groq's own messages are far longer than the
     * 220dp that used to be hard-coded — they were cut off mid-sentence, which reads as a broken UI.
     * The window is animated between sizes, so a wider error also morphs open rather than appearing.
     */
    fun errorWidthDp(message: String): Int =
        (message.length * CHARACTER_WIDTH_DP + ERROR_CHROME_DP)
            .toInt()
            .coerceIn(ERROR_WIDTH_DP, MAX_ERROR_WIDTH_DP)

    /** A rectangle for the overlay window: pill plus shadow padding, positioned on screen. */
    data class Window(val width: Int, val height: Int, val x: Int, val y: Int) {

        /** Moves linearly from [from] to [to]; [fraction] is clamped to `0f..1f`. */
        companion object {
            fun lerp(from: Window, to: Window, fraction: Float): Window {
                val t = fraction.coerceIn(0f, 1f)
                return Window(
                    width = interpolate(from.width, to.width, t),
                    height = interpolate(from.height, to.height, t),
                    x = interpolate(from.x, to.x, t),
                    y = interpolate(from.y, to.y, t),
                )
            }

            private fun interpolate(from: Int, to: Int, fraction: Float): Int =
                (from + (to - from) * fraction).toInt()
        }
    }

    /**
     * Where the window belongs for [state] right now.
     *
     * The window is grown back by the padding on both axes and moved back by it, so the *pill* keeps
     * the position [KeyboardGeometry] computed — 16dp above the keyboard, 16dp from the right edge.
     */
    fun windowFor(
        state: OverlayUiState,
        idleSizeDp: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        keyboardHeightPx: Int,
        density: Float,
    ): Window {
        val (pillWidthDp, pillHeightDp) = pillSizeDp(state, idleSizeDp)
        val margin = dp(KeyboardGeometry.MARGIN_DP, density)
        // A capsule — a failure message especially, since it grows with its text — can never be wider
        // than the space between the two 16dp margins, whatever the screen reports.
        val pillWidthPx = dp(pillWidthDp, density)
            .coerceAtMost((screenWidthPx - margin * 2).coerceAtLeast(1))
        val pillHeightPx = dp(pillHeightDp, density)

        val (pillX, pillY) = KeyboardGeometry.pillTopLeft(
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            keyboardHeightPx = keyboardHeightPx,
            pillWidthPx = pillWidthPx,
            pillHeightPx = pillHeightPx,
            density = density,
        )
        val padding = dp(SHADOW_PADDING_DP, density)

        return Window(
            width = pillWidthPx + padding * 2,
            height = pillHeightPx + padding * 2,
            // Clamped rather than negative: with a very tall IME the pill's own top can be at the
            // screen edge, and subtracting the shadow padding would otherwise push the window
            // off-screen and clip the top of the orb.
            x = (pillX - padding).coerceAtLeast(0),
            y = (pillY - padding).coerceAtLeast(0),
        )
    }

    /** dp → px, matching the rounding `WindowManager.LayoutParams` has always been given here. */
    fun dp(value: Int, density: Float): Int = (value * density).toInt()
}
