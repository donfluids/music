package com.donfluids.music.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoAndMeterTest {

    private fun assertBpm(expected: Double, result: AnalysisResult) {
        val bpm = result.bpm
        assertNotNull("no tempo found", bpm)
        assertEquals("bpm", expected, bpm!!.toDouble(), expected * 0.03)
    }

    @Test
    fun plainClicksAt120() {
        val result = Synth.analyze(Synth.clickTrack(120.0, 12.0))
        assertBpm(120.0, result)
        assertTrue(result.tempoLocked)
    }

    @Test
    fun fourFourAt110() {
        val audio = Synth.clickTrack(110.0, 16.0, accents = listOf(1f, 0.4f, 0.7f, 0.4f))
        val result = Synth.analyze(audio)
        assertBpm(110.0, result)
        assertEquals("4/4", result.meterLabel)
        assertEquals(4, result.beatsPerBar)
    }

    @Test
    fun threeFourAt130() {
        val audio = Synth.clickTrack(130.0, 16.0, accents = listOf(1f, 0.4f, 0.4f))
        val result = Synth.analyze(audio)
        assertBpm(130.0, result)
        assertEquals("3/4", result.meterLabel)
    }

    @Test
    fun sixEightFeltInTwo() {
        // Dotted-quarter pulse at 80 with triplet eighths underneath, strong-weak bars.
        val audio = Synth.clickTrack(80.0, 18.0, accents = listOf(1f, 0.5f), subdivisions = 3, subdivisionAmplitude = 0.35f)
        val result = Synth.analyze(audio)
        assertBpm(80.0, result)
        assertTrue("compound", result.meterCompound)
        assertEquals("6/8", result.meterLabel)
    }

    @Test
    fun sevenEightAtTheEighth() {
        // 2+2+3 at 200 eighths per minute.
        val audio = Synth.clickTrack(200.0, 20.0, accents = listOf(1f, 0.3f, 0.7f, 0.3f, 0.7f, 0.3f, 0.3f))
        val result = Synth.analyze(audio)
        assertBpm(200.0, result)
        assertEquals("7/8", result.meterLabel)
    }

    @Test
    fun downbeatIsOnTheAccent() {
        val bpm = 100.0
        val audio = Synth.clickTrack(bpm, 16.0, accents = listOf(1f, 0.3f, 0.6f, 0.3f))
        val analyzer = MusicAnalyzer(Synth.SAMPLE_RATE)
        val result = Synth.analyze(audio, analyzer)
        assertEquals(4, result.beatsPerBar)
        // The beat that lastBeatAtNanos points at must be a multiple of the period from time 0
        // (allowing one hop of slack) and its bar index must match its position from the start.
        val periodNanos = 60.0 / bpm * 1e9
        val beatsFromStart = result.lastBeatAtNanos / periodNanos
        val nearest = Math.round(beatsFromStart)
        assertEquals("beat alignment", nearest.toDouble(), beatsFromStart, 0.15)
        assertEquals(Math.floorMod(nearest, 4L).toInt(), result.lastBeatIndexInBar)
    }

    @Test
    fun keyAndTempoTogether() {
        val chords = Synth.progression(
            listOf(listOf(55, 59, 62, 67), listOf(48, 55, 60, 64), listOf(50, 57, 62, 66), listOf(55, 59, 62, 67)),
            secondsEach = 4.0,
        )
        val clicks = Synth.clickTrack(96.0, 16.0, accents = listOf(1f, 0.4f, 0.7f, 0.4f), subdivisions = 2, subdivisionAmplitude = 0.15f)
        val result = Synth.analyze(Synth.mix(chords, clicks))
        assertEquals("G major", result.key?.label)
        assertBpm(96.0, result)
        assertEquals("4/4", result.meterLabel)
    }

    @Test
    fun silenceReportsNothing() {
        val result = Synth.analyze(FloatArray(10 * Synth.SAMPLE_RATE))
        assertEquals(null, result.bpm)
        assertEquals(null, result.key)
        assertEquals(null, result.meterLabel)
    }
}
