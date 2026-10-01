package com.donfluids.piano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetronomeAndTheoryTest {
    @Test
    fun metronomeClicksOnTheBeatWithAccentedDownbeats() {
        val rate = 44100
        val m = Metronome(rate)
        m.bpm = 120
        m.beatsPerBar = 3
        m.enabled = true
        val out = FloatArray(rate * 2) // two seconds: four beats at 120
        val block = 256
        var i = 0
        val beats = ArrayList<Pair<Long, Int>>()
        var seen = 0L
        while (i < out.size) {
            val n = minOf(block, out.size - i)
            val chunk = FloatArray(n)
            m.mix(chunk, n, (i.toDouble() / rate * 1e9).toLong())
            System.arraycopy(chunk, 0, out, i, n)
            if (m.beatCount != seen) { seen = m.beatCount; beats += m.beatAtNanos to m.beatIndex }
            i += n
        }
        assertEquals(4, beats.size)
        assertEquals(listOf(0, 1, 2, 0), beats.map { it.second })
        for ((k, b) in beats.withIndex()) assertEquals(k * 0.5, b.first / 1e9, 0.002)
        // Energy at the first click, silence just before the second.
        assertTrue(out.copyOfRange(0, 200).any { kotlin.math.abs(it) > 0.1f })
        assertTrue(out.copyOfRange(rate / 2 - 400, rate / 2 - 1).all { kotlin.math.abs(it) < 1e-3f })
    }

    @Test
    fun scalesAndChords() {
        val g = Theory.Scale(7, minor = false)
        assertEquals(listOf(7, 9, 11, 0, 2, 4, 6), g.pitchClasses)
        assertEquals("G major", g.label)
        assertEquals(listOf("G", "Am", "Bm", "C", "D", "Em", "F#°"), g.chords().map { it.name })
        assertEquals(listOf("I", "ii", "iii", "IV", "V", "vi", "vii°"), g.chords().map { it.numeral })
        val f = Theory.Scale(5, minor = false)
        assertEquals("Bb", f.chords()[3].name)
        val am = Theory.Scale(9, minor = true)
        assertEquals(listOf("Am", "B°", "C", "Dm", "Em", "F", "G"), am.chords().map { it.name })
    }

    @Test
    fun organDoesNotDecayWhileHeld() {
        val rate = 44100
        val synth = PianoSynth(rate)
        synth.tone = PianoSynth.Tone.ORGAN
        synth.noteOn(60)
        val block = FloatArray(256)
        fun rms(seconds: Double): Double {
            var sum = 0.0; var n = 0
            var i = 0
            while (i < seconds * rate) { synth.render(block, 256); for (v in block) { sum += v * v; n++ }; i += 256 }
            return kotlin.math.sqrt(sum / n)
        }
        rms(0.1)
        val early = rms(0.2)
        val late = rms(2.0)
        assertTrue("organ level held: $early vs $late", late > early * 0.95)
        synth.noteOff(60)
        rms(0.5)
        assertEquals(0, synth.activeVoices)
    }
}
