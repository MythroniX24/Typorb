package com.typorb.local.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The offline front-end is hand-written DSP, so it gets real coverage: a broken FFT or filterbank
 * would otherwise only show up as gibberish text on a device.
 */
class WhisperFeatureTest {

    private val sampleRate = 16_000

    @Test
    fun `fft magnitude peaks at the frequency of a pure tone`() {
        val fftSize = 512
        val toneHz = 1_000.0
        val real = FloatArray(fftSize) { index ->
            sin(2 * PI * toneHz * index / sampleRate).toFloat()
        }
        val imag = FloatArray(fftSize)

        Fft.transform(real, imag)
        val bin = 32 // 16 kHz / 512 = 31.25 Hz per bin
        var peak = 0
        for (index in 1 until fftSize / 2) {
            if (real[index] * real[index] + imag[index] * imag[index] >
                real[peak] * real[peak] + imag[peak] * imag[peak]
            ) {
                peak = index
            }
        }

        assertEquals(bin, peak)
    }

    @Test
    fun `fft of silence is silent`() {
        val real = FloatArray(64)
        val imag = FloatArray(64)
        Fft.transform(real, imag)
        assertTrue(real.all { abs(it) < 1e-6f })
        assertTrue(imag.all { abs(it) < 1e-6f })
    }

    @Test
    fun `frame count matches whisper's 25ms window and 10ms hop`() {
        val spectrogram = LogMelSpectrogram(sampleRate = sampleRate)
        // 1 second → 16 000 samples, reflect-padded by 200 on each side, 400-sample window, hop 160.
        val frames = spectrogram.energies(FloatArray(sampleRate)).first().size
        assertEquals(101, frames)
    }

    @Test
    fun `one second of audio produces 80 mel bins`() {
        val spectrogram = LogMelSpectrogram(sampleRate = sampleRate)
        val energies = spectrogram.energies(FloatArray(sampleRate))
        assertEquals(80, energies.size)
        assertTrue(energies.all { it.size == 101 })
    }

    @Test
    fun `filterbank is area normalised so wide triangles do not dominate`() {
        val fftSize = 512
        val filterBank = MelFilterBank(80, fftSize, sampleRate)
        // A 1 kHz tone must fall in the middle of the bank, not the widest top triangles.
        val toneBin = (0..fftSize / 2).maxByOrNull { filterBank.filters[26][it] }!!
        assertEquals(1000f, toneBin * (sampleRate / fftSize.toFloat()), 1f)

        // Slaney normalisation gives every triangle unit area in Hz, so after sampling onto bins
        // each mel bin carries the same total weight (1 / binWidth). That uniformity is what stops
        // the wide high-frequency triangles from out-scoring a mid-frequency spectral peak.
        val binWidthHz = sampleRate / fftSize.toFloat()
        val expectedSum = 1f / binWidthHz
        val sums = filterBank.filters.drop(1).dropLast(1).map { it.sum() }
        sums.forEachIndexed { index, sum ->
            assertEquals(
                "mel bin ${index + 1} weight",
                expectedSum,
                sum,
                expectedSum * 0.15f,
            )
        }
    }

    @Test
    fun `silence normalises to a flat block at the bottom of the range`() {
        val spectrogram = LogMelSpectrogram(sampleRate = sampleRate)
        val frames = 101
        val normalised = LogMelSpectrogram.normalise(spectrogram.energies(FloatArray(sampleRate)), frames)

        val first = normalised[0][0]
        assertTrue("values should be normalised (expected < -1.4, was $first)", first < -1.4f)
        // Every mel bin sees the same silence, so every cell must be identical.
        val spread = normalised.flatMap { row -> row.toList() }.distinct()
        assertEquals(1, spread.size)
    }

    @Test
    fun `a 1 kHz tone puts energy in mid mel bins and leaves the top bin quiet`() {
        val spectrogram = LogMelSpectrogram(sampleRate = sampleRate)
        val tone = FloatArray(sampleRate) { index ->
            (0.5 * sin(2 * PI * 1_000 * index / sampleRate)).toFloat()
        }
        val frames = 101
        val normalised = LogMelSpectrogram.normalise(spectrogram.energies(tone), frames)

        val averages = FloatArray(80) { bin ->
            (0 until frames).sumOf { normalised[bin][it].toDouble() }.toFloat() / frames
        }
        val strongest = averages.indices.maxByOrNull { averages[it] } ?: -1
        val quietest = averages.indices.minByOrNull { averages[it] } ?: -1

        assertTrue("1 kHz should light up a mid bin, got $strongest", strongest in 20..60)
        assertTrue(
            "top mel bin (8 kHz) should be quiet: ${averages[79]} vs ${averages[strongest]}",
            averages[79] < averages[strongest],
        )
        assertTrue(
            "quietest bin (${averages[quietest]}) should sit below the peak (${averages[strongest]})",
            averages[quietest] < averages[strongest],
        )
    }

    @Test
    fun `normalisation never produces NaN for silent or zero-length input`() {
        val normalised = LogMelSpectrogram.normalise(Array(80) { FloatArray(10) }, 10)
        assertTrue(normalised.all { row -> row.all { !it.isNaN() } })
    }

    @Test
    fun `reflect padding mirrors the signal`() {
        val samples = floatArrayOf(0f, 1f, 2f, 3f)
        val padded = LogMelSpectrogram.reflectPad(samples, 2)
        assertEquals(8, padded.size)
        assertEquals(2f, padded[0], 1e-6f) // numpy "reflect" mirrors without repeating the edge
        assertEquals(1f, padded[1], 1e-6f)
        assertEquals(0f, padded[2], 1e-6f) // original
        assertEquals(3f, padded[5], 1e-6f)
        assertEquals(1f, padded[7], 1e-6f) // mirrored right
    }

    @Test
    fun `hann window is periodic and bounded`() {
        val window = LogMelSpectrogram.hannWindow(400)
        assertEquals(400, window.size)
        assertEquals(0f, window[0], 1e-6f)
        assertTrue(window.all { it in 0f..1f })
        assertTrue(window.any { it > 0.99f })
    }
}