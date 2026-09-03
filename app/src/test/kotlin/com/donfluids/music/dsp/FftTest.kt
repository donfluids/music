package com.donfluids.music.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FftTest {
    @Test
    fun matchesNaiveDft() {
        val n = 64
        val rnd = Random(7)
        val re = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val im = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val expectedRe = DoubleArray(n)
        val expectedIm = DoubleArray(n)
        for (k in 0 until n) {
            for (t in 0 until n) {
                val angle = -2 * PI * k * t / n
                expectedRe[k] += re[t] * cos(angle) - im[t] * sin(angle)
                expectedIm[k] += re[t] * sin(angle) + im[t] * cos(angle)
            }
        }
        Fft(n).transform(re, im)
        for (k in 0 until n) {
            assertEquals(expectedRe[k], re[k].toDouble(), 1e-3)
            assertEquals(expectedIm[k], im[k].toDouble(), 1e-3)
        }
    }

    @Test
    fun sinePeaksAtItsBin() {
        val n = 1024
        val bin = 37
        val re = FloatArray(n) { sin(2 * PI * bin * it / n).toFloat() }
        val im = FloatArray(n)
        Fft(n).transform(re, im)
        var best = 0
        for (k in 1 until n / 2) if (re[k] * re[k] + im[k] * im[k] > re[best] * re[best] + im[best] * im[best]) best = k
        assertEquals(bin, best)
    }
}
