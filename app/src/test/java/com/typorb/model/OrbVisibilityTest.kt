package com.typorb.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that decide whether the orb exists, asked without a phone.
 *
 * They are the whole reason the orb appears or does not, and each half has already been the
 * difference between "nothing shows" and a working build, so they are asserted rather than reasoned
 * about.
 */
class OrbVisibilityTest {

    @Test
    fun `a visible keyboard is enough on its own`() {
        assertTrue(orbVisibility(keyboardVisible = true, state = OverlayUiState.Idle))
        assertTrue(orbVisibility(keyboardVisible = true, state = OverlayUiState.Failed("nope")))
    }

    @Test
    fun `a dictation in flight survives the keyboard leaving`() {
        // The microphone stays open while the user talks: an app that dismisses its keyboard
        // mid-take must not take away the only button that can stop the recording.
        assertTrue(
            orbVisibility(
                keyboardVisible = false,
                state = OverlayUiState.Recording(amplitudes = listOf(0.4f), elapsedMs = 900L),
            ),
        )
        assertTrue(
            orbVisibility(
                keyboardVisible = false,
                state = OverlayUiState.Processing(ProcessingStage.TRANSCRIBING),
            ),
        )
    }

    @Test
    fun `nothing rests on screen without a keyboard`() {
        assertFalse(orbVisibility(keyboardVisible = false, state = OverlayUiState.Idle))
        // A captured error is a toast plus the clipboard; parking it over the home screen is the
        // failure mode the reference implementations document as their own bug.
        assertFalse(orbVisibility(keyboardVisible = false, state = OverlayUiState.Failed("no mic")))
    }

    @Test
    fun `only work in flight counts as pinned`() {
        assertTrue(OverlayUiState.Recording(amplitudes = emptyList(), elapsedMs = 0L).pinsOrb())
        assertTrue(OverlayUiState.Processing(ProcessingStage.FORMATTING).pinsOrb())
        assertFalse(OverlayUiState.Idle.pinsOrb())
        assertFalse(OverlayUiState.Failed("x").pinsOrb())
    }
}
