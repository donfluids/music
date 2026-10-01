package com.donfluids.piano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ReverbTest {
    @Test
    fun impulseLeavesADecayingTailThatDiesOut() {
        val rate = 44100
        val r = Reverb(rate)
        r.enabled = true
        val x = FloatArray(rate * 3)
        x[0] = 1f
        val block = 256
        var i = 0
        while (i < x.size) { val n = minOf(block, x.size - i); val c = x.copyOfRange(i, i + n); r.process(c, n); System.arraycopy(c, 0, x, i, n); i += n }
        fun peak(from: Double, to: Double) = x.copyOfRange((from * rate).toInt(), (to * rate).toInt()).maxOf { abs(it) }
        val early = peak(0.05, 0.15)
        val mid = peak(0.4, 0.6)
        val late = peak(2.5, 3.0)
        assertTrue("tail exists: $early", early > 0.01f)
        assertTrue("tail decays: $early > $mid > $late", early > mid && mid > late)
        assertTrue("tail dies: $late", late < 1e-3f)
        assertTrue("never blows up", x.all { abs(it) <= 1f })
    }

    @Test
    fun disabledReverbIsTransparent() {
        val r = Reverb(44100)
        val x = FloatArray(512) { (it % 7) / 7f }
        val copy = x.copyOf()
        r.process(x, x.size)
        assertEquals(copy.toList(), x.toList())
    }

    @Test
    fun volumeScalesOutput() {
        val synth = PianoSynth(44100)
        val loud = FloatArray(4096); val quiet = FloatArray(4096)
        synth.volume = 1f; synth.noteOn(60); synth.render(loud, loud.size)
        val s2 = PianoSynth(44100); s2.volume = 0.5f; s2.noteOn(60); s2.render(quiet, quiet.size)
        for (i in loud.indices) assertEquals(loud[i] * 0.5f, quiet[i], 1e-4f)
    }
}
