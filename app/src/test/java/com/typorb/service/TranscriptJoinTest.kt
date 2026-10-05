package com.typorb.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TranscriptJoin] — the two pure rules injection depends on.
 *
 * `join` is what stopped a dictation from *deleting* a half-written message, and `landed` is what
 * stopped a dropped write from being reported as a success. Both are covered here rather than only on
 * a device, because a wrong answer in either one is silent: no crash, no log line, just the user's
 * words going missing.
 */
class TranscriptJoinTest {

    // ---- join -------------------------------------------------------------------------------

    @Test
    fun `an empty field takes the transcript unchanged`() {
        assertEquals("hello", TranscriptJoin.join(existing = "", addition = "hello"))
    }

    @Test
    fun `a whitespace-only field counts as empty`() {
        assertEquals("hello", TranscriptJoin.join(existing = "   \n ", addition = "hello"))
    }

    @Test
    fun `existing text is never discarded`() {
        val joined = TranscriptJoin.join(existing = "Hi there", addition = "how are you")
        assertTrue(joined.contains("Hi there"))
        assertTrue(joined.contains("how are you"))
    }

    @Test
    fun `a part-written field gets the transcript on its own line`() {
        assertEquals(
            "Half a messa\nand the rest",
            TranscriptJoin.join(existing = "Half a messa", addition = "and the rest"),
        )
    }

    @Test
    fun `trailing whitespace in the field does not double up before the newline`() {
        assertEquals("draft\nnext", TranscriptJoin.join(existing = "draft   ", addition = "next"))
    }

    @Test
    fun `a blank transcript leaves the field exactly as it was`() {
        // The other direction: an empty transcript must not rewrite the user's field at all.
        assertEquals("untouched", TranscriptJoin.join(existing = "untouched", addition = "   "))
    }

    // ---- landed -----------------------------------------------------------------------------

    @Test
    fun `an exact match is a landed write`() {
        assertTrue(TranscriptJoin.landed(actual = "hello", expected = "hello"))
    }

    @Test
    fun `a landed write is found among other text`() {
        // The field keeps whatever the user had already typed, so the read-back is never just the
        // transcript on its own.
        assertTrue(TranscriptJoin.landed(actual = "Hi there\nhow are you", expected = "how are you"))
    }

    @Test
    fun `an unchanged field is not a landed write`() {
        assertFalse(TranscriptJoin.landed(actual = "how are you", expected = "hello"))
    }

    @Test
    fun `an empty field is not a landed write`() {
        // The bug this exists to catch: an app that accepts ACTION_SET_TEXT, reports success, and
        // writes nothing leaves the read-back empty. Only a non-empty transcript can count as landed.
        assertFalse(TranscriptJoin.landed(actual = "", expected = "hello"))
    }

    @Test
    fun `a blank transcript never counts as landed`() {
        assertFalse(TranscriptJoin.landed(actual = "anything at all", expected = "   "))
    }

    @Test
    fun `case and punctuation differences do not mask a landed write`() {
        // The IME rewrites quotes and capitalisation on the way in; a strict check would call these
        // failures and paste the same words in a second time.
        assertTrue(TranscriptJoin.landed(actual = "Hello, world.", expected = "hello world"))
    }

    @Test
    fun `normalising strips both sides symmetrically`() {
        assertTrue(TranscriptJoin.landed(actual = "  HELLO  ", expected = "hello"))
        assertTrue(TranscriptJoin.landed(actual = "hello", expected = "  HeLLo  "))
    }
}
