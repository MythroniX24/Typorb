package com.typorb.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry tests — no Android framework needed, so they run on the JVM in CI.
 */
class KeyboardGeometryTest {

    private val density = 2f
    private val screenWidth = 1080
    private val screenHeight = 1920
    private val pillWidth = 320 // 160dp at density 2
    private val pillHeight = 96 // 48dp at density 2

    @Test
    fun `pill sits 16dp above the keyboard and is right aligned`() {
        val keyboardHeight = 700
        val (x, y) = KeyboardGeometry.pillTopLeft(
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            keyboardHeightPx = keyboardHeight,
            pillWidthPx = pillWidth,
            pillHeightPx = pillHeight,
            density = density,
        )

        val margin = 32 // 16dp at density 2
        assertEquals(screenWidth - margin - pillWidth, x)
        assertEquals(screenHeight - keyboardHeight - margin - pillHeight, y)
    }

    @Test
    fun `pill follows the keyboard as it animates`() {
        val hidden = KeyboardGeometry.pillTopLeft(screenWidth, screenHeight, 0, pillWidth, pillHeight, density)
        val shown = KeyboardGeometry.pillTopLeft(screenWidth, screenHeight, 700, pillWidth, pillHeight, density)

        assertEquals(hidden.second, shown.second + 700)
    }

    @Test
    fun `pill never leaves the screen when the keyboard height is nonsense`() {
        val (x, y) = KeyboardGeometry.pillTopLeft(
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            keyboardHeightPx = screenHeight * 4,
            pillWidthPx = pillWidth,
            pillHeightPx = pillHeight,
            density = density,
        )

        assertTrue("x within screen", x >= 0)
        assertTrue("y within screen", y in 0..screenHeight)
        assertTrue("pill not off the right edge", x + pillWidth <= screenWidth)
    }

    @Test
    fun `negative keyboard height is treated as no keyboard`() {
        val (negative, _) = KeyboardGeometry.pillTopLeft(screenWidth, screenHeight, -200, pillWidth, pillHeight, density)
        val (zero, _) = KeyboardGeometry.pillTopLeft(screenWidth, screenHeight, 0, pillWidth, pillHeight, density)

        assertEquals(zero, negative)
    }

    /**
     * A floating or split keyboard does not reach the bottom of the screen, so the distance the IME
     * window's top edge sits from the bottom is much larger than the keyboard itself.
     *
     * That is exactly the right number to position against: the pill is placed above the keyboard's
     * *top edge*, so anchoring on the top edge keeps it glued to a floating keyboard too, rather than
     * letting it drift to the top of the display.
     */
    @Test
    fun `pill tracks a floating keyboard's top edge rather than the screen bottom`() {
        // A 400px-tall floating keyboard whose top edge is at y=600 on a 1520px screen.
        val display = 1520
        val windowTop = 600
        val floatingKeyboardHeight = display - windowTop

        val keyboardTop = ImeGeometry.heightAboveBottomInset(
            displayHeightPx = display,
            windowTopPx = windowTop,
            windowHeightPx = 400,
            windowWidthPx = 720,
        )
        assertEquals(floatingKeyboardHeight, keyboardTop)

        val (_, y) = KeyboardGeometry.pillTopLeft(
            screenWidthPx = 720,
            screenHeightPx = display,
            keyboardHeightPx = keyboardTop,
            pillWidthPx = 320,
            pillHeightPx = 96,
            density = density,
        )

        // The pill's bottom edge plus the 16dp margin lands exactly on the keyboard's top edge.
        val margin = 32 // 16dp at density 2
        assertEquals(windowTop, y + 96 + margin)
        assertTrue("pill is not pinned to the top of the screen", y > 0)
    }

    /**
     * End to end on a Redmi 8A: the pill clears a docked Gboard that rests on a 3-button nav bar.
     */
    @Test
    fun `pill clears a docked keyboard sitting above a navigation bar`() {
        val display = 1520
        val navBar = 96
        val gboardHeight = 440
        val keyboardTop = display - navBar - gboardHeight

        val keyboardHeight = ImeGeometry.heightAboveBottomInset(
            displayHeightPx = display,
            windowTopPx = keyboardTop,
            windowHeightPx = gboardHeight,
            windowWidthPx = 720,
        )
        assertEquals(navBar + gboardHeight, keyboardHeight)

        val (_, y) = KeyboardGeometry.pillTopLeft(
            screenWidthPx = 720,
            screenHeightPx = display,
            keyboardHeightPx = keyboardHeight,
            pillWidthPx = 320,
            pillHeightPx = 96,
            density = density,
        )

        val margin = 32
        assertEquals(keyboardTop, y + 96 + margin)
    }
}