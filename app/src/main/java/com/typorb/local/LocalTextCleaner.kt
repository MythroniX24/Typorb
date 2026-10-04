package com.typorb.local

import com.typorb.model.ContextMode

/**
 * Deterministic post-processing for the offline engine.
 *
 * With no cloud model to reformat with, Local mode relies on native [Regex] passes to remove
 * disfluencies, collapse stutters and repair capitalisation/punctuation — the same guarantees the
 * cloud prompt asks for, minus the semantic formatting.
 */
object LocalTextCleaner {

    /** Word-boundary filler sounds, including stretched spellings such as "ummm" or "uh-huh". */
    private val FILLERS = Regex(
        pattern = "(?i)\\b(?:u{1,2}m+|u{1,2}h+|e{1,2}r+|e{1,2}hm+|a{1,3}h+|h{1,2}m+|n{1,2}h+|mm+)\\b[,]?",
    )

    /** "the the cat" → "the cat". */
    private val REPEATED_WORDS = Regex("(?i)\\b([a-z']+)(\\s+\\1\\b)+")

    /**
 * "soooo" → "so", "yesss" → "yes".
 *
 * Only runs of three or more identical letters are collapsed: "hello", "coffee" and "letter" keep
 * their natural doubles.
 */
    private val ELONGATED_WORDS = Regex("([a-zA-Z])\\1{2,}")

    /** "!!!!" / "??" → "!" / "?". */
    private val REPEATED_PUNCTUATION = Regex("([!?.,;:])\\1+")

    private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([,.!?;:])")

    private val MISSING_SPACE_AFTER_PUNCTUATION = Regex("([,.!?;:])(?=[A-Za-z])")

    private val WHITESPACE = Regex("\\s+")

    private val STANDALONE_I = Regex("(?<![A-Za-z])i(?![A-Za-z])")

    private val SENTENCE_START = Regex("(^|[.!?]\\s+)([a-z])")

    private const val LEADING_SYMBOLS = "-–—:;,"

    /**
     * @param raw transcript straight out of Whisper (or the cloud engine when falling back offline).
     * @param mode kept for API parity and future heuristics; the current pass is mode-agnostic on
     *   purpose so code snippets and URLs survive untouched.
     */
    @Suppress("UNUSED_PARAMETER")
    fun clean(raw: String, mode: ContextMode = ContextMode.QUICK_CHAT): String {
        if (raw.isBlank()) return raw

        var text = raw.replace(ELONGATED_WORDS, "$1")
        text = text.replace(REPEATED_WORDS, "$1")
        text = text.replace(FILLERS, " ")
        text = text.replace('’', '\'').replace('“', '"').replace('”', '"')
        text = text.replace(WHITESPACE, " ")
        text = text.replace(REPEATED_PUNCTUATION, "$1")
        text = text.replace(SPACE_BEFORE_PUNCTUATION, "$1")
        text = text.replace(MISSING_SPACE_AFTER_PUNCTUATION, "$1 ")
        text = text.replace(WHITESPACE, " ").trim()
        text = text.trimStart { it.isWhitespace() || it in LEADING_SYMBOLS }
        text = text.replace(STANDALONE_I, "I")
        text = text.replace(SENTENCE_START) { match ->
            val prefix = match.groupValues[1]
            val letter = match.groupValues[2].uppercase()
            prefix + letter
        }
        return text.trim().trimEnd(',', ';', ':').trim()
    }
}