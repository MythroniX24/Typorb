package com.typorb.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orb's gesture rules: when a touch is a drag, and where the window goes while it is one.
 *
 * Both halves of this are felt immediately on a phone and are invisible in a stack trace. Too eager
 * and every tap nudges the orb instead of starting a dictation; too shy and the orb refuses to move.
 * A drag measured from the wrong origin creeps away from the finger, or snaps back when the user lets
 * go — the classic "the ball drifts" bug.
 */
class OverlayDragTest {

    private val slop = 24

    @Test
    fun `a touch that has not travelled is still a tap`() {
        assertFalse(OverlayDrag.isDrag(dx = 0f, dy = 0f, touchSlopPx = slop))
        assertFalse(OverlayDrag.isDrag(dx = 23f, dy = -23f, touchSlopPx = slop))
        // Exactly at the slop is not a drag: the platform's own rule is "greater than".
        assertFalse(OverlayDrag.isDrag(dx = 24f, dy = 0f, touchSlopPx = slop))
    }

    @Test
    fun `a touch that travelled past the slop is a drag`() {
        assertTrue(OverlayDrag.isDrag(dx = 25f, dy = 0f, touchSlopPx = slop))
        assertTrue(OverlayDrag.isDrag(dx = 0f, dy = -25f, touchSlopPx = slop))
        assertTrue(OverlayDrag.isDrag(dx = 29f, dy = 29f, touchSlopPx = slop))
        // The axes are checked one at a time, the way the platform's own gesture detector does it. A
        // diagonal that is under the slop on *both* axes is still the tremor of a tap, not a move.
        assertFalse(OverlayDrag.isDrag(dx = 20f, dy = 20f, touchSlopPx = slop))
    }

    @Test
    fun `the window follows the finger one to one`() {
        assertEquals(1100 to 640, OverlayDrag.windowTopLeft(startX = 900, startY = 400, dx = 200f, dy = 240f))
        assertEquals(700 to 400, OverlayDrag.windowTopLeft(startX = 900, startY = 400, dx = -200f, dy = 0f))
    }

    @Test
    fun `a drag is measured from where the finger went down, not from the last frame`() {
        // The finger travels to the right edge, is held there, and comes back to the middle. Accumulated
        // per frame the window would end up wherever the clamping left it; measured from the origin it
        // returns exactly to the point under the finger.
        val origin = 900
        val toEdge = OverlayDrag.windowTopLeft(origin, 400, dx = 4000f, dy = 0f).first
        val back = OverlayDrag.windowTopLeft(origin, 400, dx = 300f, dy = 0f).first

        assertEquals(origin + 4000, toEdge)
        assertEquals(origin + 300, back)
        assertTrue("coming back is not affected by the trip to the edge", back < toEdge)
    }

    @Test
    fun `the long press resolves before the platform's own timeout`() {
        // Under ViewConfiguration.getLongPressTimeout() (500ms) on purpose: this gesture is the "type
        // my last words again" rescue, and holding a 48dp target for the full timeout is a lot to ask
        // of someone whose text just did not appear.
        assertTrue(OverlayDrag.LONG_PRESS_MS in 1..499)
    }
}
