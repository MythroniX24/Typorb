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
 *
 * ## The long press is the drag's enemy
 *
 * A long press and a drag are the same physical act — finger down, then decide — and only the clock
 * separates them. [LONG_PRESS_MS] used to be 420 ms and a long press, once fired, cancelled the rest
 * of the gesture: a finger that rested on the orb for two fifths of a second and *then* moved was no
 * longer allowed to drag it. Hesitating for 400 ms is what a person does when they are about to move
 * something, so the effect on a phone was an orb that "just will not drag". The window is now long
 * enough that a hesitant drag is never stolen, and
 * [com.typorb.overlay.OverlayDragHost] never lets a fired long press stand in a drag's way either.
 */
object OverlayDrag {

    /**
     * How long a finger has to rest on the orb before it counts as a long press.
     *
     * Well above `ViewConfiguration.getLongPressTimeout()` (500 ms) on purpose. The long press is the
     * "type the last words again" escape hatch — a recovery the user reaches for *after* a dictation
     * went wrong — while the drag is how the orb is used every day. When the two conflict, the daily
     * gesture has to win, and it does not cost the escape hatch anything to ask for a beat longer.
     */
    const val LONG_PRESS_MS = 700L

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
