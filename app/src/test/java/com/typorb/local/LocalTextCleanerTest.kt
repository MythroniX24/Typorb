package com.typorb.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline cleanup pass is the only formatting Local mode has, so it gets real test coverage.
 *
 * Expected values are written the way a person would want the text to read: fillers gone, stutters
 * collapsed, punctuation and capitalisation repaired — and nothing invented.
 */
class LocalTextCleanerTest {

    @Test
    fun `removes filler words`() {
        assertEquals(
            "So we shipped the release today",
            LocalTextCleaner.clean("umm so we uh shipped the release today"),
        )
    }

    @Test
    fun `removes elongated fillers`() {
        assertEquals("Let's start", LocalTextCleaner.clean("ummm let's ahh start"))
    }

    @Test
    fun `collapses adjacent repeated words`() {
        assertEquals(
            "That is that is the plan",
            LocalTextCleaner.clean("that is that that is the the plan"),
        )
    }

    @Test
    fun `collapses elongated words but keeps natural doubles`() {
        assertEquals(
            "Yes that is so good, the coffee is ready",
            LocalTextCleaner.clean("yesss that is soooo good , the coffee is ready"),
        )
    }

    @Test
    fun `repairs punctuation spacing`() {
        assertEquals(
            "Hello, world. How are you?",
            LocalTextCleaner.clean("hello ,world .how are you ?"),
        )
    }

    @Test
    fun `collapses repeated punctuation`() {
        assertEquals("Wait! Really?", LocalTextCleaner.clean("wait!!! really???"))
    }

    @Test
    fun `capitalises the pronoun i`() {
        assertEquals("I think it works", LocalTextCleaner.clean("i think it works"))
    }

    @Test
    fun `capitalises sentence starts`() {
        assertEquals(
            "Shipped it. The tests pass",
            LocalTextCleaner.clean("shipped it . the tests pass"),
        )
    }

    @Test
    fun `blank input stays blank`() {
        assertTrue(LocalTextCleaner.clean("   ").isBlank())
    }

    @Test
    fun `normalises smart quotes`() {
        val cleaned = LocalTextCleaner.clean("“hello”")
        assertFalse(cleaned.contains('“'))
        assertEquals("\"hello\"", cleaned)
    }
}