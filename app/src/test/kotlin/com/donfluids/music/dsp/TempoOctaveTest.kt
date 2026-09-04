package com.donfluids.music.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Octave choices the tempo tracker must get right for church repertoire. */
class TempoOctaveTest {

    private fun assertBpm(expected: Double, result: AnalysisResult, tolerance: Double = 0.035) {
        assertNotNull("no tempo", result.bpm)
        assertEquals(expected, result.bpm!!.toDouble(), expected * tolerance)
    }

    @Test
    fun slowBalladStrummedInEighthsReadsTheQuarterNote() {
        // 68 BPM, every eighth strummed: beats louder than off-beats, 1 and 3 loudest.
        val audio = Synth.clickTrack(136.0, 24.0, accents = listOf(1f, 0.3f, 0.5f, 0.3f, 0.7f, 0.3f, 0.5f, 0.3f))
        val result = Synth.analyze(audio)
        assertBpm(68.0, result)
        assertEquals("4/4", result.meterLabel)
    }

    @Test
    fun compoundAtFiftyFiveDottedQuarters() {
        // 6/8 hymn: dotted-quarter pulse at 55, eighths at 165, strong-weak bars.
        val audio = Synth.clickTrack(55.0, 24.0, accents = listOf(1f, 0.5f), subdivisions = 3, subdivisionAmplitude = 0.35f)
        val result = Synth.analyze(audio)
        // Either reading is right: the dotted-quarter pulse felt in two, or the eighths in six.
        val bpm = result.bpm!!.toDouble()
        val dotted = kotlin.math.abs(bpm - 55.0) < 55.0 * 0.035
        val eighths = kotlin.math.abs(bpm - 165.0) < 165.0 * 0.035
        assertTrue("bpm $bpm", dotted || eighths)
        assertEquals("6/8", result.meterLabel)
        if (dotted) assertTrue("compound", result.meterCompound) else assertEquals(6, result.beatsPerBar)
    }

    @Test
    fun swungEighthsAreNotCompound() {
        // 100 BPM with off-beats at two thirds of the beat (swing): 4/4, not 12/8.
        val bpm = 100.0
        val n = (16.0 * Synth.SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val period = 60.0 / bpm * Synth.SAMPLE_RATE
        val accents = floatArrayOf(1f, 0.4f, 0.7f, 0.4f)
        var beat = 0
        var t = 0.0
        while (t < n) {
            Synth.click(out, t.toInt(), accents[beat % 4])
            Synth.click(out, (t + period * 2.0 / 3.0).toInt(), 0.35f)
            beat++
            t += period
        }
        val result = Synth.analyze(out)
        assertBpm(100.0, result)
        assertFalse("compound", result.meterCompound)
        assertEquals("4/4", result.meterLabel)
    }

    @Test
    fun lockedTempoDoesNotFlipOctaveOnOneBadEstimate() {
        // Steady 4/4 at 110, then four seconds of eighth-note fills, then steady again.
        val steady = Synth.clickTrack(110.0, 12.0, accents = listOf(1f, 0.4f, 0.7f, 0.4f))
        val busy = Synth.clickTrack(220.0, 3.0, accents = listOf(0.8f, 0.7f))
        val audio = steady + busy + steady
        val bpms = ArrayList<Float>()
        Synth.analyze(audio) { r -> if (r.secondsHeard > 8f) r.bpm?.let { bpms += it } }
        val flips = bpms.zipWithNext().count { (a, b) -> kotlin.math.abs(b / a - 1f) > 0.3f }
        assertTrue("tempo flipped $flips times: ${bpms.distinct().take(12)}", flips <= 2)
    }
}
