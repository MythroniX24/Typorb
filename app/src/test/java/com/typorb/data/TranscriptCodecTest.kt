package com.typorb.data

import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript vault is the only place dictated text is written down, so a codec that silently
 * drops or corrupts entries loses a user's work. These cover the round trip and the two ways real
 * data goes wrong: a truncated file and a record written by an older version.
 */
class TranscriptCodecTest {

    private val sample = Transcript(
        id = "abc-1",
        text = "Fix the login crash on cold start",
        timestampMs = 1_726_000_000_000L,
        mode = ContextMode.CODE,
        engine = ProcessingEngine.LOCAL,
        latencyMs = 4_210L,
    )

    @Test
    fun `round trips a single entry`() {
        val decoded = TranscriptCodec.decode(TranscriptCodec.encode(listOf(sample)))

        assertEquals(1, decoded.size)
        assertEquals(sample, decoded.first())
    }

    @Test
    fun `round trips an ordered list and preserves order`() {
        val items = listOf(
            sample,
            sample.copy(id = "abc-2", text = "second", mode = ContextMode.QUICK_CHAT),
            sample.copy(id = "abc-3", text = "third", mode = ContextMode.FORMAL),
        )

        val decoded = TranscriptCodec.decode(TranscriptCodec.encode(items))

        assertEquals(listOf("abc-1", "abc-2", "abc-3"), decoded.map { it.id })
    }

    @Test
    fun `survives text with quotes newlines and emoji`() {
        val awkward = sample.copy(
            text = "He said \"hi\"\nand left 🚪\n\ttabbed — em dash",
        )

        val decoded = TranscriptCodec.decode(TranscriptCodec.encode(listOf(awkward)))

        assertEquals(awkward.text, decoded.single().text)
    }

    @Test
    fun `empty input decodes to an empty list`() {
        assertTrue(TranscriptCodec.decode("").isEmpty())
        assertTrue(TranscriptCodec.decode("[]").isEmpty())
    }

    @Test
    fun `truncated json degrades to empty instead of throwing`() {
        val truncated = TranscriptCodec.encode(listOf(sample)).dropLast(6)

        assertTrue(TranscriptCodec.decode(truncated).isEmpty())
    }

    @Test
    fun `entry with a blank text is skipped but siblings survive`() {
        val encoded = TranscriptCodec.encode(
            listOf(sample.copy(id = "keep"), sample.copy(id = "drop", text = "   ")),
        )

        val decoded = TranscriptCodec.decode(encoded)

        assertEquals(listOf("keep"), decoded.map { it.id })
    }

    @Test
    fun `unknown enum values from a newer app version fall back to defaults`() {
        val future = """
            [{"id":"x","text":"hi","ts":10,"mode":"TELEPATHY","engine":"QUANTUM","latency":5}]
        """.trimIndent()

        val decoded = TranscriptCodec.decode(future).single()

        assertEquals(ContextMode.QUICK_CHAT, decoded.mode)
        assertEquals(ProcessingEngine.CLOUD, decoded.engine)
        assertEquals("hi", decoded.text)
        assertEquals(10L, decoded.timestampMs)
    }
}