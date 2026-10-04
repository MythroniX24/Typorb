package com.typorb.local.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Slaney-scale mel filterbank, matching the filterbank Whisper was trained with.
 *
 * Two details matter for fidelity:
 *  * the mel scale switches from linear to logarithmic at 1 kHz, so low frequencies (where speech
 *    formants live) stay well resolved;
 *  * every triangle is area-normalised (`2 / (right - left)`), exactly as `librosa.filters.mel`
 *    does with `slaney=True`. Without it the wider high-frequency triangles accumulate more energy
 *    from the same spectral peak and the log-mel scale drifts away from the one Whisper expects.
 */
class MelFilterBank(
    numberOfMelBins: Int,
    fftSize: Int,
    sampleRate: Int,
    minimumFrequency: Double = 0.0,
    maximumFrequency: Double = sampleRate / 2.0,
) {

    /** Triangular weights, indexed `[melBin][frequencyBin]`. */
    val filters: Array<FloatArray> = buildFilters(
        numberOfMelBins = numberOfMelBins,
        fftSize = fftSize,
        sampleRate = sampleRate,
        minimumFrequency = minimumFrequency,
        maximumFrequency = maximumFrequency,
    )

    /** Projects one magnitude spectrum onto the mel filters. */
    fun apply(magnitudes: FloatArray): FloatArray {
        val bins = magnitudes.size
        return FloatArray(filters.size) { melIndex ->
            val weights = filters[melIndex]
            var sum = 0f
            for (bin in 0 until bins) {
                val weight = weights[bin]
                if (weight != 0f) sum += magnitudes[bin] * weight
            }
            sum
        }
    }

    private fun buildFilters(
        numberOfMelBins: Int,
        fftSize: Int,
        sampleRate: Int,
        minimumFrequency: Double,
        maximumFrequency: Double,
    ): Array<FloatArray> {
        require(numberOfMelBins > 0) { "numberOfMelBins must be positive" }
        val frequencyBins = fftSize / 2 + 1
        val melPoints = FloatArray(numberOfMelBins + 2) { index ->
            melToFrequency(
                hzToMel(minimumFrequency) +
                    (hzToMel(maximumFrequency) - hzToMel(minimumFrequency)) * index / (numberOfMelBins + 1),
            ).toFloat()
        }

        val fftFrequencies = FloatArray(frequencyBins) { index ->
            index * sampleRate.toFloat() / fftSize
        }

        val filters = Array(numberOfMelBins) { FloatArray(frequencyBins) }
        for (melIndex in 0 until numberOfMelBins) {
            val left = melPoints[melIndex]
            val center = melPoints[melIndex + 1]
            val right = melPoints[melIndex + 2]
            // Slaney normalisation: unit-area triangles regardless of their width in Hz.
            val enorm = 2f / (right - left).coerceAtLeast(1e-6f)
            for (bin in 0 until frequencyBins) {
                val frequency = fftFrequencies[bin]
                filters[melIndex][bin] = when {
                    frequency >= left && frequency <= center ->
                        (frequency - left) / (center - left).coerceAtLeast(1e-9f)
                    frequency > center && frequency <= right ->
                        (right - frequency) / (right - center).coerceAtLeast(1e-9f)
                    else -> 0f
                } * enorm
            }
        }
        return filters
    }

    private companion object {
        /** Linear below 1 kHz, logarithmic above. */
        private val LINEAR_HZ_STEP = 200.0 / 3
        private val MIN_LOG_HZ = 1000.0
        private val MIN_LOG_MEL = MIN_LOG_HZ / LINEAR_HZ_STEP
        private val LOG_STEP = ln(6.4) / 27.0

        fun hzToMel(hertz: Double): Double = when {
            hertz < MIN_LOG_HZ -> hertz / LINEAR_HZ_STEP
            else -> MIN_LOG_MEL + ln(hertz / MIN_LOG_HZ) / LOG_STEP
        }

        fun melToFrequency(mel: Double): Double = when {
            mel < MIN_LOG_MEL -> mel * LINEAR_HZ_STEP
            else -> MIN_LOG_HZ * exp(LOG_STEP * (mel - MIN_LOG_MEL))
        }
    }
}

