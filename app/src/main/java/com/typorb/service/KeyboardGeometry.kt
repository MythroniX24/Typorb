package com.typorb.service

/**
 * Pure geometry for the floating pill: keeps it pinned to the right edge of the screen and exactly
 * [MARGIN_DP] above the top edge of the soft keyboard.
 *
 * Kept free of Android types so it can be unit-tested directly.
 */
object KeyboardGeometry {

    /** Spec'd gap between the keyboard's top edge and the bottom of the pill. */
    const val MARGIN_DP = 16

    /**
     * @return `x to y` in pixels, for a window whose top-left corner should sit at that point.
     */
    fun pillTopLeft(
        screenWidthPx: Int,
        screenHeightPx: Int,
        keyboardHeightPx: Int,
        pillWidthPx: Int,
        pillHeightPx: Int,
        density: Float,
    ): Pair<Int, Int> {
        val margin = (MARGIN_DP * density).toInt()
        val clampedKeyboard = keyboardHeightPx.coerceIn(0, screenHeightPx)
        val x = (screenWidthPx - margin - pillWidthPx).coerceAtLeast(margin)
        val aboveKeyboard = screenHeightPx - clampedKeyboard - margin - pillHeightPx
        // Never push the pill off the top when the keyboard is taller than expected.
        val y = aboveKeyboard.coerceIn(margin, screenHeightPx)
        return x to y
    }
}