package com.typorb.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind "the waves go up and down while I speak, and become a straight line when I stop".
 *
 * That behaviour is the one part of the orb a user described in words rather than as a bug, and it is
 * also the one part that cannot be checked by looking at a screenshot: whether the line is flat in a
 * pause depends on where the gate sits relative to the noise floor of a real phone microphone. The
 * numbers below are the recorder's own scale, so a change to either end fails here instead of on the
 * user's phone.
 */
class WaveformGateTest {

    /** What the recorder reports for an empty room: smoothed, floored at its own minimum. */
    private val quietRoom = listOf(0f, 0.06f, 0.09f, 0.12f, 0.15f)

    /** What it reports for speech at arm's length, and for a shout. */
    private val speech = listOf(0.30f, 0.45f, 0.62f, 0.8f, 1f)

    @Test
    fun `a pause is exactly zero, so the line is flat and not nearly flat`() {
        // Not "small": exactly 0f. Anything above zero leaves the bars a few pixels tall, and a few
        // pixels of shiver in a silent room is what a user reads as "it is still listening to noise"
        // instead of "the line is straight because I stopped talking".
        quietRoom.forEach { raw ->
            assertEquals("$raw should read as silence", 0f, WaveformGate.level(raw), 0f)
        }
    }

    @Test
    fun `the gate sits below speech and above the room`() {
        // The two ends of the same decision: the loudest thing silence can produce must be under the
        // gate, and the quietest thing a voice can produce must be over it. A gate in between is the
        // whole design; a gate anywhere else is a waveform that never flattens or never moves.
        assertTrue("the noise floor must be masked", WaveformGate.FLOOR > quietRoom.max())
        assertTrue("speech must survive the gate", WaveformGate.FLOOR < speech.min())
    }

    @Test
    fun `speech keeps its own range, so a loud voice is visibly taller`() {
        // Above the gate the curve is the identity: the waveform's job is to distinguish a whisper
        // from a shout, and a gate that also compressed would take that away.
        speech.forEach { raw ->
            assertEquals(raw, WaveformGate.level(raw), 1e-6f)
        }
        assertTrue(WaveformGate.level(0.8f) > WaveformGate.level(0.3f))
    }

    @Test
    fun `the ends of the scale are pinned`() {
        assertEquals(0f, WaveformGate.level(-1f), 0f)
        assertEquals(1f, WaveformGate.level(2f), 1e-6f)
        assertEquals(0f, WaveformGate.level(WaveformGate.FLOOR), 0f)
        assertTrue("one step above the gate is audible", WaveformGate.level(WaveformGate.FLOOR + 0.01f) > 0f)
    }
}
