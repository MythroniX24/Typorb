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
 * The shape they pin down: **a tile that carries the app icon and never moves, plus a panel that
 * slides out of its left edge.** Every state that has something to show grows that panel; the two
 * states with nothing to show (idle, and the moment while the engine works) collapse it again. That
 * is the behaviour the user asked for in words — the icon stays, the box extends to the side, tapping
 * again closes the slider — and the only place it can be asserted without a phone is here.
 *
 * The other bugs guarded here are older. The first: the window used to follow the Settings flow only,
 * so a tap that started a recording left the capsule rendering inside the idle square — clipped,
 * garbled, and reported as "the layout breaks when I tap". The second: a position is a thing the user
 * owns, and the rules for it have to let the orb *go* anywhere while never letting it end up somewhere
 * unreachable. The third is the one the phone reported: the orb used to be walled off from the
 * keyboard, and its own resting place was just under that wall — so a downward drag moved it 14dp and
 * stopped, which is not felt as a boundary but as a broken drag.
 */
class OverlayMetricsTest {

    private val density = 2f
    private val screenWidth = 1080
    private val screenHeight = 1920
    private val keyboard = 700
    private val tile = 48

    private fun defaultAnchor(pillWidthPx: Int, pillHeightPx: Int): OverlayMetrics.Anchor =
        OverlayMetrics.defaultAnchor(
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            keyboardHeightPx = keyboard,
            pillWidthPx = pillWidthPx,
            pillHeightPx = pillHeightPx,
            density = density,
        )

