package com.typorb.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.typorb.domain.TyporbException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Captures 16 kHz / 16-bit / mono PCM with the platform [AudioRecord] API.
 *
 * The read loop lives on [Dispatchers.IO] inside a scope owned by the caller (the accessibility
 * service), so neither the overlay nor the UI thread ever blocks. Every recording is exposed as a
 * normalised amplitude stream that drives the live waveform.
 */
class AudioRecorder(context: Context) {

    private val appContext = context.applicationContext

    private val _amplitude = MutableStateFlow(0f)
    private val _elapsedMs = MutableStateFlow(0L)

    /** Smoothed, perceptually scaled level in `0f..1f` for the waveform. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /** Wall-clock-ish duration derived from captured frame count. */
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    val isRecording: Boolean get() = readJob != null

    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null
    private var sink: ByteArrayOutputStream? = null
    private var capturedFrames: Long = 0L
    private var autoStopped: Boolean = false

    /** Set once the auto-stop guard trips so the caller can surface "max length reached". */
    var reachedMaxDuration: Boolean = false
        private set

    /**
     * Starts capture. Returns immediately; audio is collected on a background dispatcher.
     *
     * @throws TyporbException when the microphone permission is missing or [AudioRecord] fails to
     *   initialise (device in use by another app, missing hardware, etc.).
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(scope: CoroutineScope) {
        check(readJob == null) { "AudioRecorder is already recording" }
        ensurePermission()

        val bufferSize = resolveBufferSizeBytes()
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw TyporbException("Microphone is unavailable right now.", retryable = true)
        }

        val output = ByteArrayOutputStream(estimatedCapacityBytes())
        sink = output
        capturedFrames = 0L
        autoStopped = false
        reachedMaxDuration = false

        try {
            record.startRecording()
        } catch (error: IllegalStateException) {
            record.release()
            sink = null
            throw TyporbException("Could not start the microphone.", retryable = true, cause = error)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            sink = null
            throw TyporbException("Microphone is unavailable right now.", retryable = true)
        }

        audioRecord = record
        readJob = scope.launch(Dispatchers.IO) {
            readLoop(record, output)
        }
    }

    private suspend fun readLoop(record: AudioRecord, output: ByteArrayOutputStream) {
        val frameBuffer = ShortArray(FRAMES_PER_READ)
        var smoothed = MIN_SMOOTHED_LEVEL
        try {
            while (currentCoroutineContext().isActive) {
                val framesRead = record.read(frameBuffer, 0, frameBuffer.size)
                if (framesRead <= 0) {
                    // ERROR_DEAD_OBJECT / ERROR_INVALID_OPERATION: the stream stalled, keep polling.
                    continue
                }
                output.write(toLittleEndianBytes(frameBuffer, framesRead))
                capturedFrames += framesRead

                val elapsed = capturedFrames * MILLIS_PER_SECOND / SAMPLE_RATE
                _elapsedMs.value = elapsed

                val rawLevel = normalisedLevel(frameBuffer, framesRead)
                smoothed = smoothed + SMOOTHING * (rawLevel - smoothed)
                _amplitude.value = smoothed.coerceIn(MIN_SMOOTHED_LEVEL, 1f)

                if (elapsed >= MAX_DURATION_MS) {
                    reachedMaxDuration = true
                    autoStopped = true
                    break
                }
            }
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Recording loop ended early", error)
        } finally {
            _amplitude.value = MIN_SMOOTHED_LEVEL
        }
    }

    /**
     * Stops capture and returns everything recorded so far as little-endian PCM16.
     *
     * Safe to call after an auto-stop; returns the partial take.
     */
    suspend fun stop(): ByteArray {
        val job = readJob
        val record = audioRecord
        readJob = null
        audioRecord = null
        _amplitude.value = MIN_SMOOTHED_LEVEL
        _elapsedMs.value = 0L

        // Free the blocking read first, then join the loop, then release the hardware.
        runCatching { record?.stop() }
        job?.cancelAndJoin()
        runCatching { record?.release() }

        // The read job has been joined, so the buffer is no longer mutated concurrently.
        val output = sink
        sink = null
        val bytes = output?.toByteArray() ?: ByteArray(0)
        if (bytes.isEmpty() && !autoStopped) throw TyporbException.noAudio()
        return bytes
    }

    /** Hard teardown used when the service is destroyed mid-recording. */
    suspend fun release() {
        runCatching { stop() }
    }

    private fun ensurePermission() {
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw TyporbException(
                message = "Microphone permission is required to dictate.",
                retryable = false,
            )
        }
    }

    private fun estimatedCapacityBytes(): Int =
        (SAMPLE_RATE.toLong() * MAX_DURATION_MS / MILLIS_PER_SECOND * BYTES_PER_SAMPLE).toInt()

    private fun toLittleEndianBytes(samples: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (index in 0 until count) {
            val sample = samples[index].toInt()
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    companion object {
        private const val TAG = "AudioRecorder"

        /** Whisper's native sampling rate. */
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        const val MILLIS_PER_SECOND = 1_000L

        /** Hard cap so a stuck key cannot record forever. */
        const val MAX_DURATION_MS = 90_000L

        private const val FRAMES_PER_READ = 1024
        private const val MIN_BUFFER_SAMPLES = SAMPLE_RATE / 2 * 4
        private const val MIN_SMOOTHED_LEVEL = 0.06f
        private const val SMOOTHING = 0.45f

        /** RMS considered "loud enough" to pin the waveform at full height. */
        private const val RMS_FULL_SCALE = 11_000f

        fun resolveBufferSizeBytes(): Int {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val safeMinimum = if (minimum <= 0) MIN_BUFFER_SAMPLES else minimum
            return maxOf(safeMinimum * 2, FRAMES_PER_READ * BYTES_PER_SAMPLE * 4)
        }

        /** RMS → 0f..1f, square-rooted so quiet speech still moves the bars. */
        internal fun normalisedLevel(samples: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var sumSquares = 0.0
            for (index in 0 until count) {
                val value = samples[index].toDouble()
                sumSquares += value * value
            }
            val rms = sqrt(sumSquares / count).toFloat()
            return sqrt(min(1f, rms / RMS_FULL_SCALE))
        }
    }
}