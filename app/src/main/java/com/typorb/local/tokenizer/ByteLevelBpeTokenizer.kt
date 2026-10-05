package com.typorb.local.tokenizer

import org.json.JSONObject

/**
 * Byte-level BPE decoder for Whisper's GPT-2 style vocabulary.
 *
 * Whisper tokens are the raw byte-pair-encoded text produced by OpenAI's `bytes_to_unicode` map, so
 * decoding is: id → token string → per-character bytes → UTF-8 text. Only decoding and the fixed
 * prompt tokens are needed, so the merge table is intentionally ignored.
 */
class ByteLevelBpeTokenizer private constructor(
    private val tokenById: Array<String?>,
    private val idByToken: Map<String, Long>,
) {

    /** Number of tokens the decoder head can emit; also the vocabulary size. */
    val vocabularySize: Int = tokenById.size

    /**
     * Builds the decoder prompt.
     *
     * Multilingual checkpoints take `<|startoftranscript|><|en|><|transcribe|><|notimestamps|>`;
     * English-only checkpoints have no `<|en|>`/`<|transcribe|>` tokens, so those are included only
     * when the vocabulary actually defines them.
     *
     * @param languageToken token for the language the speaker is expected to use, e.g. `"<|hi|>"`.
     *   Any token the vocabulary does not define is skipped rather than substituted, so a checkpoint
     *   that lacks the language still decodes.
     */
    fun buildPrompt(languageToken: String = SPECIAL_ENGLISH): LongArray {
        val start = idByToken[SPECIAL_START_OF_TRANSCRIPT] ?: idByToken[SPECIAL_END_OF_TEXT] ?: 0L
        val prompt = mutableListOf(start)
        idByToken[languageToken]?.let(prompt::add)
        idByToken[SPECIAL_TRANSCRIBE]?.let(prompt::add)
        prompt += idByToken[SPECIAL_NO_TIMESTAMPS] ?: start
        return prompt.toLongArray()
    }

    /** Token id for a raw token string, exposed for diagnostics and tests. */
    internal fun idOfToken(token: String): Long? = idByToken[token]

    /** Greedy-loop stop condition: control tokens (and any `<|…|>` token) end the utterance. */
    fun isStopToken(id: Long): Boolean {
        if (id < 0 || id >= tokenById.size) return true
        if (id >= SPECIAL_TOKEN_FLOOR) return true
        return isControlText(tokenById[id.toInt()])
    }

    /** Decodes model output token ids into display text, dropping control tokens. */
    fun decode(ids: List<Long>): String {
        val bytes = ArrayList<Byte>(ids.size * 4)
        for (id in ids) {
            if (id < 0 || id >= tokenById.size) continue
            val token = tokenById[id.toInt()] ?: continue
            if (isControlText(token)) continue
            for (character in token) {
                val byte = BYTE_DECODER[character] ?: continue
                bytes += byte.toByte()
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun isControlText(token: String?): Boolean =
        token != null && token.length > 4 && token.startsWith("<|") && token.endsWith("|>")

    companion object {
        const val SPECIAL_END_OF_TEXT = "<|endoftext|>"
        const val SPECIAL_START_OF_TRANSCRIPT = "<|startoftranscript|>"
        const val SPECIAL_ENGLISH = "<|en|>"
        const val SPECIAL_TRANSCRIBE = "<|transcribe|>"
        const val SPECIAL_NO_TIMESTAMPS = "<|notimestamps|>"

        /** Ids at or above this threshold are control tokens in Whisper's vocabulary. */
        const val SPECIAL_TOKEN_FLOOR = 50257L

        /** Inverse of OpenAI's `bytes_to_unicode()`: unicode char → original byte. */
        private val BYTE_DECODER: Map<Char, Int> = buildByteDecoder()

        /** Exposed for tests: the byte a token character represents. */
        internal fun byteFor(character: Char): Int? = BYTE_DECODER[character]

        /**
         * Parses a HuggingFace `tokenizer.json`.
         *
         * Only reading `model.vocab` is not enough: a whisper checkpoint stores the GPT-2 base
         * vocabulary there (50 258 entries) and puts everything Whisper added — the control tokens
         * *and* the multilingual tokens — in `added_tokens`, with ids running contiguously up to
         * 51 864. Both halves are merged here, otherwise the decoder prompt collapses to
         * `<|endoftext|>` and every non-ASCII id decodes to nothing.
         */
        fun fromJson(json: String): ByteLevelBpeTokenizer {
            val root = JSONObject(json)
            val vocab = root.getJSONObject("model").getJSONObject("vocab")
            val tokenById = HashMap<Int, String>(vocab.length() * 2)

            val keys = vocab.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                tokenById[vocab.getInt(token)] = token
            }

            root.optJSONArray("added_tokens")?.let { added ->
                for (index in 0 until added.length()) {
                    val entry = added.optJSONObject(index) ?: continue
                    if (!entry.has("id")) continue
                    val content = entry.optString("content")
                    // Some entries repeat an existing id with an empty content; keep the real token.
                    if (content.isEmpty()) continue
                    tokenById[entry.getInt("id")] = content
                }
            }

            val tokens = arrayOfNulls<String>((tokenById.keys.maxOrNull() ?: 0) + 1)
            tokenById.forEach { (id, token) -> tokens[id] = token }
            val idByToken = HashMap<String, Long>(tokens.size)
            tokens.forEachIndexed { id, token ->
                if (token != null) idByToken[token] = id.toLong()
            }
            return ByteLevelBpeTokenizer(tokens, idByToken)
        }

        /**
         * Inverts OpenAI's `bytes_to_unicode()`: every byte in the printable set maps to itself, and
         * the 68 remaining bytes map to U+0100, U+0101, … in ascending byte order.
         */
        private fun buildByteDecoder(): Map<Char, Int> {
            val decoder = HashMap<Char, Int>(256)
            fun isMappedByte(value: Int): Boolean =
                value in 0x21..0x7E || value in 0xA1..0xAC || value in 0xAE..0xFF

            for (value in 0 until 256) {
                if (isMappedByte(value)) decoder[value.toChar()] = value
            }
            var nextCodePoint = 0x100
            for (value in 0 until 256) {
                if (!isMappedByte(value)) decoder[(nextCodePoint++).toChar()] = value
            }
            return decoder
        }
    }
}