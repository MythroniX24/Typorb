package com.typorb.overlay

import com.typorb.model.OverlayUiState
import com.typorb.model.ProcessingStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window *is* the pill's rectangle, so these numbers decide what the user sees the moment they tap.
 *
 * The bug this guards: the window used to follow the Settings flow only, so a tap that started a
 * recording left the capsule rendering inside the idle square — clipped, garbled, and reported as
 * "the layout breaks when I tap". Every state's rectangle is asserted here instead.
 */
class OverlayMetricsTest {

    private val density = 2f
    private val screenWidth = 1080
    private val screenHeight = 1920
    private val keyboard = 700

    private fun window(state: OverlayUiState, idleSizeDp: Int = 48): OverlayMetrics.Window =
        OverlayMetrics.windowFor(
            state = state,
            idleSizeDp = idleSizeDp,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            keyboardHeightPx = keyboard,
            density = density,
        )

    @Test
    fun `the idle orb is a square the user sized`() {
        assertEquals(56 to 56, OverlayMetrics.pillSizeDp(OverlayUiState.Idle, idleSizeDp = 56))
        assertEquals(40 to 40, OverlayMetrics.pillSizeDp(OverlayUiState.Idle, idleSizeDp = 40))
    }

    @Test
    fun `every working state is a capsule of the documented height`() {
        val height = OverlayMetrics.PILL_HEIGHT_DP
        assertEquals(
            OverlayMetrics.RECORDING_WIDTH_DP to height,
            OverlayMetrics.pillSizeDp(OverlayUiState.Recording(emptyList(), 0L), idleSizeDp = 48),
        )
        assertEquals(
            OverlayMetrics.PROCESSING_WIDTH_DP to height,
            OverlayMetrics.pillSizeDp(OverlayUiState.Processing(ProcessingStage.TRANSCRIBING), 48),
        )
        // A square tap target stays square: the idle size must not leak into the capsules.
        assertEquals(
            OverlayMetrics.RECORDING_WIDTH_DP to height,
            OverlayMetrics.pillSizeDp(OverlayUiState.Recording(emptyList(), 0L), idleSizeDp = 64),
        )
    }

    @Test
    fun `a failure capsule widens with its message and stays on screen`() {
        val short = OverlayMetrics.errorWidthDp("Didn't catch that.")
        assertEquals(OverlayMetrics.ERROR_WIDTH_DP, short)

        val long = OverlayMetrics.errorWidthDp("Groq rejected your API key. Update it in Typorb.")
        assertTrue("a long error gets a wider capsule", long > short)
        assertEquals(OverlayMetrics.MAX_ERROR_WIDTH_DP, OverlayMetrics.errorWidthDp("x".repeat(400)))

        // The widest capsule plus its two 16dp margins still fits the target phone's 360dp width.
        assertTrue(OverlayMetrics.MAX_ERROR_WIDTH_DP + 2 * 16 <= 360)
    }

    @Test
    fun `a capsule wider than a very narrow screen is shrunk to fit`() {
        // 640px = 320dp at density 2, the narrowest screen Android ships on. The clamp is proven by
        // the window still starting on screen and never exceeding it.
        val narrow = OverlayMetrics.windowFor(
            state = OverlayUiState.Failed("A failure message far longer than any narrow screen holds"),
            idleSizeDp = 48,
            screenWidthPx = 640,
            screenHeightPx = 1280,
            keyboardHeightPx = 400,
            density = 2f,
        )

        assertTrue(narrow.x >= 0)
        assertTrue(narrow.width <= 640)
    }

    @Test
    fun `the window is the pill plus shadow padding, pinned right and above the keyboard`() {
        val margin = 32 // 16dp at density 2
        val padding = OverlayMetrics.SHADOW_PADDING_DP * 2
        val recording = window(OverlayUiState.Recording(emptyList(), 0L))

        assertEquals(OverlayMetrics.RECORDING_WIDTH_DP * 2 + padding * 2, recording.width)
        assertEquals(OverlayMetrics.PILL_HEIGHT_DP * 2 + padding * 2, recording.height)

        // The pill's own right edge sits 16dp from the screen edge; the window is that plus the
        // padding it adds around the pill.
        assertEquals(screenWidth - margin + padding, recording.x + recording.width)
        // And the pill's bottom edge sits 16dp above the keyboard.
        assertEquals(screenHeight - keyboard - margin + padding, recording.y + recording.height)
    }

    @Test
    fun `the morph interpolates every edge and never overshoots`() {
        val from = OverlayMetrics.Window(width = 100, height = 100, x = 900, y = 1000)
        val to = OverlayMetrics.Window(width = 300, height = 200, x = 700, y = 900)

        assertEquals(from, OverlayMetrics.Window.lerp(from, to, 0f))
        assertEquals(to, OverlayMetrics.Window.lerp(from, to, 1f))

        val half = OverlayMetrics.Window.lerp(from, to, 0.5f)
        assertEquals(200, half.width)
        assertEquals(150, half.height)
        assertEquals(800, half.x)
        assertEquals(950, half.y)

        // A platform can report a fraction outside 0..1 on the final frame; that must not fling the
        // window off-screen.
        assertEquals(from, OverlayMetrics.Window.lerp(from, to, -1f))
        assertEquals(to, OverlayMetrics.Window.lerp(from, to, 4f))
    }
}
