package com.typorb.local.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal in-place iterative radix-2 Cooley–Tukey FFT.
 *
 * Kept dependency-free and allocation-light because the Whisper front-end runs this 3001 times per
 * dictation on a 400-sample window.
 */
object Fft {

    /** `size` must be a power of two. */
    fun isSupportedSize(size: Int): Boolean = size > 0 && size and (size - 1) == 0

    /**
     * Transforms [real]/[imag] (both length [size]) in place.
     *
     * @param inverse when true the transform is unnormalised (divide by N yourself if needed).
     */
    fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean = false) {
        val size = real.size
        require(size == imag.size) { "real and imaginary buffers must match" }
        require(isSupportedSize(size)) { "FFT size must be a power of two, was $size" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 0 until size - 1) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tempReal = real[i]; real[i] = real[j]; real[j] = tempReal
                val tempImag = imag[i]; imag[i] = imag[j]; imag[j] = tempImag
            }
        }

        // Butterflies.
        var length = 2
        while (length <= size) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / length
            val wReal = cos(angle).toFloat()
            val wImag = sin(angle).toFloat()
            var i = 0
            while (i < size) {
                var curReal = 1f
                var curImag = 0f
                for (k in 0 until length / 2) {
                    val even = i + k
                    val odd = even + length / 2
                    val oddReal = real[odd] * curReal - imag[odd] * curImag
                    val oddImag = real[odd] * curImag + imag[odd] * curReal
                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag
                    val nextReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                }
                i += length
            }
            length = length shl 1
        }
    }

    /** Magnitude spectrum of the first [bins] bins (zero-pads to a power-of-two [size]). */
    fun magnitudes(signal: FloatArray, size: Int, bins: Int): FloatArray {
        val real = FloatArray(size)
        val imag = FloatArray(size)
        signal.copyInto(real, endIndex = minOf(signal.size, size))
        transform(real, imag)
        return FloatArray(bins) { index ->
            val re = real[index]
            val im = imag[index]
            sqrt(re * re + im * im)
        }
    }
}