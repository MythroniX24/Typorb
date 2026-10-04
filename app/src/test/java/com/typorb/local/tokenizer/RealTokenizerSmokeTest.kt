package com.typorb.local.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Validates the parser and byte decoder against the real checkpoint vocabulary.
 *
 * Skipped unless the model assets have been fetched with `sh tools/fetch-whisper-model.sh`, so CI
 * stays green without the 150 MB download.
 */
class RealTokenizerSmokeTest {

    private val candidates = listOf(
        File("src/main/assets/whisper/tokenizer.json"),
        File("app/src/main/assets/whisper/tokenizer.json"),
    )

    private val file: File = candidates.firstOrNull(File::exists) ?: candidates.first()

    @Test
    fun `real whisper tiny tokenizer`() {
        assumeTrue("real tokenizer not present at " + candidates.map(File::getAbsolutePath), file.exists())
        val tokenizer = ByteLevelBpeTokenizer.fromJson(file.readText())

        println("REAL vocabSize=${tokenizer.vocabularySize}")
        println("REAL prompt=${tokenizer.buildPrompt().toList()}")
        println("REAL id of 'Ġthe'=${tokenizer.idOfToken("Ġthe")}")
        println("REAL id of '<|notimestamps|>'=${tokenizer.idOfToken("<|notimestamps|>")}")
        println("REAL decode Ġthe=${tokenizer.decode(listOf(tokenizer.idOfToken("Ġthe")!!))}")
        println("REAL decode multilingual id 51864=${tokenizer.decode(listOf(51864L))}")

        // GPT-2 base (50 258) + Whisper's added tokens up to id 51 864.
        assertEquals(51_865, tokenizer.vocabularySize)
        assertEquals(listOf(50258L, 50259L, 50359L, 50363L), tokenizer.buildPrompt().toList())
        assertEquals(" the", tokenizer.decode(listOf(tokenizer.idOfToken("Ġthe")!!)))
        assertTrue(tokenizer.isStopToken(50257L))
    }
}