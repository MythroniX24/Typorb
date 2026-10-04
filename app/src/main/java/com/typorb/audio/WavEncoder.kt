package com.typorb.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps raw PCM16 samples in a RIFF/WAVE container.
 *
 * The cloud engine uploads a WAV file rather than an opaque PCM blob, so the on-device recorder can
 * feed both engines (Groq multipart upload and ONNX feature extraction) from one byte array.
 */
object WavEncoder {

    private const val HEADER_BYTES = 44
    private const val RIFF = 0x46464952 // "RIFF"
    private const val WAVE = 0x45564157 // "WAVE"
    private const val FMT = 0x20746D66 // "fmt "
    private const val DATA = 0x61746164 // "data"

    private const val PCM_FORMAT = 1

    /** Byte rate for 16 kHz / 16-bit / mono: sample rate * channels * bytes per sample. */
    private const val BYTE_RATE = AudioRecorder.SAMPLE_RATE * AudioRecorder.CHANNELS *
        AudioRecorder.BYTES_PER_SAMPLE

    private const val BLOCK_ALIGN = AudioRecorder.CHANNELS * AudioRecorder.BYTES_PER_SAMPLE

    fun encodePcm16Mono(
        pcm: ByteArray,
        sampleRate: Int = AudioRecorder.SAMPLE_RATE,
    ): ByteArray {
        val dataSize = pcm.size
        val output = ByteArrayOutputStream(HEADER_BYTES + dataSize)
        val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(RIFF)
        buffer.putInt(36 + dataSize)
        buffer.putInt(WAVE)

        buffer.putInt(FMT)
        buffer.putInt(16) // PCM subchunk size
        buffer.putShort(PCM_FORMAT.toShort())
        buffer.putShort(AudioRecorder.CHANNELS.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(BYTE_RATE)
        buffer.putShort(BLOCK_ALIGN.toShort())
        buffer.putShort(AudioRecorder.BITS_PER_SAMPLE.toShort())

        buffer.putInt(DATA)
        buffer.putInt(dataSize)

        output.write(buffer.array())
        output.write(pcm)
        return output.toByteArray()
    }
}