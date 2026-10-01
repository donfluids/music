package com.donfluids.piano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Objective sound-quality checks on the synthesiser, reverb and metronome. */
class SoundQualityTest {
    private val rate = 44100

    @Test
    fun tuningIsWithinTwoCentsAcrossTheKeyboard() {
        for (midi in 36..96 step 6) {
            val synth = PianoSynth(rate)
            synth.noteOn(midi)
            val x = Audio.render(synth, 1.0).copyOfRange(rate / 10, rate) // skip the attack
            val expected = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            val got = Audio.peakHz(x, expected, rate)
            val err = Audio.cents(got, expected)
            assertTrue("midi $midi: ${"%.1f".format(got)} Hz is ${"%.1f".format(err)} cents off", abs(err) < 2.0)
        }
    }

    @Test
    fun noClicksOnAttackOrRelease() {
        for (tone in PianoSynth.Tone.entries) {
            val synth = PianoSynth(rate)
            synth.tone = tone
            synth.noteOn(57)
            val on = Audio.render(synth, 0.3)
            synth.noteOff(57)
            val off = Audio.render(synth, 0.3)
            // A step within the first millisecond must be no larger than the steady-state waveform's own steps.
            val steady = Audio.maxStep(on, rate / 10, rate / 5)
            assertTrue("$tone attack click: ${Audio.maxStep(on, 1, rate / 1000)} vs steady $steady", Audio.maxStep(on, 1, rate / 1000) <= steady * 1.1f + 1e-4f)
            assertTrue("$tone release click", Audio.maxStep(off, 1, rate / 1000) <= steady * 1.1f + 1e-4f)
            assertTrue("$tone release continuous at the join", abs(off[0] - on.last()) <= steady * 1.1f + 1e-4f)
        }
    }

    @Test
    fun retriggeringARingingNoteDoesNotClick() {
        val synth = PianoSynth(rate)
        synth.noteOn(60)
        val a = Audio.render(synth, 0.3)
        synth.noteOn(60) // same key again while it still rings
        val b = Audio.render(synth, 0.05)
        val steady = Audio.maxStep(a, rate / 10, rate / 5)
        assertTrue("retrigger click: ${abs(b[0] - a.last())} vs $steady", abs(b[0] - a.last()) <= steady * 1.5f + 1e-4f)
        assertTrue(Audio.maxStep(b, 1, b.size) <= steady * 1.5f + 1e-4f)
    }

    @Test
    fun tenNoteChordAtFullVelocityNeitherClipsNorVanishes() {
        val synth = PianoSynth(rate)
        synth.volume = 1f
        for (m in listOf(48, 52, 55, 59, 62, 64, 67, 71, 74, 76)) synth.noteOn(m, 1f)
        val x = Audio.render(synth, 0.5)
        val p = Audio.peak(x)
        assertTrue("peak $p", p < 0.96f && p > 0.4f)
        // Four notes must stay in the limiter's near-linear region: their peak is close to the
        // plain sum of the same notes rendered alone.
        val four = listOf(48, 55, 60, 64)
        val together = PianoSynth(rate).also { it.volume = 1f; four.forEach { m -> it.noteOn(m, 1f) } }
        val mixed = Audio.render(together, 0.2)
        val separate = FloatArray(mixed.size)
        for (m in four) { val one = PianoSynth(rate).also { it.volume = 1f; it.noteOn(m, 1f) }; val y = Audio.render(one, 0.2); for (i in y.indices) separate[i] += y[i] }
        val ratio = Audio.rms(mixed) / Audio.rms(separate)
        assertTrue("four-note chord compressed to ${"%.2f".format(ratio)} of linear", ratio > 0.85)
        // A single note is clearly quieter than ten, so the limiter is not squashing everything flat.
        val one = PianoSynth(rate).also { it.volume = 1f; it.noteOn(60, 1f) }
        assertTrue(Audio.rms(Audio.render(one, 0.5)) < Audio.rms(x) * 0.7)
    }

    @Test
    fun highNotesDoNotAlias() {
        val synth = PianoSynth(rate)
        synth.noteOn(100) // about 2637 Hz; its 6th harmonic would be 15.8 kHz, 8th beyond Nyquist
        val x = Audio.render(synth, 0.5)
        val fundamental = Audio.power(x, 2637.02, rate)
        // Nothing meaningful above 12 kHz: scan a few bins.
        val high = (12000..21000 step 500).maxOf { Audio.power(x, it.toDouble(), rate) }
        assertTrue("high band $high vs fundamental $fundamental", high < fundamental * 0.03)
    }

    @Test
    fun silenceIsExactlySilentAndThereIsNoDcOffset() {
        val synth = PianoSynth(rate)
        assertTrue(Audio.render(synth, 0.2).all { it == 0f })
        synth.noteOn(45)
        val x = Audio.render(synth, 1.0)
        val mean = x.sum() / x.size
        assertTrue("dc $mean", abs(mean) < 1e-3)
    }

    @Test
    fun pianoDecaysSmoothlyAndBassRingsLongerThanTreble() {
        fun decayCurve(midi: Int): List<Double> {
            val synth = PianoSynth(rate)
            synth.noteOn(midi)
            val x = Audio.render(synth, 3.0)
            val w = rate / 4
            return (0 until 12).map { Audio.rms(x, it * w, (it + 1) * w) }
        }
        val bass = decayCurve(40)
        val treble = decayCurve(84)
        for (c in listOf(bass, treble)) for (i in 1 until c.size) assertTrue("non-monotonic decay at $i: $c", c[i] <= c[i - 1] * 1.02)
        assertTrue("bass ${bass[8] / bass[0]} vs treble ${treble[8] / treble[0]}", bass[8] / bass[0] > treble[8] / treble[0] * 1.5)
    }

    @Test
    fun reverbTailFallsSixtyDecibelsWithinThreeSeconds() {
        val r = Reverb(rate)
        r.enabled = true; r.mix = 1f
        val x = FloatArray(rate * 3)
        x[0] = 1f
        var i = 0
        while (i < x.size) { val n = minOf(256, x.size - i); val c = x.copyOfRange(i, i + n); r.process(c, n); System.arraycopy(c, 0, x, i, n); i += n }
        val early = Audio.rms(x, 0, rate / 10)
        val late = Audio.rms(x, (2.9 * rate).toInt(), x.size)
        val dropDb = 20 * Math.log10(early / late)
        assertTrue("tail dropped only ${"%.1f".format(dropDb)} dB", dropDb > 60)
    }

    @Test
    fun metronomeClickIsShortAndCentred() {
        val m = Metronome(rate)
        m.bpm = 60; m.enabled = true
        val x = FloatArray(rate)
        m.mix(x, x.size, 0L)
        val last = x.indexOfLast { abs(it) > 1e-3f }
        assertTrue("click length ${last.toDouble() / rate} s", last < rate * 0.03)
        assertTrue(abs(x.sum() / x.size) < 1e-4)
    }

    @Test
    fun soundIsTheSameAtFortyEightKilohertz() {
        val a = PianoSynth(44100).also { it.noteOn(64) }
        val b = PianoSynth(48000).also { it.noteOn(64) }
        val xa = Audio.render(a, 1.0)
        val xb = Audio.render(b, 1.0)
        assertEquals(Audio.rms(xa), Audio.rms(xb), Audio.rms(xa) * 0.05)
        val f = 329.63
        val ra = Audio.power(xa, 2 * f, 44100) / Audio.power(xa, f, 44100)
        val rb = Audio.power(xb, 2 * f, 48000) / Audio.power(xb, f, 48000)
        assertEquals(ra, rb, ra * 0.1)
    }
}
