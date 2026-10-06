package com.typorb.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * The view that owns every touch on the orb: tap, drag, and long press.
 *
 * **Why a wrapper instead of Compose's own gestures.** The gesture has to survive the window moving
 * under the finger. Compose reports a pointer's position *inside the view*, and since a drag moves
 * that very view, the two feed each other: measuring the movement locally and applying it to the
 * window makes the orb follow at roughly half speed, which reads as a laggy, rubbery orb. The
 * platform's raw coordinates do not have that problem — they are the same no matter where the window
 * is — so the drag is tracked here, in a real view, from `MotionEvent.rawX/rawY`.
 *
 * **Why the children never see a touch.** Compose's `clickable` would consume the ACTION_DOWN and
 * this class would never learn that a drag started. So the tap is not a Compose click at all: it is
 * decided here, from the same slop rule the platform uses, and handed to [Callbacks.onTap]. The pill
 * still answers the finger immediately because the press state is pushed into the composition
 * instead ([Callbacks.onPressChanged]).
 */
internal class OverlayDragHost(
    context: Context,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    interface Callbacks {
        /** The window's current top-left, in screen px — the drag's origin. */
        fun windowTopLeft(): Pair<Int, Int>

        /** Moves the window so the finger stays on the point it grabbed. */
        fun moveWindowTo(x: Int, y: Int)

        /** The finger is down / up; drives the press animation. */
        fun onPressChanged(pressedNow: Boolean)

        /** A drag has started: the orb is being moved, not tapped. */
        fun onDragStarted()

        /** The orb was dropped; the position is worth keeping. */
        fun onDragEnded()

        /** A tap that never became a drag or a long press. */
        fun onTap()

        /** A finger rested on the orb without moving. */
        fun onLongPress()
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        longPressFired = true
        callbacks.onLongPress()
    }

    /**
     * Always intercepts, so the composition underneath is a picture and never a touch target.
     *
     * A clickable child would eat the ACTION_DOWN before this class could decide whether the gesture
     * is a tap or a drag, and the tap is exactly what must still work.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (x, y) = callbacks.windowTopLeft()
                startX = x
                startY = y
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                longPressFired = false
                callbacks.onPressChanged(true)
                postDelayed(longPressRunnable, OverlayDrag.LONG_PRESS_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                // `!longPressFired` is not about politeness, it is about a real device report: a
                // finger that came to rest for the long-press timeout and only *then* started moving
                // used to have its movement ignored entirely, so the orb refused to be dragged for
                // the whole rest of the gesture. The long press has already been delivered by then,
                // and a gesture that has been resolved must not be re-resolved — so movement after it
                // is simply not a drag. Nothing is thrown away by that: a user who wants to drag
                // moves before the timeout, which now has room for them.
                if (!dragging && !longPressFired && OverlayDrag.isDrag(dx, dy, touchSlop)) {
                    dragging = true
                    // The finger is moving, so this was never going to be a long press.
                    removeCallbacks(longPressRunnable)
                    callbacks.onDragStarted()
                }
                if (dragging) {
                    val (x, y) = OverlayDrag.windowTopLeft(startX, startY, dx, dy)
                    callbacks.moveWindowTo(x, y)
                }
            }

            MotionEvent.ACTION_UP -> {
                finishGesture(tapped = !dragging && !longPressFired)
            }

            MotionEvent.ACTION_CANCEL -> {
                finishGesture(tapped = false)
            }

            else -> return false
        }
        return true
    }

    /**
     * Runs the end of a gesture exactly once, whatever ended it.
     *
     * [tapped] is the whole difference between starting a dictation and moving the orb, so it is
     * decided here and nowhere else: a drag that ends over the same pixel it started from is still a
     * drag, because the user saw the orb move. A *long* press that never moved is neither a tap nor a
     * drag, so the orb stays exactly where it was and the position is not written back.
     */
    private fun finishGesture(tapped: Boolean) {
        removeCallbacks(longPressRunnable)
        if (dragging) callbacks.onDragEnded()
        callbacks.onPressChanged(false)
        dragging = false
        if (tapped) callbacks.onTap()
        longPressFired = false
    }

    /** The orb is going away mid-gesture; drop everything without reporting a tap. */
    fun cancelGesture() {
        removeCallbacks(longPressRunnable)
        dragging = false
        longPressFired = false
        callbacks.onPressChanged(false)
    }
}
