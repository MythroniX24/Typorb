package com.typorb.overlay

/**
 * Turns the microphone's raw level into the number of pixels the waveform is allowed to move.
 *
 * ## Why this is not just the amplitude
 *
 * [com.typorb.audio.AudioRecorder] reports a smoothed, square-rooted RMS in `0f..1f` and — quite
 * deliberately — never reaches zero: a room is never silent to a microphone, and the recorder floors
 * its own output so the bars keep a little life while the user is drawing breath. That floor is right
 * for a level meter and wrong for what the orb shows, because the orb's waveform is a *sentence*:
 * **the line is flat when the user has stopped speaking.** So the last step before the bars is a gate
 * that pins anything at or below the room's noise floor to exactly zero, which is what makes the bars
 * collapse into the straight line instead of shivering at 10% forever.
 *
 * Above the gate the curve is the identity, so loud speech still reaches the top and normal speech
 * keeps the range it had.
 *
 * Pure, and kept out of the composable for the same reason as everything else in this package: the
 * rule that decides whether the orb looks like it is listening is a rule, so it is asserted on the JVM
 * rather than discovered on a phone.
 */
object WaveformGate {

    /**
     * Level at or below which the user is treated as quiet.
     *
     * 0.16 is above the amplitude a phone microphone reports for an empty room (measured on the
     * target device's own scale, where steady background noise settles around 0.06–0.12 after the
     * recorder's smoothing) and well below the ~0.30 a normal speaking voice produces at arm's length,
     * so the line flattens in the gap between two words without ever swallowing a word.
     */
    const val FLOOR = 0.16f

    /** The level the bars are drawn at: `0f` when the user is quiet, `raw` otherwise. */
    fun level(raw: Float): Float {
        val clamped = raw.coerceIn(0f, 1f)
        return if (clamped <= FLOOR) 0f else clamped
    }
}
