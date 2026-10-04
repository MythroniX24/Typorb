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
}