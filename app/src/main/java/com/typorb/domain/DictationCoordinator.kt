package com.typorb.domain

import android.util.Log
import com.typorb.audio.AudioRecorder
import com.typorb.data.SettingsRepository
import com.typorb.diagnostics.DictationStages
import com.typorb.diagnostics.OrbDiagnosticsBus
import com.typorb.model.OverlayUiState
import com.typorb.model.ProcessingStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Drives the Typorb pill: tap-to-record, tap-to-process, then hand the finished text to
 * [onTextReady] (which performs accessibility text injection) and collapse back to
 * [OverlayUiState.Idle].
 *
 * The coordinator is UI-agnostic — it owns no views — so the overlay only has to render
 * [state] and forward taps to [onToggle].
 */
class DictationCoordinator(
    private val settings: SettingsRepository,
    private val recorder: AudioRecorder,
    private val engineProvider: (com.typorb.data.TyporbSettings) -> TextProcessingEngine,
    private val scope: CoroutineScope,
    private val onTextReady: suspend (String) -> Unit,
) {

    private val _state = MutableStateFlow<OverlayUiState>(OverlayUiState.Idle)

    /** Rendered directly by the overlay composable. */
    val state: StateFlow<OverlayUiState> = _state.asStateFlow()

    private var meterJob: Job? = null
    private var processingJob: Job? = null
    private var dismissJob: Job? = null
    private val amplitudeWindow = ArrayDeque<Float>()

    /** First tap starts recording, second tap stops and processes; ignored while processing. */
    fun onToggle() {
        when (_state.value) {
            is OverlayUiState.Idle -> startRecording()
            is OverlayUiState.Failed -> startRecording()
            is OverlayUiState.Recording -> stopRecording()
            is OverlayUiState.Processing -> Unit
        }
    }

    /**
     * Types words that are already transcribed, without recording them again.
     *
     * Driven by a long press on the orb. The captured sentence did not land the first time — a field
     * that refused it, a window that went away — and asking the user to say it again would charge them
     * twice for the app's failure. Everything downstream behaves exactly as it does on the normal path,
     * because this *is* that path with the recording step left out: the same injection, the same failure
     * pill, the same clipboard fallback.
     */
    fun retype(text: String) {
        val current = _state.value
        if (current !is OverlayUiState.Idle && current !is OverlayUiState.Failed) return
        dismissJob?.cancel()
        processingJob?.cancel()
        _state.value = OverlayUiState.Processing(ProcessingStage.UPDATING)
        OrbDiagnosticsBus.update {
            it.copy(
                dictationStage = DictationStages.INSERTING,
                dictationError = null,
                dictationTranscriptChars = text.trim().length,
                lastInjection = null,
            )
        }
        processingJob = scope.launch {
            try {
                onTextReady(text)
                _state.value = OverlayUiState.Idle
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.w(TAG, "Retype failed", error)
                failWith(
                    (error as? TyporbException)?.message
                        ?: "Couldn't type that. Tap the field and try again.",
                )
            }
        }
    }

    /** Called by the service when the keyboard closes: abort anything in flight. */
    fun reset() {
        if (_state.value is OverlayUiState.Idle) return
        meterJob?.cancel()
        processingJob?.cancel()
        dismissJob?.cancel()
        amplitudeWindow.clear()
        _state.value = OverlayUiState.Idle
    }

    /** Cancels in-flight work when the service dies. */
    fun release() {
        reset()
        scope.launch { recorder.release() }
    }

    private fun startRecording() {
        dismissJob?.cancel()
        processingJob?.cancel()
        amplitudeWindow.clear()

        // Written before the microphone is opened so a permission failure is still attributed to an
        // engine and a stage in the report rather than to nothing at all.
        OrbDiagnosticsBus.update {
            it.copy(
                dictationEngine = settings.current().engine.name,
                dictationStage = DictationStages.RECORDING,
                dictationError = null,
                dictationAudioMs = 0L,
                dictationTranscriptChars = 0,
                lastInjection = null,
            )
        }

        try {
            recorder.start(scope)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not start recording", error)
            failWith((error as? TyporbException)?.message ?: "Microphone unavailable.")
            return
        }

        meterJob?.cancel()
        meterJob = scope.launch {
            combine(recorder.amplitude, recorder.elapsedMs) { level, elapsed ->
                amplitudeWindow.addLast(level)
                while (amplitudeWindow.size > WAVEFORM_BARS) amplitudeWindow.removeFirst()
                OverlayUiState.Recording(amplitudeWindow.toList(), elapsed)
            }.collect { recording ->
                // Never downgrade an in-flight processing state.
                if (_state.value is OverlayUiState.Recording) _state.value = recording
            }
        }
        _state.value = OverlayUiState.Recording(emptyList(), 0L)
    }

    private fun stopRecording() {
        meterJob?.cancel()
        meterJob = null

        processingJob?.cancel()
        processingJob = scope.launch {
            _state.value = OverlayUiState.Processing(ProcessingStage.TRANSCRIBING)

            val audio = try {
                recorder.stop()
            } catch (error: Throwable) {
                Log.w(TAG, "Recording produced no audio", error)
                failWith((error as? TyporbException)?.message ?: "Didn't catch that.")
                return@launch
            }
            // How much was actually captured. "Didn't catch that" with 12 s of audio and with 0 ms
            // of audio are different bugs, and only this number separates them.
            OrbDiagnosticsBus.update {
                it.copy(
                    dictationStage = DictationStages.TRANSCRIBING,
                    dictationAudioMs = durationMsOf(audio),
                )
            }

            val snapshot = settings.current()
            val engine = engineProvider(snapshot)
            // The engine *instance* that ran, not the setting that was read: this is what proves a
            // switch in the dashboard reached the dictation pipeline.
            OrbDiagnosticsBus.update { it.copy(dictationEngine = engine.engine.name) }

            val result = try {
                engine.process(
                    DictationRequest(
                        pcm = audio,
                        mode = snapshot.contextMode,
                        language = snapshot.language,
                    ),
                ) { stage ->
                    _state.value = OverlayUiState.Processing(stage)
                    OrbDiagnosticsBus.update { it.copy(dictationStage = stageName(stage)) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error)
            }

            result
                .onSuccess { text ->
                    _state.value = OverlayUiState.Processing(ProcessingStage.UPDATING)
                    OrbDiagnosticsBus.update {
                        it.copy(
                            dictationStage = DictationStages.INSERTING,
                            dictationTranscriptChars = text.trim().length,
                        )
                    }
                    try {
                        onTextReady(text)
                        _state.value = OverlayUiState.Idle
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        Log.w(TAG, "Text injection failed", error)
                        // The injection layer knows more than this class does — "text copied, paste it"
                        // and "no field was found" are both far more useful than one generic line.
                        failWith(
                            (error as? TyporbException)?.message
                                ?: "Couldn't type that. Tap the field and try again.",
                        )
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Dictation failed", error)
                    failWith((error as? TyporbException)?.message ?: "Something went wrong.")
                }
        }
    }

    private fun failWith(message: String) {
        _state.value = OverlayUiState.Failed(message)
        OrbDiagnosticsBus.update {
            it.copy(
                dictationStage = DictationStages.FAILED,
                dictationError = message,
            )
        }
        scheduleDismiss()
    }

    /** Whisper's native rate; the recorder captures nothing else. */
    private fun durationMsOf(pcm: ByteArray): Long =
        (pcm.size / AudioRecorder.BYTES_PER_SAMPLE).toLong() * 1_000L / AudioRecorder.SAMPLE_RATE

    private fun stageName(stage: ProcessingStage): String = when (stage) {
        ProcessingStage.TRANSCRIBING -> DictationStages.TRANSCRIBING
        ProcessingStage.FORMATTING -> DictationStages.FORMATTING
        ProcessingStage.UPDATING -> DictationStages.INSERTING
    }

    private fun scheduleDismiss() {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            kotlinx.coroutines.delay(ERROR_VISIBLE_MS)
            _state.value = OverlayUiState.Idle
        }
    }

    companion object {
        private const val TAG = "DictationCoordinator"

        /** Number of bars in the recording waveform. */
        const val WAVEFORM_BARS = 5

        /** How long a failure message stays readable before the pill collapses. */
        const val ERROR_VISIBLE_MS = 2_600L
    }
}