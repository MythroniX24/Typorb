package com.typorb.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the keyboard-visibility geometry that decides whether the pill shows.
 *
 * Each case corresponds to a real device shape that previously made the Typorb orb refuse to
 * appear at all.
 */
class ImeGeometryTest {

    // --- Redmi 8A: 720 x 1520, Gboard sits above a 3-button navigation bar -------------------

    @Test
    fun `gboard on a redmi 8a reports its keyboard height`() {
        // Gboard occupies roughly the bottom third of a 1520px display, leaving its top at ~1010.
        val height = ImeGeometry.heightAboveBottomInset(
            displayHeightPx = 1520,
            windowTopPx = 1010,
            windowHeightPx = 460,
            windowWidthPx = 720,
        )
        assertEquals(510, height)
    }

    @Test
    fun `a keyboard well under half the screen is still a keyboard`() {
        // Regression: an earlier revision required 40% of the display height, which every real
        // keyboard on a 720p phone fails, so the orb never appeared.
        val height = ImeGeometry.heightAboveBottomInset(
            displayHeightPx = 1520,
            windowTopPx = 1080,
            windowHeightPx = 392,
            windowWidthPx = 720,
        )
        assertEquals(440, height)
        assertEquals(true, height >= displayHeightPx() * 0.2f)
    }

    @Test
    fun `keyboard resting above a navigation bar is detected`() {
        // Regression: the IME window's bottom edge stops at the top of the nav bar, so an earlier
        // `bounds.bottom >= screenHeight` check rejected every real keyboard.
        val navBarPx = 96
        val keyboardBottom = 1520 - navBarPx
        val keyboardHeight = 440
        val keyboardTop = keyboardBottom - keyboardHeight

        val height = ImeGeometry.heightAboveBottomInset(
            displayHeightPx = 1520,
            windowTopPx = keyboardTop,
            windowHeightPx = keyboardHeight,
            windowWidthPx = 720,
        )
        assertEquals(536, height)
    }

    @Test
    fun `gesture navigation bar barely inset is detected`() {
        val keyboardTop = 1520 - 48 - 440
        val height = ImeGeometry.heightAboveBottomInset(
            displayHeightPx = 1520,
            windowTopPx = keyboardTop,
            windowHeightPx = 440,
            windowWidthPx = 720,
        )
        assertEquals(488, height)
    }

    // --- Rejected shapes -----------------------------------------------------------------------

    @Test
    fun `a hidden keyboard window is rejected`() {
        assertEquals(
            0,
            ImeGeometry.heightAboveBottomInset(1520, 1500, 20, 720),
        )
    }

    @Test
    fun `a zero height window is rejected`() {
        assertEquals(
            0,
            ImeGeometry.heightAboveBottomInset(1520, 1100, 0, 720),
        )
    }

    @Test
    fun `a zero width window is rejected`() {
        assertEquals(
            0,
            ImeGeometry.heightAboveBottomInset(1520, 1100, 420, 0),
        )
    }

    @Test
    fun `a strip shorter than the minimum is not treated as a keyboard`() {
        // A one-row suggestion strip peeking in from the bottom must not push the orb up.
        assertEquals(
            0,
            ImeGeometry.heightAboveBottomInset(1520, 1480, 40, 720),
        )
    }

    @Test
    fun `an out of range window top is rejected`() {
        assertEquals(
            0,
            ImeGeometry.heightAboveBottomInset(1520, -5, 440, 720),
        )
        assertEquals(
            0,
            ImeGeometry.heightAboveBottomInset(1520, 1520, 440, 720),
        )
    }

    @Test
    fun `a non positive display height is rejected`() {
        assertEquals(
            0,
            ImeGeometry.heightAboveBottomInset(0, 500, 400, 720),
        )
    }

    @Test
    fun `height never exceeds the display`() {
        val height = ImeGeometry.heightAboveBottomInset(1520, 0, 1520, 720)
        assertEquals(1520, height)
    }

    private fun displayHeightPx() = 1520f
}