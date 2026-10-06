package com.typorb.overlay

import com.typorb.model.OverlayUiState
import com.typorb.service.KeyboardGeometry

/**
 * The pill's own size per state, and the overlay window bounds that follow from it.
 *
 * Pure — no Android types — for two reasons. The window is sized *outside* Compose, so a state change
 * has to move a real `WindowManager.LayoutParams`, and that is exactly the kind of arithmetic that
 * silently clips the orb when it is wrong. And the position rules are what the drag gesture is built
 * on, so they are asserted on the JVM instead of only being discovered by dragging an orb off the
 * screen on a phone.
 *
 * ## The anchor
 *
 * Everything here is expressed as a [Anchor]: the pill's **top-right corner in screen pixels**. That
 * one point is enough because the pill is drawn right-aligned — idle it sits against the right edge,
 * and a capsule grows leftwards from that same corner without the right edge moving. It is also the
 * point a drag holds on to: the user moves the orb, the anchor moves, and every later state change
 * keeps the corner the user chose instead of snapping back to the default.
 *
 * The window is deliberately larger than the pill by [SHADOW_PADDING_DP] on every side: a window
 * surface is clipped to its own bounds, so a pill-sized window would cut the soft ambient shadow off
 * at the edges.
 */
object OverlayMetrics {

    /** Height of every capsule state; the idle orb is square. */
    const val PILL_HEIGHT_DP = 48

    /**
     * Inflates the overlay window beyond the pill on every side.
     *
     * 14dp covers the 8dp elevation plus its blur radius. It is also the slack that absorbs the one
     * frame by which the window can lag the animated pill, and the only room the shadow has to fall
     * into at all.
     */
    const val SHADOW_PADDING_DP = 14

    const val RECORDING_WIDTH_DP = 160
    const val PROCESSING_WIDTH_DP = 190

    /** Narrowest failure capsule; the message widens it from here — see [errorWidthDp]. */
    const val ERROR_WIDTH_DP = 220

    /** Widest failure capsule, leaving the standard 16dp margin on a 360dp-wide screen. */
    const val MAX_ERROR_WIDTH_DP = 320

    /**
     * How long the pill takes to change size, in ms.
     *
     * Shared between the composition, which animates the surface, and
     * [com.typorb.overlay.OverlayController], which has to keep the window large enough for the pill
     * for the whole of that transition and then shrink it back. Two constants with the same value
     * would drift, and the failure mode of that drift is a clipped orb — the bug this number exists
     * to prevent.
     */
    const val MORPH_MS = 240

    /** Rough advance width of one character of the 13sp capsule label, in dp. */
    private const val CHARACTER_WIDTH_DP = 6.6f

    /** Dot, spacer and the capsule's own horizontal padding, in dp. */
    private const val ERROR_CHROME_DP = 52f

    /** Pill size in dp for [state]; only [OverlayUiState.Idle] is user-resizable. */
    fun pillSizeDp(state: OverlayUiState, idleSizeDp: Int): Pair<Int, Int> = when (state) {
        is OverlayUiState.Idle -> idleSizeDp to idleSizeDp
        is OverlayUiState.Recording -> RECORDING_WIDTH_DP to PILL_HEIGHT_DP
        is OverlayUiState.Processing -> PROCESSING_WIDTH_DP to PILL_HEIGHT_DP
        is OverlayUiState.Failed -> errorWidthDp(state.message) to PILL_HEIGHT_DP
    }

    /**
     * Width a failure capsule needs to show [message] on one line, clamped to a sane range.
     *
     * A fixed width was actively harmful here: the failure text is the only thing a user gets to read
     * when a dictation does not land, and errors such as Groq's own messages are far longer than the
     * 220dp that used to be hard-coded — they were cut off mid-sentence, which reads as a broken UI.
     */
    fun errorWidthDp(message: String): Int =
        (message.length * CHARACTER_WIDTH_DP + ERROR_CHROME_DP)
            .toInt()
            .coerceIn(ERROR_WIDTH_DP, MAX_ERROR_WIDTH_DP)

    /**
     * The smallest box that holds both pill sizes.
     *
     * This is the whole reason the window does not have to be resized on every animation frame. When
     * a state changes, the two pills in play are the one leaving and the one arriving, and a window
     * the size of their hull contains both — so the window is written **once** at the start of the
     * transition and once more when it is over, instead of sixty times a second. The old per-frame
     * writes were the reason a tap could look torn: the composition drew the new pill while the
     * window was still the old rectangle, and the window is the clip rectangle.
     */
    fun hull(a: Pair<Int, Int>, b: Pair<Int, Int>): Pair<Int, Int> =
        maxOf(a.first, b.first) to maxOf(a.second, b.second)

