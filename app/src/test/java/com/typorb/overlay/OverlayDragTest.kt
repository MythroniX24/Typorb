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
        // returns exactly to the point under the finger. This is what makes a drag that ran into an
        // edge resume under the finger the instant it comes back — in any direction, which is what the
        // old keyboard wall stopped happening downwards.
        val origin = 900
        val toEdge = OverlayDrag.windowTopLeft(origin, 400, dx = 4000f, dy = 0f).first
        val back = OverlayDrag.windowTopLeft(origin, 400, dx = 300f, dy = 0f).first

        assertEquals(origin + 4000, toEdge)
        assertEquals(origin + 300, back)
        assertTrue("coming back is not affected by the trip to the edge", back < toEdge)
    }

    @Test
    fun `the long press waits longer than the platform's own timeout, so it cannot steal a drag`() {
        // A long press and a drag start out identical and are only told apart by the clock, so this
        // number is the whole margin a hesitant drag gets. It used to be 420ms — *under* the platform's
        // 500ms long-press timeout — and a long press, once fired, cancelled the rest of the gesture: a
        // finger that came to rest on the orb for two fifths of a second and only then moved was
        // refused a drag entirely, which on a phone is simply "the orb will not move".
        //
        // The long press is the "type my last words again" rescue — something a user reaches for after
        // a dictation went wrong — while the drag is the daily gesture. When the two collide the daily
        // one has to win, and asking for a beat longer costs the rescue nothing.
        assertTrue("a long press must not resolve inside the drag window", OverlayDrag.LONG_PRESS_MS > 500L)
    }
}
