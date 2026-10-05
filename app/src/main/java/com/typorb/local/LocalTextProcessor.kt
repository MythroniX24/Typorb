package com.typorb.local

import com.typorb.domain.DictationRequest
import com.typorb.domain.ProgressReporter
import com.typorb.domain.TextProcessingEngine
import com.typorb.domain.TyporbException
import com.typorb.model.ProcessingEngine
import com.typorb.model.ProcessingStage
import kotlinx.coroutines.CancellationException

/**
 * Engine B — ✈️ Local mode.
 *
 * Runs the quantised Whisper model on-device and cleans the transcript with deterministic Kotlin
 * regexes. Works with no network at all.
 */
class LocalTextProcessor(
    private val whisper: WhisperOnnxEngine,
) : TextProcessingEngine {

    override val engine: ProcessingEngine = ProcessingEngine.LOCAL

    override suspend fun process(
        request: DictationRequest,
        onProgress: ProgressReporter,
    ): Result<String> = try {
        onProgress.onStage(ProcessingStage.TRANSCRIBING)
        val transcript = whisper.transcribe(request.pcm, request.language.promptToken)
        onProgress.onStage(ProcessingStage.FORMATTING)
        val cleaned = LocalTextCleaner.clean(transcript, request.mode)
        if (cleaned.isBlank()) {
            Result.failure(TyporbException.noAudio())
        } else {
            Result.success(cleaned)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(
            if (error is TyporbException) error else TyporbException(
                message = "Offline transcription failed. Try Cloud mode.",
                retryable = true,
                cause = error,
            ),
        )
    }
}