/**
 * Computes Whisper-compatible **log-mel** spectrograms: 80 mel bins, 25 ms window / 10 ms hop,
 * magnitude spectrogram → log10 → floor at `max - 8` → affine normalisation to roughly `[-1, 1]`.
 *
 * Whisper analyses a 400-sample (25 ms) window, but the radix-2 FFT needs a power-of-two length, so
 * each window is zero-padded to 512 and the mel filterbank is built on the padded bin layout (512 →
 * 31.25 Hz per bin instead of 40 Hz). Padding changes the frequency grid, not the filter edges in
 * Hz, so the features stay equivalent to a 400-point DFT up to that resolution difference.
 */
class LogMelSpectrogram(
    private val sampleRate: Int,
    private val fftSize: Int = DEFAULT_FFT_SIZE,
    private val hopLength: Int = DEFAULT_HOP_LENGTH,
    numberOfMelBins: Int = DEFAULT_MEL_BINS,
) {

    private val paddedFftSize = nextPowerOfTwo(fftSize)
    private val filterBank = MelFilterBank(numberOfMelBins, paddedFftSize, sampleRate)
    private val window = hannWindow(fftSize)

    /**
     * @param samples mono PCM in `[-1, 1]`.
     * @return `[numberOfMelBins][frameCount]` mel energies (not yet normalised).
     */
    fun energies(samples: FloatArray): Array<FloatArray> {
        val padded = reflectPad(samples, fftSize / 2)
        val frameCount = maxOf(0, (padded.size - fftSize) / hopLength + 1)
        val frequencyBins = paddedFftSize / 2 + 1
        val real = FloatArray(paddedFftSize)
        val imag = FloatArray(paddedFftSize)
        val spectrum = FloatArray(frequencyBins)

        val out = Array(filterBank.filters.size) { FloatArray(frameCount) }
        for (frame in 0 until frameCount) {
            val start = frame * hopLength
            java.util.Arrays.fill(real, 0f)
            java.util.Arrays.fill(imag, 0f)
            for (index in 0 until fftSize) {
                real[index] = padded[start + index] * window[index]
            }
            Fft.transform(real, imag)
            for (bin in 0 until frequencyBins) {
                val re = real[bin]
                val im = imag[bin]
                spectrum[bin] = sqrt(re * re + im * im)
            }
            val melEnergies = filterBank.apply(spectrum)
            for (melBin in 0 until out.size) {
                out[melBin][frame] = melEnergies[melBin]
            }
        }
        return out
    }

    companion object {
        const val DEFAULT_MEL_BINS = 80
        const val DEFAULT_FFT_SIZE = 400
        const val DEFAULT_HOP_LENGTH = 160
        private const val LOG_FLOOR = 8f
        private const val LOG_OFFSET = 4f
        private const val LOG_SCALE = 4f

        /** Smallest power of two ≥ [value]; the radix-2 FFT requires one. */
        fun nextPowerOfTwo(value: Int): Int {
            var size = 1
            while (size < value) size = size shl 1
            return size
        }

        /** Periodic Hann window, as used by Whisper. */
        fun hannWindow(size: Int): FloatArray = FloatArray(size) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / size)).toFloat()
        }

        /**
         * Whisper's `log_spec = log10(max(spec, 1e-10))`, clamped to `max - 8`, then scaled to
         * `[-1, 1]` with `(x + 4) / 4`.
         */
        fun normalise(energies: Array<FloatArray>, frames: Int): Array<FloatArray> {
            var peak = Float.NEGATIVE_INFINITY
            for (bin in energies) {
                for (frame in 0 until frames) {
                    val value = log10(maxOf(bin[frame], 1e-10f))
                    bin[frame] = value
                    if (value > peak) peak = value
                }
            }
            val floor = peak - LOG_FLOOR
            return Array(energies.size) { binIndex ->
                FloatArray(frames) { frame ->
                    val clamped = maxOf(energies[binIndex][frame], floor)
                    (clamped + LOG_OFFSET) / LOG_SCALE
                }
            }
        }

        /** Mirror padding, matching `numpy.pad(..., mode="reflect")` for centred STFTs. */
        fun reflectPad(samples: FloatArray, pad: Int): FloatArray {
            if (pad <= 0) return samples
            val out = FloatArray(samples.size + pad * 2)
            for (index in out.indices) {
                val source = when {
                    index < pad -> pad - index
                    index < pad + samples.size -> index - pad
                    else -> 2 * samples.size - (index - pad) - 2
                }
                out[index] = samples[source.coerceIn(0, samples.lastIndex.coerceAtLeast(0))]
            }
            return out
        }

        }
}