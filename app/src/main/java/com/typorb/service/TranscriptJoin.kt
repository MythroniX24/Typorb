package com.typorb.service

/**
 * How dictated text is combined with what a field already holds.
 *
 * `ACTION_SET_TEXT` replaces a field's entire contents, so a dictation into a half-written message
 * used to delete that message. The existing text therefore has to be read first and carried forward
 * with the transcript.
 *
 * This lives apart from [TextInjector] — which cannot be constructed without a live
 * `AccessibilityService` — so the rule is a pure function a JVM test can cover. That matters because
 * this is the one piece of injection logic where a wrong answer silently eats the user's typing.
 */
object TranscriptJoin {

    /** Separator between a part-written field and the dictated words. */
    const val SEPARATOR = "\n"

    /**
     * Combines what is already in the field with the dictated words.
     *
     * A line break when the field already holds text: the user was part-way through a thought, and
     * running the transcript straight onto the end of it produces one unreadable run-on line.
     */
    fun join(existing: String, addition: String): String = when {
        existing.isBlank() -> addition
        addition.isBlank() -> existing
        else -> existing.trimEnd() + SEPARATOR + addition
    }

    /**
     * Whether [actual] — a field's text read back after a write — shows that [expected] landed.
     *
     * Only letters and digits are compared. Field text comes back carrying the IME's own
     * decorations: composing underlines, autocorrect substitutions, trailing spaces, smart quotes.
     * A strict equality check calls a successful write a failure, and the pipeline then pastes the
     * same words in a second time, which is worse than not writing at all.
     */
    fun landed(actual: String, expected: String): Boolean {
        val needle = normalize(expected)
        return needle.isNotEmpty() && normalize(actual).contains(needle)
    }

    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}
