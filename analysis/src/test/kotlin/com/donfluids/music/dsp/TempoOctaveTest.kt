package com.donfluids.music.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Octave and bar-length choices the tracker must get right for church repertoire. */
class TempoOctaveTest {

    private fun assertBpm(expected: Double, result: AnalysisResult, tolerance: Double = 0.035) {
        assertNotNull("no tempo", result.bpm)
        assertEquals(expected, result.bpm!!.toDouble(), expected * tolerance)
    }

    @Test
    fun accentedFourFourFromOneTwentyToOneForty() {
        // Strong-weak-medium-weak at the beat: the beat, not half time, across the working range.
        for (bpm in listOf(120.0, 130.0, 140.0)) {
            val result = Synth.analyze(Synth.clickTrack(bpm, 20.0, accents = listOf(1f, 0.4f, 0.7f, 0.4f)))
            assertBpm(bpm, result)
            assertEquals("meter at $bpm", "4/4", result.meterLabel)
        }
    }

    @Test
    fun strummedEighthsInFourFourAtOneTwenty() {
        val audio = Synth.clickTrack(240.0, 20.0, accents = listOf(1f, 0.3f, 0.5f, 0.3f, 0.7f, 0.3f, 0.5f, 0.3f))
        val result = Synth.analyze(audio)
        assertBpm(120.0, result)
        assertEquals("4/4", result.meterLabel)
    }

    @Test
    fun slowBalladStrummedInEighthsIsReadAtOneOfItsTwoPulses() {
        // 68 BPM with every eighth strummed is the same onset pattern as 136 BPM in 4/4; nothing
        // in the onsets can tell them apart, so either reading is accepted and the ÷2 control
        // covers the other. What must hold: it is one of the two, and the bar is four beats.
        val audio = Synth.clickTrack(136.0, 24.0, accents = listOf(1f, 0.3f, 0.5f, 0.3f, 0.7f, 0.3f, 0.5f, 0.3f))
        val result = Synth.analyze(audio)
        val bpm = result.bpm!!.toDouble()
        assertTrue("bpm $bpm", abs(bpm - 68.0) < 68.0 * 0.035 || abs(bpm - 136.0) < 136.0 * 0.035)
        assertEquals("4/4", result.meterLabel)
    }

    @Test
    fun fastWaltzStaysInThree() {
        // A waltz at 150 is rhythmically the same as 3/2 at 75; either pulse is accepted, but the
        // bar must be three and must not be mistaken for 6/8.
        for (bpm in listOf(140.0, 150.0)) {
            val result = Synth.analyze(Synth.clickTrack(bpm, 20.0, accents = listOf(1f, 0.4f, 0.4f)))
            val got = result.bpm!!.toDouble()
            assertTrue("bpm $got at $bpm", abs(got - bpm) < bpm * 0.035 || abs(got - bpm / 2) < bpm * 0.02)
            assertEquals("meter at $bpm", "3/4", result.meterLabel)
        }
    }

    @Test
    fun compoundAtFiftyFiveDottedQuarters() {
        // 6/8 hymn: dotted-quarter pulse at 55, eighths at 165, strong-weak bars.
        val audio = Synth.clickTrack(55.0, 24.0, accents = listOf(1f, 0.5f), subdivisions = 3, subdivisionAmplitude = 0.35f)
        val result = Synth.analyze(audio)
        // Either reading is right: the dotted-quarter pulse felt in two, or the eighths in six.
        val bpm = result.bpm!!.toDouble()
        val dotted = abs(bpm - 55.0) < 55.0 * 0.035
        val eighths = abs(bpm - 165.0) < 165.0 * 0.035
        assertTrue("bpm $bpm", dotted || eighths)
        assertEquals("6/8", result.meterLabel)
        if (dotted) assertTrue("compound", result.meterCompound) else assertEquals(6, result.beatsPerBar)
    }

    @Test
    fun swungEighthsAreNotCompound() {
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

    /** Feeds the tracker one impulse per beat and returns (seconds, bpm) for every estimate it makes. */
    private fun trackerEstimates(fps: Double, bpmSequence: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val tracker = TempoTracker(fps)
        val out = ArrayList<Pair<Double, Double>>()
        var frame = 0
        var nextBeat = 0.0
        for ((bpm, seconds) in bpmSequence) {
            val period = fps * 60.0 / bpm
            val frames = (seconds * fps).toInt()
            for (i in 0 until frames) {
                val onset = if (frame >= nextBeat) { nextBeat += period; 1f } else 0.05f
                tracker.push(onset)
                frame++
                if (frame % (fps / 2).toInt() == 0) tracker.estimate()?.let { out += (frame / fps) to it.bpm }
            }
        }
        return out
    }

    @Test
    fun lockedTempoResistsAnOctaveJumpThenFollowsIt() {
        val fps = 86.13
        // 70 then 140: the doubled tempo is well inside the prior, so without hysteresis the
        // tracker would jump on the first estimate that sees mostly 140-BPM onsets.
        val estimates = trackerEstimates(fps, listOf(70.0 to 12.0, 140.0 to 12.0))
        val before = estimates.filter { it.first > 10.0 && it.first <= 12.0 }.map { it.second }
        assertTrue("locked at 70 before the change: $before", before.size >= 3 && before.all { abs(it - 70.0) < 3.0 })
        // Hysteresis: for the first two seconds after the change the tracker still says 70.
        val justAfter = estimates.filter { it.first > 12.5 && it.first <= 14.0 }.map { it.second }
        assertTrue("held 70 for a while after the change: $justAfter", justAfter.size >= 2 && justAfter.all { abs(it - 70.0) < 3.0 })
        // But it does follow a real change within a few seconds.
        val later = estimates.filter { it.first > 22.0 }.map { it.second }
        assertTrue("followed to 140 eventually: $later", later.size >= 2 && later.all { abs(it - 140.0) < 6.0 })
    }
}
