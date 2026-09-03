package com.donfluids.music.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT.
 *
 * Pure Kotlin so it can be unit-tested on the JVM. Sizes must be a power of two.
 */
class Fft(val size: Int) {
    private val levels: Int
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val bitReversed = IntArray(size)

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two, got $size" }
        levels = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size / 2) {
            val angle = 2.0 * PI * i / size
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        for (i in 0 until size) {
            bitReversed[i] = Integer.reverse(i) ushr (32 - levels)
        }
    }

    /** Forward transform. [re] and [im] are both length [size] and are overwritten. */
    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size)
        for (i in 0 until size) {
            val j = bitReversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var halfSize = 1
        while (halfSize < size) {
            val fullSize = halfSize * 2
            val tableStep = size / fullSize
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + halfSize) {
                    val l = j + halfSize
                    val tRe = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val tIm = -re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tRe
                    im[l] = im[j] - tIm
                    re[j] += tRe
                    im[j] += tIm
                    k += tableStep
                }
                i += fullSize
            }
            halfSize = fullSize
        }
    }

    /**
     * Convenience: windowed magnitude spectrum of a real signal.
     * Writes size/2 + 1 magnitudes into [out], scaled so a full-scale sine peaks near 0.5.
     */
    fun magnitudeSpectrum(input: FloatArray, window: FloatArray, re: FloatArray, im: FloatArray, out: FloatArray) {
        for (i in 0 until size) {
            re[i] = input[i] * window[i]
            im[i] = 0f
        }
        transform(re, im)
        val scale = 2f / size
        for (k in 0..size / 2) {
            out[k] = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k]) * scale
        }
    }

    companion object {
        fun hannWindow(size: Int): FloatArray =
            FloatArray(size) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / size)).toFloat() }
    }
}
