package com.typorb.domain

import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import com.typorb.model.ProcessingStage

/**
 * One dictation cycle: raw 16 kHz mono PCM plus the user's formatting preferences.
 *
 * Intentionally a regular class rather than a data class because [pcm] is a large ByteArray that
 * should not participate in generated equals/hashCode.
 */
class DictationRequest(
    val pcm: ByteArray,
    val mode: ContextMode,
    /** Optional textual hint (e.g. an app hint about the focused field). Never sent by default. */
    val fieldHint: String? = null,
)

/** A pipeline step reported back to the UI so the processing pill can describe what is happening. */
fun interface ProgressReporter {
    fun onStage(stage: ProcessingStage)
}

/**
 * A transcription + formatting backend. Implementations must be safe to call from any coroutine
 * context and must never touch the Android main thread.
 */
interface TextProcessingEngine {

    /** Which engine this is, used for UI labelling and telemetry. */
    val engine: ProcessingEngine

    /**
     * Transcribes and formats [request].
     *
     * @return the final text to inject, or a failure whose [TyporbException.retryable] flag tells the
     *   UI whether offering a retry makes sense.
     */
    suspend fun process(
        request: DictationRequest,
        onProgress: ProgressReporter = ProgressReporter { },
    ): Result<String>
}

/**
 * A single, user-presentable failure reason.
 *
 * @param retryable true when the failure is transient (rate limit, network blip) and the user can
 *   usefully try again.
 */
class TyporbException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause) {

    companion object {
        fun missingApiKey(): TyporbException = TyporbException(
            message = "Add your Groq API key in Typorb to use Cloud mode.",
            retryable = false,
        )

        fun noAudio(): TyporbException = TyporbException(
            message = "Didn't catch that — hold the button and speak.",
            retryable = true,
        )

        fun localModelUnavailable(cause: Throwable): TyporbException = TyporbException(
            message = "Offline model unavailable. Place whisper-tiny.onnx in assets/whisper/.",
            retryable = false,
            cause = cause,
        )
    }
}