package com.typorb.cloud

import com.typorb.audio.WavEncoder
import com.typorb.cloud.dto.ChatCompletionRequest
import com.typorb.cloud.dto.ChatMessage
import com.typorb.cloud.groq.GroqApi
import com.typorb.cloud.groq.GroqErrors
import com.typorb.domain.DictationRequest
import com.typorb.domain.ProgressReporter
import com.typorb.domain.TextProcessingEngine
import com.typorb.domain.TyporbException
import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import com.typorb.model.ProcessingStage
import com.typorb.util.retrying
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Engine A — ☁️ Cloud mode.
 *
 * 1. The captured WAV is uploaded to Groq's `whisper-large-v3` endpoint over multipart HTTP.
 * 2. The raw transcript is passed to `llama-3.1-8b-instant`, which removes filler words, fixes
 *    punctuation and reshapes the text into the user's selected [ContextMode].
 *
 * Both calls retry with exponential backoff when Groq rate limits or returns a 5xx.
 */
class CloudTextProcessor(
    private val api: GroqApi,
    private val apiKeyProvider: () -> String,
    private val transcribeModel: String,
    private val chatModel: String,
) : TextProcessingEngine {

    override val engine: ProcessingEngine = ProcessingEngine.CLOUD

    override suspend fun process(
        request: DictationRequest,
        onProgress: ProgressReporter,
    ): Result<String> = runCatching {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) throw TyporbException.missingApiKey()
        val authorization = "Bearer $apiKey"

        onProgress.onStage(ProcessingStage.TRANSCRIBING)
        val rawTranscript = transcribe(authorization, request)

        onProgress.onStage(ProcessingStage.FORMATTING)
        format(authorization, rawTranscript, request.mode, request.fieldHint)
    }.recoverCatching { throwable ->
        throw GroqErrors.toTyporbException(throwable)
    }

    private suspend fun transcribe(authorization: String, request: DictationRequest): String {
        val wav = WavEncoder.encodePcm16Mono(request.pcm)
        val filePart = MultipartBody.Part.createFormData(
            "file",
            "typorb-dictation.wav",
            wav.toRequestBody(AUDIO_WAV),
        )

        val transcript = retrying(
            maxAttempts = MAX_ATTEMPTS,
            shouldRetry = GroqErrors::isRetryable,
        ) {
            api.transcribe(
                authorization = authorization,
                file = filePart,
                model = transcribeModel.asFormPart(),
                responseFormat = "json".asFormPart(),
                language = DEFAULT_LANGUAGE.asFormPart(),
                temperature = TEMPERATURE.asFormPart(),
            ).text
        }

        return transcript?.trim().orEmpty().ifEmpty { throw TyporbException.noAudio() }
    }

    private suspend fun format(
        authorization: String,
        rawTranscript: String,
        mode: ContextMode,
        fieldHint: String?,
    ): String {
        val userContent = buildString {
            append(rawTranscript)
            if (!fieldHint.isNullOrBlank()) {
                append("\n\nContext: ").append(fieldHint.trim())
            }
        }

        val response = retrying(
            maxAttempts = MAX_ATTEMPTS,
            shouldRetry = GroqErrors::isRetryable,
        ) {
            api.complete(
                authorization = authorization,
                request = ChatCompletionRequest(
                    model = chatModel,
                    messages = listOf(
                        ChatMessage(role = SYSTEM_ROLE, content = systemPrompt(mode)),
                        ChatMessage(role = USER_ROLE, content = userContent),
                    ),
                ),
            )
        }

        val formatted = response.choices?.firstNotNullOfOrNull { it.message?.content }
            ?.trim()
            .orEmpty()

        if (formatted.isEmpty()) {
            // Never drop the user's words because formatting failed.
            return rawTranscript.trim()
        }
        return stripWrappingQuotes(formatted)
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"
        private const val DEFAULT_LANGUAGE = "en"
        private const val TEMPERATURE = "0"

        private val AUDIO_WAV = "audio/wav".toMediaType()
        private val TEXT_PLAIN = "text/plain".toMediaType()

        private val FILLER_WORDS = listOf("umm", "uh", "er", "ah", "aah", "hmm", "erm", "uhh um")

        fun systemPrompt(mode: ContextMode): String = buildString {
            append("You are the text engine of a voice-typing overlay. ")
            append("You receive a raw speech-to-text transcript and return ONLY the cleaned final text ")
            append("that should be typed into the user's text field.\n")
            append("Rules:\n")
            append("1. Remove disfluencies and filler words such as: ")
            append(FILLER_WORDS.joinToString(", "))
            append(".\n")
            append("2. Fix punctuation, capitalisation and spacing.\n")
            append("3. Never summarise, translate, answer or add commentary.\n")
            append("4. Never wrap the result in quotes or markdown code fences.\n")
            append("5. Keep the speaker's original wording and meaning; only clean and reformat it.\n")
            append("Output format for this request — ${mode.label}: ${mode.systemInstruction}")
        }

        private fun String.asFormPart() = toRequestBody(TEXT_PLAIN)

        private fun stripWrappingQuotes(value: String): String {
            val trimmed = value.trim()
            val first = trimmed.firstOrNull() ?: return trimmed
            val last = trimmed.last()
            val wrapped = (first == '"' && last == '"') ||
                (first == '“' && last == '”') ||
                (first == '`' && last == '`')
            return if (wrapped) trimmed.trim('"', '“', '”', '`').trim() else trimmed
        }
    }
}