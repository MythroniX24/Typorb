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
 * reported as "the layout breaks when I tap". The second: a position is a thing the user owns, and the
 * rules for it have to let the orb *go* anywhere while never letting it end up somewhere unreachable.
 *
 * The third is the one the phone reported: the orb used to be walled off from the keyboard, and its
 * own resting place was just under that wall — so a downward drag moved it 14dp and stopped, which is
 * not felt as a boundary but as a broken drag. The tests below pin every direction as reachable.
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
        screenWidthPx: Int = screenWidth,
        screenHeightPx: Int = screenHeight,
    ): OverlayMetrics.Anchor = OverlayMetrics.clampAnchor(
        anchor = anchor,
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx,
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
    fun `a drag can put the orb anywhere on the screen, in every direction`() {
        val pillWidthPx = 96
        val pillHeightPx = 96
        val padding = OverlayMetrics.dp(OverlayMetrics.SHADOW_PADDING_DP, density)

        // Far past the left edge: the pill plus its shadow padding stays on screen.
        val left = clamp(OverlayMetrics.Anchor(rightPx = -500, topPx = 800), pillWidthPx, pillHeightPx)
        assertEquals(pillWidthPx + padding, left.rightPx)

        // Far past the right edge.
        val right = clamp(OverlayMetrics.Anchor(rightPx = screenWidth * 2, topPx = 800), pillWidthPx, pillHeightPx)
        assertEquals(screenWidth, right.rightPx)

        // **Down, over the keyboard.** This is the direction that used to be impossible: the orb's own
        // resting place was the bottom-most row the old keyboard wall allowed, so a downward drag moved
        // it by the 14dp of shadow padding and stopped. The keyboard is layered *under* an accessibility
        // overlay, so an orb parked over it is still on top, still visible, and still touchable.
        val down = clamp(OverlayMetrics.Anchor(rightPx = 900, topPx = screenHeight), pillWidthPx, pillHeightPx)
        assertEquals(screenHeight - pillHeightPx - padding, down.topPx)

        // And up into the status bar: the top stays on screen.
        val up = clamp(OverlayMetrics.Anchor(rightPx = 900, topPx = -200), pillWidthPx, pillHeightPx)
        assertEquals(padding, up.topPx)
    }

    @Test
    fun `the resting place is itself a legal position`() {
        // The keyboard still decides where the orb starts. If that place were outside the clamp, the
        // first pixel of a drag would teleport the orb to the nearest legal position — which is the
        // other way a drag reads as "it jumped away from my finger".
        val pillWidthPx = OverlayMetrics.dp(OverlayMetrics.RECORDING_WIDTH_DP, density)
        val pillHeightPx = OverlayMetrics.dp(OverlayMetrics.PILL_HEIGHT_DP, density)
        val resting = defaultAnchor(pillWidthPx, pillHeightPx)
        assertEquals(resting, clamp(resting, pillWidthPx, pillHeightPx))
    }

    @Test
    fun `the window box for a transition holds both pills in it`() {
        // The window is written once per state change, to this box. Both the pill that is leaving and
        // the pill that is arriving have to fit inside it — otherwise the outgoing one is clipped by
        // the window, which is exactly the tearing this hull replaced a per-frame resize to fix.
        val idle = OverlayMetrics.pillSizeDp(OverlayUiState.Idle, idleSizeDp = 48)
        val recording = OverlayMetrics.pillSizeDp(OverlayUiState.Recording(emptyList(), 0L), 48)
        val hull = OverlayMetrics.hull(idle, recording)

        assertEquals(OverlayMetrics.RECORDING_WIDTH_DP to OverlayMetrics.PILL_HEIGHT_DP, hull)
        assertEquals("the hull does not depend on which way round it is asked", hull, OverlayMetrics.hull(recording, idle))
        assertTrue(hull.first >= idle.first && hull.first >= recording.first)
        assertTrue(hull.second >= idle.second && hull.second >= recording.second)
        // And the pill's box is still the tighter of the two once the transition is over.
        assertEquals(idle, OverlayMetrics.hull(idle, idle))
    }

    @Test
    fun `the widest capsule still fits a narrow screen`() {
        // 640px for the whole screen at density 2, i.e. the 320dp the widest failure capsule needs —
        // the narrowest screen Android ships on, with a capsule that fills it edge to edge.
        val pillWidthPx = 640
        val anchor = clamp(
            anchor = OverlayMetrics.Anchor(rightPx = 9999, topPx = 400),
            pillWidthPx = pillWidthPx,
            pillHeightPx = 96,
            screenWidthPx = 640,
            screenHeightPx = 1280,
        )

        // The invariant that matters is about the *pill*, not the window: the shadow padding is
        // allowed to hang off the edge (it is transparent), the pill is not.
        assertTrue(anchor.rightPx <= 640)
        assertTrue(anchor.rightPx - pillWidthPx >= 0)
    }
}
