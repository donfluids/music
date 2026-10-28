package com.donfluids.music.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class HeardNotesTest {

    @Test
    fun theResamplerKeepsATonesPitchAndTime() {
        for (rate in listOf(44100, 48000)) {
            val r = StreamResampler(rate, 22050)
            val f = 1000.0
            val input = FloatArray(rate) { sin(2 * PI * f * it / rate).toFloat() }
            val out = ArrayList<Float>()
            var i = 0
            while (i < input.size) { val n = minOf(512, input.size - i); r.push(input, i, n) { out += it }; i += n }
            // Output n is the input at time n / 22050: compare away from the start.
            var worst = 0.0
            for (n in 2000 until out.size - 10) worst = maxOf(worst, abs(out[n] - sin(2 * PI * f * n / 22050)))
            assertTrue("rate $rate: worst error $worst", worst < 0.01)
            assertTrue("rate $rate: ${out.size} samples", abs(out.size - 22050) < 100)
        }
    }

    @Test
    fun aChordIsHeardAndDatedToWhenItWasPlayed() {
        val rate = 44100
        val h = HeardNotes(TestNotes.weights, rate)
        // C major (C3 E3 G3) for 2 s, then A minor (A2 C3 E3) for 2 s, as plucked strings.
        val audio = FloatArray(rate * 5)
        fun tone(midi: Int, start: Double, end: Double) {
            val f = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            for (i in (start * rate).toInt() until (end * rate).toInt()) {
                val t = i.toDouble() / rate - start
                var s = 0.0
                for (k in 1..6) s += Math.pow(0.6, k - 1.0) * sin(2 * PI * f * k * t)
                audio[i] += (0.08 * s * Math.exp(-t * 1.2)).toFloat()
            }
        }
        for (m in listOf(48, 52, 55)) tone(m, 0.0, 2.0)
        for (m in listOf(45, 48, 52)) tone(m, 2.0, 4.0)
        var i = 0
        while (i < audio.size) { val n = minOf(512, audio.size - i); h.push(audio, i, n); i += n }
        fun top3(from: Double, to: Double): Set<Int> {
            val c = FloatArray(12)
            assertTrue(h.pitchClasses(from, to, c) > 0)
            return c.indices.sortedByDescending { c[it] }.take(3).toSet()
        }
        assertEquals(setOf(0, 4, 7), top3(0.8, 1.8))
        assertEquals(setOf(9, 0, 4), top3(2.3, 3.3))
        // Just after the change the old chord must be gone: the frames are dated right.
        assertEquals(setOf(9, 0, 4), top3(2.1, 2.4))
        assertTrue("delay ${h.delaySeconds}", h.delaySeconds in 0.5..0.8)
    }

    @Test
    fun theResamplerShiftsPitchAndKeepsTime() {
        val rate = 48000
        val r = StreamResampler(rate, 22050)
        r.shiftCents = 40.0
        val f = 1000.0
        val input = FloatArray(rate) { sin(2 * PI * f * it / rate).toFloat() }
        val out = ArrayList<Float>(); val times = ArrayList<Double>()
        var i = 0
        while (i < input.size) { val n = minOf(512, input.size - i); r.push(input, i, n) { out += it; times += r.lastTime }; i += n }
        // Each output is the input at its recorded time, and the tone now reads 40 cents lower.
        var worst = 0.0
        for (n in 2000 until out.size - 10) worst = maxOf(worst, abs(out[n] - sin(2 * PI * f * times[n])))
        assertTrue("worst $worst", worst < 0.01)
        val lowered = f * Math.pow(2.0, -40.0 / 1200)
        var worst2 = 0.0
        for (n in 2000 until out.size - 10) worst2 = maxOf(worst2, abs(out[n] - sin(2 * PI * lowered * n / 22050 + 2 * PI * f * times[0])))
        assertTrue("not lowered: $worst2", worst2 < 0.02)
    }
}
