package com.donfluids.music.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AnalyzerStateTest {

    /** Every published beat time must sit on the click grid, at every publish, not just the last. */
    @Test
    fun publishedBeatStaysOnTheGrid() {
        val bpm = 100.0
        val periodNanos = 60.0 / bpm * 1e9
        val audio = Synth.clickTrack(bpm, 20.0, accents = listOf(1f, 0.3f, 0.6f, 0.3f))
        val offsets = ArrayList<Double>()
        var worstDrift = 0.0
        var previousBeat = 0L
        Synth.analyze(audio) { r ->
            if (!r.tempoLocked || r.lastBeatAtNanos == 0L || r.secondsHeard < 10f) return@analyze
            val beats = r.lastBeatAtNanos / periodNanos
            offsets += abs(beats - Math.round(beats))
            // A fresh tempo estimate may re-place the beat by a hop or so; anything larger is drift.
            val backwards = (previousBeat - r.lastBeatAtNanos) / periodNanos
            assertTrue("beat moved backwards by $backwards beats", backwards < 0.2)
            previousBeat = maxOf(previousBeat, r.lastBeatAtNanos)
            val nowNanos = (r.secondsHeard * 1e9).toLong()
            val age = (nowNanos - r.lastBeatAtNanos) / periodNanos
            worstDrift = maxOf(worstDrift, age)
        }
        assertTrue("too few samples", offsets.size > 100)
        assertTrue("max offset ${offsets.max()}", offsets.max() < 0.15)
        assertTrue("published beat lags now by $worstDrift beats", worstDrift < 1.1)
    }

    /** After a reset the next publish must not carry the old song's key, tempo, meter or chords. */
    @Test
    fun resetClearsEverythingPublished() {
        val chords = Synth.progression(listOf(listOf(55, 59, 62, 67), listOf(48, 55, 60, 64), listOf(50, 57, 62, 66), listOf(55, 59, 62, 67)), secondsEach = 3.0)
        val clicks = Synth.clickTrack(96.0, 12.0, accents = listOf(1f, 0.4f, 0.7f, 0.4f))
        val analyzer = MusicAnalyzer(Synth.SAMPLE_RATE)
        val before = Synth.analyze(Synth.mix(chords, clicks), analyzer)
        assertEquals("G major", before.key?.label)
        assertTrue(before.bpm != null && before.meterLabel != null && before.chordSegments.isNotEmpty())

        analyzer.reset()
        val after = Synth.analyze(FloatArray(Synth.SAMPLE_RATE / 5), analyzer) // 0.2 s of silence
        assertNull(after.key)
        assertNull(after.bpm)
        assertNull(after.meterLabel)
        assertNull(after.currentChord)
        assertNull(after.songKey)
        assertTrue(after.chordSegments.isEmpty())
        assertEquals(0L, after.lastBeatAtNanos)
        assertTrue(after.secondsHeard < 0.3f)
    }
}
