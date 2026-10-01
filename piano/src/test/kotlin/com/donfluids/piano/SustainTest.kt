package com.donfluids.piano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SustainTest {
    private val rate = 44100

    @Test
    fun stealingAVoiceFadesInsteadOfCutting() {
        val synth = PianoSynth(rate, maxVoices = 4)
        synth.sustain = true
        for (m in listOf(40, 44, 47, 52)) synth.noteOn(m)
        val before = Audio.render(synth, 0.3)
        synth.noteOn(59) // fifth note: must steal
        val after = Audio.render(synth, 0.05)
        val steady = Audio.maxStep(before, rate / 10, rate / 5)
        assertTrue("steal click at the join: ${abs(after[0] - before.last())} vs $steady", abs(after[0] - before.last()) <= steady * 1.5f + 1e-4f)
        assertTrue("steal click inside: ${Audio.maxStep(after, 1, after.size)} vs $steady", Audio.maxStep(after, 1, after.size) <= steady * 1.5f + 1e-4f)
        assertEquals(4, synth.activeVoices)
        // The new note is actually sounding after the fade.
        val later = Audio.render(synth, 0.3)
        val expected = 440.0 * Math.pow(2.0, (59 - 69) / 12.0)
        assertTrue(Audio.power(later, expected, rate) > Audio.power(later, expected * 1.06, rate) * 3)
    }

    @Test
    fun stealPrefersTheQuietestReleasingVoice() {
        val synth = PianoSynth(rate, maxVoices = 3)
        synth.noteOn(60); synth.noteOn(64); synth.noteOn(67)
        Audio.render(synth, 0.2)
        synth.noteOff(64) // this one is now releasing: it should be the victim
        Audio.render(synth, 0.05)
        synth.noteOn(72)
        Audio.render(synth, 0.3)
        val x = Audio.render(synth, 0.3)
        fun hz(m: Int) = 440.0 * Math.pow(2.0, (m - 69) / 12.0)
        assertTrue("C still sounds", Audio.power(x, hz(60), rate) > 0.01)
        assertTrue("G still sounds", Audio.power(x, hz(67), rate) > 0.01)
        assertTrue("new C sounds", Audio.power(x, hz(72), rate) > 0.01)
        assertTrue("E was taken", Audio.power(x, hz(64), rate) < Audio.power(x, hz(60), rate) * 0.2)
    }

    @Test
    fun sustainedPianoNoteIsCappedButSustainedHeldNoteIsNot() {
        val synth = PianoSynth(rate, sustainCapSeconds = 1.0)
        synth.sustain = true
        synth.noteOn(36) // a low note that would otherwise ring for most of a minute
        synth.noteOff(36)
        Audio.render(synth, 2.5)
        assertEquals("lifted note released after the cap", 0, synth.activeVoices)
        synth.noteOn(36)
        Audio.render(synth, 2.5)
        assertEquals("held note keeps ringing", 1, synth.activeVoices)
    }

    @Test
    fun organUnderSustainFadesSlowlyRatherThanHoldingForever() {
        val synth = PianoSynth(rate, organSustainFadeSeconds = 0.5)
        synth.tone = PianoSynth.Tone.ORGAN
        synth.sustain = true
        synth.noteOn(60)
        Audio.render(synth, 0.2)
        synth.noteOff(60)
        val a = Audio.rms(Audio.render(synth, 0.2))
        val b = Audio.rms(Audio.render(synth, 0.6))
        assertTrue("fading: $a then $b", b < a * 0.6)
        Audio.render(synth, 3.0)
        assertEquals(0, synth.activeVoices)
        // Without sustain the organ stops quickly, as before.
        synth.sustain = false
        synth.noteOn(60); Audio.render(synth, 0.2); synth.noteOff(60); Audio.render(synth, 0.5)
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun levelsFollowTheSound() {
        val synth = PianoSynth(rate)
        val levels = FloatArray(128)
        synth.copyLevels(levels)
        assertTrue(levels.all { it == 0f })
        synth.noteOn(60)
        Audio.render(synth, 0.05)
        synth.copyLevels(levels)
        assertTrue("fresh note near full: ${levels[60]}", levels[60] > 0.9f)
        Audio.render(synth, 2.0)
        synth.copyLevels(levels)
        assertTrue("decayed: ${levels[60]}", levels[60] in 0.01f..0.7f)
        synth.noteOff(60)
        Audio.render(synth, 1.0)
        synth.copyLevels(levels)
        assertEquals(0f, levels[60], 1e-6f)
    }
}