    /** The pill's top-right corner, in screen pixels. */
    data class Anchor(val rightPx: Int, val topPx: Int)

    /** A rectangle for the overlay window: pill plus shadow padding, positioned on screen. */
    data class Window(val width: Int, val height: Int, val x: Int, val y: Int)

    /**
     * Where the pill belongs when the user has not moved it: pinned to the right edge and exactly
     * [KeyboardGeometry.MARGIN_DP] above the keyboard.
     *
     * [pillWidthPx] and [pillHeightPx] are the *animated* pill's dimensions, so the default anchor is
     * recomputed on every frame of a state morph and the pill's right edge never leaves the margin
     * while it grows.
     */
    fun defaultAnchor(
        screenWidthPx: Int,
        screenHeightPx: Int,
        keyboardHeightPx: Int,
        pillWidthPx: Int,
        pillHeightPx: Int,
        density: Float,
    ): Anchor {
        val (left, top) = KeyboardGeometry.pillTopLeft(
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            keyboardHeightPx = keyboardHeightPx,
            pillWidthPx = pillWidthPx,
            pillHeightPx = pillHeightPx,
            density = density,
        )
        return Anchor(rightPx = left + pillWidthPx, topPx = top)
    }

    /**
     * Pulls [anchor] back onto the screen.
     *
     * Only ever applied to a position the *user* chose — the default anchor is computed from the
     * screen and cannot leave it. Exactly one rule: **the pill stays fully visible**, with the shadow
     * padding still on screen, so a drag can never park the orb half off an edge and leave it
     * unreachable.
     *
     * ## The keyboard used to be a wall here, and that was the drag bug
     *
     * This function used to stop the orb at the keyboard's top edge, reasoning that the orb must
     * never slide underneath the keyboard. The reasoning was wrong twice over. The orb's *default*
     * position — 16dp above the keyboard — **is** the bottom-most row that rule allows, so a downward
     * drag moved the orb by the 14dp of shadow padding and then stopped: on a phone, a drag that
     * simply refuses to go down reads as a broken drag, not as a wall. And the orb cannot be hidden
     * by a keyboard anyway: this window is `TYPE_ACCESSIBILITY_OVERLAY`, which is layered above the
     * IME, so an orb parked over the keyboard is still on top and still touchable.
     *
     * The keyboard therefore still decides where the orb *starts* (see [defaultAnchor]) and nothing
     * else. Every direction of a drag now moves the orb.
     */
    fun clampAnchor(
        anchor: Anchor,
        screenWidthPx: Int,
        screenHeightPx: Int,
        pillWidthPx: Int,
        pillHeightPx: Int,
        density: Float,
    ): Anchor {
        val padding = dp(SHADOW_PADDING_DP, density)

        val minRight = (pillWidthPx + padding).coerceAtMost(screenWidthPx)
        val maxRight = screenWidthPx.coerceAtLeast(minRight)
        val right = anchor.rightPx.coerceIn(minRight, maxRight)

        val maxTop = (screenHeightPx - pillHeightPx - padding).coerceAtLeast(padding)
        val top = anchor.topPx.coerceIn(padding, maxTop)
        return Anchor(right, top)
    }

    /**
     * The window for a pill of [pillWidthPx] x [pillHeightPx] anchored at [anchor].
     *
     * The window's right and top edges are the anchor plus the shadow padding, so the *pill* — which
     * the composition draws into the top-right corner of the padded content box — lands exactly on the
     * anchor. No clamping happens here on purpose: [anchor] is already clamped, and clamping again
     * would silently shift the pill away from the corner the user dragged it to.
     */
    fun windowFor(
        anchor: Anchor,
        pillWidthPx: Int,
        pillHeightPx: Int,
        density: Float,
    ): Window {
        val padding = dp(SHADOW_PADDING_DP, density)
        return Window(
            width = pillWidthPx + padding * 2,
            height = pillHeightPx + padding * 2,
            x = anchor.rightPx - pillWidthPx - padding,
            y = anchor.topPx - padding,
        )
    }

    /** dp → px, matching the rounding `WindowManager.LayoutParams` has always been given here. */
    fun dp(value: Int, density: Float): Int = (value * density).toInt()
}
