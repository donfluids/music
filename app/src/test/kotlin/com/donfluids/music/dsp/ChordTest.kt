package com.donfluids.music.dsp

import com.donfluids.music.music.ChordLabel
import com.donfluids.music.music.ChordSegment
import com.donfluids.music.music.ChordType
import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordTest {

    private fun seg(chord: ChordLabel?, start: Float, end: Float) = ChordSegment(chord, start, end)
    private fun maj(root: Int) = ChordLabel(root, false)
    private fun min(root: Int) = ChordLabel(root, true)
    private fun chroma(vararg pcWeights: Pair<Int, Float>) = FloatArray(12).also { c -> pcWeights.forEach { (pc, w) -> c[pc] = w } }

    @Test
    fun templateMatchingOnCleanChroma() {
        val t = ChordTemplates()
        assertEquals(maj(0), t.match(chroma(0 to 1f, 4 to 0.8f, 7 to 0.9f))!!.first)
        assertEquals(min(9), t.match(chroma(9 to 1f, 0 to 0.8f, 4 to 0.9f))!!.first)
        assertEquals(ChordLabel(7, ChordType.DOMINANT_SEVENTH), t.match(chroma(7 to 1f, 11 to 0.8f, 2 to 0.9f, 5 to 0.7f))!!.first)
        assertEquals(ChordLabel(0, ChordType.MAJOR_SEVENTH), t.match(chroma(0 to 1f, 4 to 0.8f, 7 to 0.9f, 11 to 0.7f))!!.first)
        assertEquals(ChordLabel(2, ChordType.MINOR_SEVENTH), t.match(chroma(2 to 1f, 5 to 0.8f, 9 to 0.9f, 0 to 0.7f))!!.first)
        assertEquals(ChordLabel(2, ChordType.SUS4), t.match(chroma(2 to 1f, 7 to 0.8f, 9 to 0.9f))!!.first)
        // A triad with a little overtone energy on the seventh stays a triad.
        assertEquals(maj(0), t.match(chroma(0 to 1f, 4 to 0.8f, 7 to 0.9f, 10 to 0.2f))!!.first)
        assertNull(t.match(FloatArray(12) { 1f }))
    }

    @Test
    fun numeralsAndNames() {
        val g = Key(7, Mode.MAJOR)
        assertEquals("I", maj(7).numeralIn(g))
        assertEquals("IV", maj(0).numeralIn(g))
        assertEquals("V7", ChordLabel(2, ChordType.DOMINANT_SEVENTH).numeralIn(g))
        assertEquals("vi7", ChordLabel(4, ChordType.MINOR_SEVENTH).numeralIn(g))
        assertEquals("bVII", maj(5).numeralIn(g))
        assertEquals("Vsus4", ChordLabel(2, ChordType.SUS4).numeralIn(g))
        assertEquals("i", min(4).numeralIn(Key(4, Mode.MINOR)))
        assertEquals("Bb", maj(10).name(Key(5, Mode.MAJOR)))
        assertEquals("A#m7", ChordLabel(10, ChordType.MINOR_SEVENTH).name(null))
        assertEquals(maj(0), ChordLabel(0, ChordType.MAJOR_SEVENTH).triad)
    }

    @Test
    fun trackerRemovesOneOffMisreads() {
        val tracker = ChordTracker(lag = 4)
        val c = chroma(0 to 1f, 4 to 0.8f, 7 to 0.9f)
        val g = chroma(7 to 1f, 11 to 0.8f, 2 to 0.9f)
        val glitch = chroma(1 to 1f, 5 to 0.8f, 8 to 0.9f) // Db, one observation only
        val sequence = List(10) { c } + listOf(glitch) + List(10) { c } + List(10) { g }
        val decided = ArrayList<ChordLabel?>()
        for (obs in sequence) if (tracker.observe(obs, 0.2)) decided += tracker.decided
        assertEquals(sequence.size - tracker.lag + 1, decided.size)
        assertTrue("glitch survived: $decided", decided.none { it == maj(1) })
        assertEquals(maj(0), decided.first())
        assertEquals(maj(7), decided.last())
        // Exactly one change, from C to G.
        val changes = decided.zipWithNext().count { (a, b) -> a != b }
        assertEquals(1, changes)
    }

    @Test
    fun trackerReportsSilenceAsNoChord() {
        val tracker = ChordTracker(lag = 2)
        repeat(6) { tracker.observe(FloatArray(12), 0.2) }
        assertTrue(tracker.hasDecision)
        assertNull(tracker.decided)
    }

    @Test
    fun cadenceDecidesBetweenRelativeKeys() {
        val segments = ArrayList<ChordSegment>()
        var t = 0f
        repeat(4) {
            for (root in intArrayOf(0, 5, 7, 0)) { segments += seg(maj(root), t, t + 2f); t += 2f }
        }
        val ranked = ChordKeyEstimator.suggest(segments)
        assertEquals(Key(0, Mode.MAJOR), ranked[0].key)
        assertTrue(ranked[0].cadences >= 4)
        assertTrue(ChordKeyEstimator.confidence(ranked) > 0.4)
    }

    @Test
    fun dominantSeventhCountsAsCadence() {
        val segments = listOf(
            seg(maj(0), 0f, 2f), seg(maj(5), 2f, 4f), seg(ChordLabel(7, ChordType.DOMINANT_SEVENTH), 4f, 6f), seg(maj(0), 6f, 8f),
            seg(maj(0), 8f, 10f), seg(maj(5), 10f, 12f), seg(ChordLabel(7, ChordType.DOMINANT_SEVENTH), 12f, 14f), seg(maj(0), 14f, 16f),
        )
        val ranked = ChordKeyEstimator.suggest(segments)
        assertEquals(Key(0, Mode.MAJOR), ranked[0].key)
        assertEquals(2, ranked[0].cadences)
    }

    @Test
    fun harmonicMinorProgressionIsMinor() {
        val segments = ArrayList<ChordSegment>()
        var t = 0f
        repeat(3) {
            for (c in listOf(min(9), min(2), maj(4), min(9))) { segments += seg(c, t, t + 2f); t += 2f }
        }
        assertEquals(Key(9, Mode.MINOR), ChordKeyEstimator.suggest(segments)[0].key)
    }

    @Test
    fun worshipProgressionWithFlatSeven() {
        val segments = listOf(
            seg(maj(7), 0f, 4f), seg(maj(0), 4f, 8f), seg(maj(2), 8f, 12f), seg(min(4), 12f, 16f),
            seg(maj(7), 16f, 20f), seg(maj(5), 20f, 22f), seg(maj(0), 22f, 24f), seg(maj(7), 24f, 28f),
        )
        assertEquals(Key(7, Mode.MAJOR), ChordKeyEstimator.suggest(segments)[0].key)
    }

    private fun collapsedTriads(result: AnalysisResult): List<String> =
        result.chordSegments.mapNotNull { it.chord?.triad?.toString() }
            .fold(ArrayList<String>()) { acc, n -> if (acc.lastOrNull() != n) acc += n; acc }

    @Test
    fun analyzerTracksChordSequenceAndSuggestsKey() {
        // D - G - A - Bm - D, two seconds each, as sung voices.
        val roots = listOf(listOf(50, 57, 62, 66), listOf(55, 59, 62, 67), listOf(57, 61, 64, 69), listOf(59, 62, 66, 71), listOf(50, 57, 62, 66))
        val result = Synth.analyze(Synth.progression(roots, secondsEach = 2.0))
        assertEquals(listOf("D", "G", "A", "Bm", "D"), collapsedTriads(result))
        assertEquals("D", result.currentChord?.triad.toString())
        assertEquals(Key(2, Mode.MAJOR), result.songKey)
        assertTrue("confidence ${result.songKeyConfidence}", result.songKeyConfidence > 0.3f)
    }

    @Test
    fun analyzerHearsSeventhChords() {
        // G - C - D7 - G with the seventh voiced clearly.
        val chords = listOf(listOf(43, 55, 59, 62, 67), listOf(48, 55, 60, 64, 67), listOf(50, 57, 60, 62, 66), listOf(43, 55, 59, 62, 67))
        val result = Synth.analyze(Synth.progression(chords, secondsEach = 2.0))
        assertEquals(listOf("G", "C", "D", "G"), collapsedTriads(result))
        val d = result.chordSegments.filter { it.chord?.root == 2 }.maxByOrNull { it.duration }!!.chord!!
        assertEquals(ChordType.DOMINANT_SEVENTH, d.type)
        assertEquals(Key(7, Mode.MAJOR), result.songKey)
    }

    @Test
    fun chordBoundariesFollowTheBeatWhenTempoIsKnown() {
        // Chords change every two beats at 120 BPM (one second) over a click track.
        val bpm = 120.0
        val chords = listOf(listOf(48, 55, 60, 64), listOf(53, 57, 60, 65), listOf(55, 59, 62, 67), listOf(48, 55, 60, 64))
        val progression = Synth.progression(chords + chords + chords + chords, secondsEach = 1.0)
        val clicks = Synth.clickTrack(bpm, 16.0, accents = listOf(1f, 0.4f, 0.7f, 0.4f))
        val result = Synth.analyze(Synth.mix(progression, clicks))
        assertTrue("tempo locked", result.tempoLocked)
        // Every boundary in the second half of the song should land within 150 ms of a whole second.
        val late = result.chordSegments.filter { it.startSeconds > 8f }
        assertTrue("need segments", late.size >= 4)
        for (seg in late) {
            val offset = seg.startSeconds - Math.round(seg.startSeconds)
            assertTrue("boundary at ${seg.startSeconds} for ${seg.chord}", kotlin.math.abs(offset) < 0.15f)
        }
        assertEquals(listOf("C", "F", "G", "C"), collapsedTriads(result).takeLast(4))
    }
}
