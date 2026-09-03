package com.donfluids.music.dsp

import com.donfluids.music.music.ChordLabel
import com.donfluids.music.music.ChordSegment
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

    @Test
    fun templateMatchingOnCleanChroma() {
        val d = ChordDetector()
        val cMajor = FloatArray(12).also { it[0] = 1f; it[4] = 0.8f; it[7] = 0.9f }
        assertEquals(maj(0), d.match(cMajor)!!.first)
        val aMinor = FloatArray(12).also { it[9] = 1f; it[0] = 0.8f; it[4] = 0.9f }
        assertEquals(min(9), d.match(aMinor)!!.first)
        // Flat chroma is not a chord.
        assertNull(d.match(FloatArray(12) { 1f }))
    }

    @Test
    fun numerals() {
        val g = Key(7, Mode.MAJOR)
        assertEquals("I", maj(7).numeralIn(g))
        assertEquals("IV", maj(0).numeralIn(g))
        assertEquals("V", maj(2).numeralIn(g))
        assertEquals("vi", min(4).numeralIn(g))
        assertEquals("bVII", maj(5).numeralIn(g))
        val em = Key(4, Mode.MINOR)
        assertEquals("i", min(4).numeralIn(em))
        assertEquals("VI", maj(0).numeralIn(em))
        assertEquals("Bb", maj(10).name(Key(5, Mode.MAJOR)))
        assertEquals("A#", maj(10).name(null))
    }

    @Test
    fun cadenceDecidesBetweenRelativeKeys() {
        // C F G C repeated: I IV V I in C major, with V-I cadences.
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
    fun harmonicMinorProgressionIsMinor() {
        // Am Dm E Am: i iv V i.
        val segments = ArrayList<ChordSegment>()
        var t = 0f
        repeat(3) {
            for (c in listOf(min(9), min(2), maj(4), min(9))) { segments += seg(c, t, t + 2f); t += 2f }
        }
        assertEquals(Key(9, Mode.MINOR), ChordKeyEstimator.suggest(segments)[0].key)
    }

    @Test
    fun worshipProgressionWithFlatSeven() {
        // G C D Em ... F (bVII) still G major.
        val segments = listOf(
            seg(maj(7), 0f, 4f), seg(maj(0), 4f, 8f), seg(maj(2), 8f, 12f), seg(min(4), 12f, 16f),
            seg(maj(7), 16f, 20f), seg(maj(5), 20f, 22f), seg(maj(0), 22f, 24f), seg(maj(7), 24f, 28f),
        )
        assertEquals(Key(7, Mode.MAJOR), ChordKeyEstimator.suggest(segments)[0].key)
    }

    @Test
    fun analyzerTracksChordSequenceAndSuggestsKey() {
        // D - G - A - Bm - D, two seconds each, as sung voices.
        val roots = listOf(listOf(50, 57, 62, 66), listOf(55, 59, 62, 67), listOf(57, 61, 64, 69), listOf(59, 62, 66, 71), listOf(50, 57, 62, 66))
        val audio = Synth.progression(roots, secondsEach = 2.0)
        val result = Synth.analyze(audio)
        val names = result.chordSegments.mapNotNull { it.chord?.toString() }
        // Collapse repeats caused by brief dropouts, then compare.
        val collapsed = names.fold(ArrayList<String>()) { acc, n -> if (acc.lastOrNull() != n) acc += n; acc }
        assertEquals(listOf("D", "G", "A", "Bm", "D"), collapsed)
        assertEquals("D", result.currentChord.toString())
        assertEquals(Key(2, Mode.MAJOR), result.songKey)
        assertTrue("confidence ${result.songKeyConfidence}", result.songKeyConfidence > 0.3f)
    }
}