    private fun anchorFor(state: OverlayUiState, idleSizeDp: Int = tile): OverlayMetrics.Anchor {
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

    private fun recordingWidthDp(orbSizeDp: Int = tile): Int =
        OverlayMetrics.pillSizeDp(OverlayUiState.Recording(emptyList(), 0L), orbSizeDp).first

    @Test
    fun `the idle orb is a square the user sized`() {
        assertEquals(56 to 56, OverlayMetrics.pillSizeDp(OverlayUiState.Idle, orbSizeDp = 56))
        assertEquals(40 to 40, OverlayMetrics.pillSizeDp(OverlayUiState.Idle, orbSizeDp = 40))
    }

    @Test
    fun `the orb collapses back to the tile alone while the engine works`() {
        // The second tap is a request to stop, and what it must leave behind is the orb the way the
        // user knows it: the panel goes away and the tile is all that is left. The working ring is
        // drawn *inside* the tile, so it costs no width at all.
        val processing = OverlayMetrics.pillSizeDp(OverlayUiState.Processing(ProcessingStage.TRANSCRIBING), tile)
        assertEquals(tile to tile, processing)
        assertEquals(0, OverlayMetrics.panelWidthDp(OverlayUiState.Processing(ProcessingStage.TRANSCRIBING), tile))
        assertEquals(0, OverlayMetrics.panelWidthDp(OverlayUiState.Idle, tile))
    }

    @Test
    fun `the working panel is a fixed piece of glass beside the tile`() {
        // The panel's width is the panel's width, whatever the user set their tile to: resizing the
        // orb in Settings resizes the orb, not the room the waveform is drawn in.
        assertEquals(
            tile + OverlayMetrics.PANEL_GAP_DP + OverlayMetrics.WAVE_PANEL_DP to tile,
            OverlayMetrics.pillSizeDp(OverlayUiState.Recording(emptyList(), 0L), tile),
        )
        assertEquals(
            OverlayMetrics.PANEL_GAP_DP + OverlayMetrics.WAVE_PANEL_DP,
            OverlayMetrics.panelWidthDp(OverlayUiState.Recording(emptyList(), 0L), tile),
        )

        val biggerOrb = 64
        assertEquals(tile + 16, recordingWidthDp(biggerOrb) - (OverlayMetrics.PANEL_GAP_DP + OverlayMetrics.WAVE_PANEL_DP))
        assertEquals(
            OverlayMetrics.PANEL_GAP_DP + OverlayMetrics.WAVE_PANEL_DP,
            OverlayMetrics.panelWidthDp(OverlayUiState.Recording(emptyList(), 0L), biggerOrb),
        )
    }

    @Test
    fun `every state is exactly as tall as the tile`() {
        // The tile's height is the pill's height in every state — which is why only the width is
        // animated and why the tile cannot be squashed into a capsule by a state change.
        val states = listOf(
            OverlayUiState.Idle,
            OverlayUiState.Recording(emptyList(), 0L),
            OverlayUiState.Processing(ProcessingStage.TRANSCRIBING),
            OverlayUiState.Failed("Didn't catch that."),
        )
        states.forEach { state ->
            assertEquals("$state", tile, OverlayMetrics.pillSizeDp(state, tile).second)
        }
    }

    @Test
    fun `a failure panel widens with its message and stays on screen`() {
        // A message shorter than the panel has room for is still given the minimum: the panel is what
        // the dot and the padding live in, not a text box sized to its content.
        assertEquals(OverlayMetrics.ERROR_PANEL_DP, OverlayMetrics.errorPanelWidthDp("Nope."))
        val short = OverlayMetrics.errorPanelWidthDp("Didn't catch that.")
        assertTrue("a one-line error sits at the minimum", short >= OverlayMetrics.ERROR_PANEL_DP)

        val long = OverlayMetrics.errorPanelWidthDp("Groq rejected your API key. Update it in Typorb.")
        assertTrue("a long error gets a wider panel", long > short)
        assertEquals(OverlayMetrics.MAX_ERROR_PANEL_DP, OverlayMetrics.errorPanelWidthDp("x".repeat(400)))

        // The widest failure the orb can show, tile and gap included, plus its two 16dp margins, still
        // fits the target phone's 360dp width — so the whole sentence is readable on screen rather than
        // running off the edge.
        val widestPill = OverlayMetrics.pillSizeDp(OverlayUiState.Failed("x".repeat(400)), tile).first
        assertEquals(OverlayMetrics.MAX_ERROR_PANEL_DP + OverlayMetrics.PANEL_GAP_DP + tile, widestPill)
        assertTrue(widestPill + 2 * 16 <= 360)
    }

    @Test
    fun `the default position pins the pill to the right edge, above the keyboard`() {
        val margin = 32 // 16dp at density 2
        val padding = OverlayMetrics.SHADOW_PADDING_DP * 2
        val pillWidthPx = OverlayMetrics.dp(recordingWidthDp(), density)
        val pillHeightPx = OverlayMetrics.dp(tile, density)

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
    fun `the panel slides out of the tile's left edge and the tile does not move`() {
        // The whole promise of the shape: the box extends *to the side* and the icon the user aimed at
        // stays exactly where it was. Because the window's right edge is the anchor plus the shadow
        // padding, the tile's right edge is in the same place in both states — and the panel, which is
        // the difference between them, opens to the left of it.
        val idleAnchor = anchorFor(OverlayUiState.Idle)
        val idleWindow = OverlayMetrics.windowFor(
            anchor = idleAnchor,
            pillWidthPx = OverlayMetrics.dp(tile, density),
            pillHeightPx = OverlayMetrics.dp(tile, density),
            density = density,
        )
        val recordingWindow = OverlayMetrics.windowFor(
            anchor = idleAnchor,
            pillWidthPx = OverlayMetrics.dp(recordingWidthDp(), density),
            pillHeightPx = OverlayMetrics.dp(tile, density),
            density = density,
        )

        assertEquals(idleWindow.x + idleWindow.width, recordingWindow.x + recordingWindow.width)
        assertEquals(idleWindow.y, recordingWindow.y)
        assertTrue("the panel reaches left of the tile", recordingWindow.x < idleWindow.x)
        assertEquals(
            OverlayMetrics.dp(OverlayMetrics.PANEL_GAP_DP + OverlayMetrics.WAVE_PANEL_DP, density),
            idleWindow.x - recordingWindow.x,
        )
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
        val pillWidthPx = OverlayMetrics.dp(recordingWidthDp(), density)
        val pillHeightPx = OverlayMetrics.dp(tile, density)
        val resting = defaultAnchor(pillWidthPx, pillHeightPx)
        assertEquals(resting, clamp(resting, pillWidthPx, pillHeightPx))
    }

    @Test
    fun `the window box for a transition holds both pills in it`() {
        // The window is written once per state change, to this box. Both the pill that is leaving and
        // the pill that is arriving have to fit inside it — otherwise the outgoing one is clipped by
        // the window, which is exactly the tearing this hull replaced a per-frame resize to fix.
        val idle = OverlayMetrics.pillSizeDp(OverlayUiState.Idle, tile)
        val recording = OverlayMetrics.pillSizeDp(OverlayUiState.Recording(emptyList(), 0L), tile)
        val hull = OverlayMetrics.hull(idle, recording)

        assertEquals(recordingWidthDp() to tile, hull)
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
