package com.typorb.domain

import android.util.Log
import com.typorb.audio.AudioRecorder
import com.typorb.data.SettingsRepository
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

        try {
            recorder.start(scope)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not start recording", error)
            _state.value = OverlayUiState.Failed(
                (error as? TyporbException)?.message ?: "Microphone unavailable.",
            )
            scheduleDismiss()
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

            val snapshot = settings.current()
            val engine = engineProvider(snapshot)

            val result = try {
                engine.process(
                    DictationRequest(pcm = audio, mode = snapshot.contextMode),
                ) { stage -> _state.value = OverlayUiState.Processing(stage) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error)
            }

            result
                .onSuccess { text ->
                    _state.value = OverlayUiState.Processing(ProcessingStage.UPDATING)
                    try {
                        onTextReady(text)
                        _state.value = OverlayUiState.Idle
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        Log.w(TAG, "Text injection failed", error)
                        failWith("Couldn't type that. Try the field again.")
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
        scheduleDismiss()
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