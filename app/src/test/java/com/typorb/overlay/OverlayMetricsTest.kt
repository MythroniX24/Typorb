package com.typorb.overlay

import com.typorb.model.OverlayUiState
import com.typorb.model.ProcessingStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window *is* the pill's rectangle, so these numbers decide what the user sees the moment they tap
 * — and, since the orb can be dragged, where they see it afterwards.
 *
 * Two bugs are guarded here. The first: the window used to follow the Settings flow only, so a tap that
 * started a recording left the capsule rendering inside the idle square — clipped, garbled, and
 * reported as "the layout breaks when I tap". The second: a position is now a thing the user owns, and
 * a drag that parked the orb off an edge or under the keyboard would put it somewhere it could never be
 * dragged back from.
 */
class OverlayMetricsTest {

    private val density = 2f
    private val screenWidth = 1080
    private val screenHeight = 1920
    private val keyboard = 700

    private fun defaultAnchor(pillWidthPx: Int, pillHeightPx: Int): OverlayMetrics.Anchor =
        OverlayMetrics.defaultAnchor(
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            keyboardHeightPx = keyboard,
            pillWidthPx = pillWidthPx,
            pillHeightPx = pillHeightPx,
            density = density,
        )

    private fun anchorFor(state: OverlayUiState, idleSizeDp: Int = 48): OverlayMetrics.Anchor {
        val (widthDp, heightDp) = OverlayMetrics.pillSizeDp(state, idleSizeDp)
        return defaultAnchor(OverlayMetrics.dp(widthDp, density), OverlayMetrics.dp(heightDp, density))
    }

