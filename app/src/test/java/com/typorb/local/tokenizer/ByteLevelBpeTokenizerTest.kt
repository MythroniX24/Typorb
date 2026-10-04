package com.typorb.local.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the pure-Kotlin half of the offline path.
 *
 * The byte-level BPE decoder is the highest-risk piece: a wrong byte map turns correct token ids
 * into unreadable text on a device, with nothing in the build to complain.
 */
class ByteLevelBpeTokenizerTest {

    /** Minimal stand-in for a real whisper `tokenizer.json`. */
    private val vocab = buildMap {
        put("h", 0)
        put("ello", 1)
        put("Ġw", 2) // "Ġ" is OpenAI's byte-level encoding of a leading space
        put("o", 3)
        put("r", 4)
        put("ld", 5)
        put("<|endoftext|>", 50257)
        put("<|startoftranscript|>", 50258)
        put("<|en|>", 50259)
        put("<|transcribe|>", 50359)
        put("<|notimestamps|>", 50363)
    }

    private val tokenizerJson: String = buildString {
        append("""{"model":{"type":"BPE","vocab":{""")
        append(vocab.entries.joinToString(",") { "\"${it.key}\":${it.value}" })
        append("""},"merges":[]}}""")
    }

    private val tokenizer = ByteLevelBpeTokenizer.fromJson(tokenizerJson)

    @Test
    fun `decodes byte-level tokens back into plain text`() {
        assertEquals("hello world", tokenizer.decode(listOf(0, 1, 2, 3, 4, 5)))
    }

    @Test
    fun `control tokens are skipped when decoding`() {
        assertEquals("hello", tokenizer.decode(listOf(50258, 50259, 0, 1, 50257)))
    }

    @Test
    fun `byte decoder maps printable ascii to itself`() {
        assertEquals('h'.code, ByteLevelBpeTokenizer.byteFor('h'))
        assertEquals(' '.code, ByteLevelBpeTokenizer.byteFor('Ġ'))
        assertEquals(null, ByteLevelBpeTokenizer.byteFor(' '))
    }

    @Test
    fun `multilingual prompt includes the language and transcribe tokens`() {
        assertEquals(
            listOf(50258L, 50259L, 50359L, 50363L),
            tokenizer.buildPrompt().toList(),
        )
    }

    @Test
    fun `english-only checkpoint prompt omits tokens its vocabulary lacks`() {
        val englishOnly = ByteLevelBpeTokenizer.fromJson(
            """{"model":{"vocab":{"h":0,"<|endoftext|>":50257,"<|startoftranscript|>":50258,"<|notimestamps|>":50363},"merges":[]}}""",
        )
        assertEquals(listOf(50258L, 50363L), englishOnly.buildPrompt().toList())
    }

    @Test
    fun `stop tokens include control tokens and out-of-range ids`() {
        assertTrue(tokenizer.isStopToken(50257L))
        assertTrue(tokenizer.isStopToken(50363L))
        assertTrue(tokenizer.isStopToken(99_999L))
        assertFalse(tokenizer.isStopToken(3L))
    }

    @Test
    fun `vocabulary size covers the highest id`() {
        assertEquals(50364, tokenizer.vocabularySize)
    }

    @Test
    fun `non ascii bytes round trip through the byte decoder`() {
        // "é" is 2 UTF-8 bytes: C3 A9. In byte-level BPE they appear as "Ã©".
        val json = """{"model":{"vocab":{"Ã©":0,"!":1},"merges":[]}}"""
        val unicodeTokenizer = ByteLevelBpeTokenizer.fromJson(json)
        assertEquals("é!", unicodeTokenizer.decode(listOf(0, 1)))
    }
}