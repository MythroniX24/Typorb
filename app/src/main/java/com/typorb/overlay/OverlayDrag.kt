package com.typorb.overlay

import kotlin.math.abs

/**
 * The gesture rules for the floating orb, kept apart from the view so they can be asserted on the JVM.
 *
 * The rule that matters is the one that decides whether a touch is a *tap* — which starts a dictation
 * — or a *drag*, which moves the orb. Getting it wrong in either direction is a bug the user feels
 * immediately: too eager and every tap nudges the orb instead of recording, too shy and the orb
 * refuses to move. It is a single threshold against `ViewConfiguration.scaledTouchSlop`, the same
 * distance the platform uses for every other drag, so the orb moves when the rest of the system
 * would move it.
 */
object OverlayDrag {

    /**
     * How long a finger has to rest on the orb before it counts as a long press.
     *
     * Under `ViewConfiguration.getLongPressTimeout()` (500 ms) on purpose: the long press is the
     * "type the last words again" escape hatch, and a user who is already frustrated by text that did
     * not appear should not have to hold a 48dp target for the platform's full timeout.
     */
    const val LONG_PRESS_MS = 420L

    /** Whether a touch that has travelled ([dx], [dy]) px from where it landed is now a drag. */
    fun isDrag(dx: Float, dy: Float, touchSlopPx: Int): Boolean =
        abs(dx) > touchSlopPx || abs(dy) > touchSlopPx

    /**
     * Where the window's top-left belongs for a drag that has travelled ([dx], [dy]) px.
     *
     * Measured from the window's position when the finger went down rather than accumulated from the
     * previous frame: a drag that runs into an edge and comes back must return to where the finger is,
     * not to a position that has been creeping along the boundary.
     */
    fun windowTopLeft(startX: Int, startY: Int, dx: Float, dy: Float): Pair<Int, Int> =
        (startX + dx.toInt()) to (startY + dy.toInt())
}