    private fun clamp(
        anchor: OverlayMetrics.Anchor,
        pillWidthPx: Int,
        pillHeightPx: Int,
        keyboardHeightPx: Int = keyboard,
    ): OverlayMetrics.Anchor = OverlayMetrics.clampAnchor(
        anchor = anchor,
        screenWidthPx = screenWidth,
        screenHeightPx = screenHeight,
        keyboardHeightPx = keyboardHeightPx,
        pillWidthPx = pillWidthPx,
        pillHeightPx = pillHeightPx,
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
    fun `the default position pins the pill to the right edge, above the keyboard`() {
        val margin = 32 // 16dp at density 2
        val padding = OverlayMetrics.SHADOW_PADDING_DP * 2
        val pillWidthPx = OverlayMetrics.dp(OverlayMetrics.RECORDING_WIDTH_DP, density)
        val pillHeightPx = OverlayMetrics.dp(OverlayMetrics.PILL_HEIGHT_DP, density)

        val window = OverlayMetrics.windowFor(
            anchor = defaultAnchor(pillWidthPx, pillHeightPx),
            pillWidthPx = pillWidthPx,
            pillHeightPx = pillHeightPx,
            density = density,
        )

        assertEquals(pillWidthPx + padding * 2, window.width)
        assertEquals(pillHeightPx + padding * 2, window.height)
        // The pill's own right edge sits 16dp from the screen edge; the window is that plus the
        // padding it adds around the pill.
        assertEquals(screenWidth - margin + padding, window.x + window.width)
        // And the pill's bottom edge sits 16dp above the keyboard.
        assertEquals(screenHeight - keyboard - margin + padding, window.y + window.height)
    }

    @Test
    fun `a capsule grows leftwards from the corner the user aimed at`() {
        // The window's right edge is the anchor plus the shadow padding, whatever the state — so a
        // capsule that is 112dp wider than the orb does not push its right edge off the margin.
        val idleWidthPx = OverlayMetrics.dp(48, density)
        val idleHeightPx = OverlayMetrics.dp(48, density)
        val idleAnchor = defaultAnchor(idleWidthPx, idleHeightPx)

        val recordingWidthPx = OverlayMetrics.dp(OverlayMetrics.RECORDING_WIDTH_DP, density)
        val recordingHeightPx = OverlayMetrics.dp(OverlayMetrics.PILL_HEIGHT_DP, density)
        val recording = OverlayMetrics.windowFor(
            anchor = idleAnchor,
            pillWidthPx = recordingWidthPx,
            pillHeightPx = recordingHeightPx,
            density = density,
        )
        val idle = OverlayMetrics.windowFor(
            anchor = idleAnchor,
            pillWidthPx = idleWidthPx,
            pillHeightPx = idleHeightPx,
            density = density,
        )

        assertEquals(idle.x + idle.width, recording.x + recording.width)
        assertTrue("the capsule reaches left of the orb", recording.x < idle.x)
    }

    @Test
    fun `a position the user chose is kept, and the pill's own right edge stays put`() {
        val userAnchor = OverlayMetrics.Anchor(rightPx = 900, topPx = 600)
        val clamped = clamp(userAnchor, pillWidthPx = 96, pillHeightPx = 96)
        assertEquals(userAnchor, clamped)

        val window = OverlayMetrics.windowFor(
            anchor = clamped,
            pillWidthPx = 96,
            pillHeightPx = 96,
            density = density,
        )
        assertEquals(900 + OverlayMetrics.dp(OverlayMetrics.SHADOW_PADDING_DP, density), window.x + window.width)
        assertEquals(600 - OverlayMetrics.dp(OverlayMetrics.SHADOW_PADDING_DP, density), window.y)
    }

    @Test
    fun `a drag cannot park the orb off an edge or under the keyboard`() {
        val pillWidthPx = 96
        val pillHeightPx = 96
        val padding = OverlayMetrics.dp(OverlayMetrics.SHADOW_PADDING_DP, density)

        // Far past the left edge: the pill plus its shadow padding stays on screen.
        val left = clamp(OverlayMetrics.Anchor(rightPx = -500, topPx = 800), pillWidthPx, pillHeightPx)
        assertEquals(pillWidthPx + padding, left.rightPx)

        // Far past the right edge.
        val right = clamp(OverlayMetrics.Anchor(rightPx = screenWidth * 2, topPx = 800), pillWidthPx, pillHeightPx)
        assertEquals(screenWidth, right.rightPx)

        // Dragged down onto the keyboard: it stops at the keyboard's top edge, never underneath it.
        val down = clamp(OverlayMetrics.Anchor(rightPx = 900, topPx = screenHeight), pillWidthPx, pillHeightPx)
        assertEquals(screenHeight - keyboard - 32 - pillHeightPx - padding, down.topPx)

        // Dragged up into the status bar: the top stays on screen.
        val up = clamp(OverlayMetrics.Anchor(rightPx = 900, topPx = -200), pillWidthPx, pillHeightPx)
        assertEquals(padding, up.topPx)
    }

    @Test
    fun `without a keyboard the orb can be dropped anywhere down the screen`() {
        val pillHeightPx = 96
        val bottom = screenHeight - pillHeightPx - OverlayMetrics.dp(OverlayMetrics.SHADOW_PADDING_DP, 2f)
        val dropped = clamp(
            anchor = OverlayMetrics.Anchor(rightPx = 900, topPx = screenHeight),
            pillWidthPx = 96,
            pillHeightPx = pillHeightPx,
            keyboardHeightPx = 0,
        )
        assertEquals(bottom, dropped.topPx)
    }

    @Test
    fun `the widest capsule still fits a narrow screen`() {
        // 640px for the whole screen at density 2, i.e. the 320dp the widest failure capsule needs —
        // the narrowest screen Android ships on, with a capsule that fills it edge to edge.
        val pillWidthPx = 640
        val anchor = OverlayMetrics.clampAnchor(
            anchor = OverlayMetrics.Anchor(rightPx = 9999, topPx = 400),
            screenWidthPx = 640,
            screenHeightPx = 1280,
            keyboardHeightPx = 400,
            pillWidthPx = pillWidthPx,
            pillHeightPx = 96,
            density = 2f,
        )

        // The invariant that matters is about the *pill*, not the window: the shadow padding is
        // allowed to hang off the edge (it is transparent), the pill is not.
        assertTrue(anchor.rightPx <= 640)
        assertTrue(anchor.rightPx - pillWidthPx >= 0)
    }
}
