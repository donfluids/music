package com.donfluids.piano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorded drum grooves: every hit plays, in its own bar of the loop, at the drummer's timing. */
class GrooveTest {
    private val rate = 44100

    /** Plays [style] for [bars] bars with no chord changes; returns each drum trigger's time in beats. */
    private fun play(style: Style, bpm: Int, bars: Int): List<Pair<Double, Drum>> {
        val band = Accompaniment(rate)
        band.style = style; band.bpm = bpm; band.fillsOnChordChange = false
        band.setChord(BandChord(0, BandChord.Quality.MAJOR))
        band.playing = true
        val log = ArrayList<Pair<Double, Drum>>()
        var played = 0L
        band.drums.onTrigger = { d, _ -> log += (played.toDouble() / rate * bpm / 60.0) to d }
        val beats = bars * style.beatsPerBar
        val total = (beats * 60.0 / bpm * rate).toLong()
        val b = FloatArray(64)
        while (played < total) { java.util.Arrays.fill(b, 0f); band.mix(b, 64, 0L); played += 64 }
        return log
    }

    @Test
    fun theFiveRecordedStylesUseTheirGrooves() {
        val withGrooves = Style.ALL.filter { it.groove != null }.map { it.id }
        assertEquals(listOf("worship_slow", "rock_8", "power_rock", "pop_16", "gospel_shuffle"), withGrooves)
        // No recorded 6/8 or waltz groove suited a gentle accompaniment: those keep their written patterns.
        assertEquals(null, Style.WORSHIP_68.groove)
        assertEquals(null, Style.WALTZ.groove)
    }

    @Test
    fun everyHitOfTheLoopPlaysOncePerCycleAndTheLoopRepeats() {
        for (s in Style.ALL.filter { it.groove != null }) {
            val g = s.groove!!
            val log = play(s, s.defaultBpm, 2 * g.bars)
            val cycleBeats = g.bars * g.beatsPerBar
            val first = log.count { it.first < cycleBeats - 0.01 }
            val second = log.count { it.first >= cycleBeats - 0.01 && it.first < 2 * cycleBeats - 0.01 }
            assertEquals(s.id, g.hits.size, first)
            assertEquals(s.id, g.hits.size, second)
        }
    }

    @Test
    fun hitsKeepTheDrummersTimingOffTheGrid() {
        val s = Style.POP_16
        val log = play(s, 100, 4)
        val offGrid = log.count { (t, _) -> val sixteenth = t * 4; kotlin.math.abs(sixteenth - Math.round(sixteenth)) > 0.06 }
        // A real drummer pushes and lays back: a good share of hits sit audibly off the 16th grid.
        assertTrue("off grid: $offGrid of ${log.size}", offGrid >= log.size / 5)
        // And each hit lands where the recording put it, to within a few milliseconds at any tempo.
        val g = s.groove!!
        for (bpm in listOf(80, 120)) {
            val at = play(s, bpm, 4).map { it.first }.sorted()
            val want = g.hits.map { it.beat.toDouble() }.sorted()
            val worstMs = at.zip(want).maxOf { (a, w) -> kotlin.math.abs(a - w) } * 60000.0 / bpm
            assertTrue("bpm $bpm: worst $worstMs ms", worstMs < 3.0)
        }
    }

    @Test
    fun theBeatDisplayStillCountsEveryBeat() {
        val band = Accompaniment(rate)
        band.style = Style.ROCK_8; band.bpm = 120
        band.setChord(BandChord(0, BandChord.Quality.MAJOR)); band.playing = true
        val seen = ArrayList<Int>()
        val b = FloatArray(64)
        repeat((rate * 4.0 / 64).toInt()) { band.mix(b, 64, 0L); if (seen.lastOrNull() != band.beatIndex) seen += band.beatIndex }
        // Two bars at 120 BPM: 1 2 3 4 1 2 3 4.
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3), seen.take(8))
    }
}
