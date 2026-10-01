package com.donfluids.piano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PianoSynthTest {
    private val rate = 44100

    /** Goertzel power at one frequency. */
    private fun power(x: FloatArray, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in x.indices) { val a = 2 * PI * hz * i / rate; re += x[i] * cos(a); im += x[i] * sin(a) }
        return sqrt(re * re + im * im) / x.size
    }

    private fun render(synth: PianoSynth, seconds: Double): FloatArray {
        val out = FloatArray((seconds * rate).toInt())
        val block = FloatArray(256)
        var i = 0
        while (i < out.size) {
            val n = minOf(256, out.size - i)
            synth.render(block, n)
            System.arraycopy(block, 0, out, i, n)
            i += n
        }
        return out
    }

    @Test
    fun a4SoundsAt440() {
        val synth = PianoSynth(rate)
        synth.noteOn(69)
        val x = render(synth, 0.5)
        val at440 = power(x, 440.0)
        assertTrue("440 Hz should dominate", at440 > 4 * power(x, 466.16) && at440 > 4 * power(x, 415.3))
        // The second harmonic is present but weaker than the fundamental.
        assertTrue(power(x, 880.0) < at440 && power(x, 880.0) > at440 * 0.1)
    }

    @Test
    fun releasedNoteFallsSilentAndFreesItsVoice() {
        val synth = PianoSynth(rate)
        synth.noteOn(60)
        render(synth, 0.2)
        synth.noteOff(60)
        val tail = render(synth, 1.0)
        val last = tail.copyOfRange(tail.size - rate / 10, tail.size)
        assertTrue("tail should be silent", last.all { kotlin.math.abs(it) < 1e-3f })
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun sustainHoldsNotesUntilReleased() {
        val synth = PianoSynth(rate)
        synth.sustain = true
        synth.noteOn(64)
        synth.noteOff(64)
        render(synth, 0.5)
        assertEquals(1, synth.activeVoices)
        synth.sustain = false
        render(synth, 1.0)
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun polyphonyStealsOldestVoiceWithoutFailing() {
        val synth = PianoSynth(rate, maxVoices = 8)
        for (m in 48 until 68) synth.noteOn(m)
        assertEquals(8, synth.activeVoices)
        val x = render(synth, 0.1)
        assertTrue("limiter keeps the mix in range", x.all { it > -1f && it < 1f })
    }

    @Test
    fun keyboardLayoutMapsNotes() {
        val l = KeyboardLayout(baseOctave = 4, octaves = 2, width = 1400f, height = 400f)
        assertEquals(14, l.whiteKeys.size)
        assertEquals(10, l.blackKeys.size)
        assertEquals(60, l.whiteKeys[0].midi)              // C4
        assertEquals(71, l.whiteKeys[6].midi)              // B4
        assertEquals(61, l.blackKeys[0].midi)              // C#4
        assertEquals(61, l.keyAt(105f, 10f)?.midi)         // black key on top
        assertEquals(60, l.keyAt(95f, 300f)?.midi)         // white key below it, left of centre
        assertEquals(62, l.keyAt(105f, 300f)?.midi)        // and D just right of it
        assertEquals("C#4", KeyboardLayout.noteName(61))
        assertEquals("B5", KeyboardLayout.noteName(l.whiteKeys.last().midi))
    }
}
