package com.typorb.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The language choice is the difference between Hinglish coming out written down and coming out
 * mangled, and it reaches two engines through two different mechanisms — an HTTP form field for Groq
 * and a decoder prompt token on-device. Both halves are pinned here so a change to either is visible.
 */
class TranscriptionLanguageTest {

    @Test
    fun `auto sends no language so whisper detects it per recording`() {
        // A missing multipart field is how "auto" is expressed, and it is the only setting that
        // handles a sentence that is half Hindi and half English.
        assertNull(TranscriptionLanguage.AUTO.requestCode)
    }

    @Test
    fun `forced languages carry their iso code and prompt token`() {
        assertEquals("en", TranscriptionLanguage.ENGLISH.requestCode)
        assertEquals("<|en|>", TranscriptionLanguage.ENGLISH.promptToken)
        assertEquals("hi", TranscriptionLanguage.HINDI.requestCode)
        assertEquals("<|hi|>", TranscriptionLanguage.HINDI.promptToken)
    }

    @Test
    fun `auto falls back to english on device and that is deliberate`() {
        // The offline engine is a quantised tiny checkpoint with no language-detection pass of its
        // own, so "auto" cannot mean the same thing there. If that ever changes, this test is where
        // the decision has to be restated.
        assertEquals("<|en|>", TranscriptionLanguage.AUTO.promptToken)
    }
}
