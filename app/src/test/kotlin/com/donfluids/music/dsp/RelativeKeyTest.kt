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

class RelativeKeyTest {
    private val cMajor = Key(0, Mode.MAJOR)
    private val aMinor = Key(9, Mode.MINOR)
    private val t = RelativeKeyResolver.THRESHOLD

    /** Notes with a breath after every note of 0.8 s or longer. */
    private fun line(vararg notes: Pair<Int, Double>): List<SungNote> {
        var at = 0.0
        return notes.map { (m, d) -> SungNote(m, at, d).also { at += d + if (d >= 0.8) 0.5 else 0.0 } }
    }
    private fun score(notes: List<SungNote>, key: Key = cMajor) = RelativeKeyResolver.evidence(key, notes, null, emptyList())

    private val cTune = line(60 to .4, 62 to .4, 64 to .4, 65 to .4, 67 to .8, 69 to .4, 67 to .4, 65 to .4, 64 to .4, 62 to .4, 60 to 1.2)
    private val aNatural = line(69 to .4, 71 to .4, 72 to .4, 74 to .4, 76 to .8, 74 to .4, 72 to .4, 71 to .4, 72 to .4, 71 to .4, 69 to 1.2)
    private val aHarmonic = line(69 to .4, 71 to .4, 72 to .4, 74 to .4, 76 to .8, 74 to .4, 72 to .4, 71 to .4, 68 to .4, 71 to .4, 69 to 1.2)
    private val aDwellingOnC = line(72 to .8, 71 to .4, 69 to .4, 72 to .8, 74 to .4, 72 to .4, 71 to .4, 72 to .8, 71 to .4, 69 to .6)

    @Test
    fun aTuneThatRestsOnCIsCMajor() {
        val e = score(cTune)
        assertTrue("$e", e.total > t)
        assertTrue("endings $e", e.endings > 0)
    }

    @Test
    fun theSameNotesRestingOnAAreAMinor() {
        val e = score(aNatural)
        assertTrue("$e", e.total < -t)
        // The answer does not depend on which of the pair the finder offered.
        assertEquals(e.total, score(aNatural, aMinor).total, 1e-9)
    }

    @Test
    fun aGSharpMakesTheMinorClearer() {
        val natural = score(aNatural)
        val harmonic = score(aHarmonic)
        assertTrue("leading tone ${harmonic.leadingTone}", harmonic.leadingTone < 0)
        assertEquals(0.0, natural.leadingTone, 1e-9)
        assertTrue("$harmonic vs $natural", harmonic.total < natural.total)
    }

    @Test
    fun aTuneThatDwellsOnCButEndsOnAIsNotCalledMajor() {
        // Three phrases rest on C, the last on A: genuinely between the two. Undecided is honest.
        val e = score(aDwellingOnC)
        assertTrue("$e", e.total <= t)
    }

    @Test
    fun chordsCountTonicTimeTheMajorDominantAndCadences() {
        fun seg(root: Int, type: ChordType, from: Float, to: Float) = ChordSegment(ChordLabel(root, type), from, to)
        val inC = listOf(seg(0, ChordType.MAJOR, 0f, 2f), seg(5, ChordType.MAJOR, 2f, 3f), seg(7, ChordType.MAJOR, 3f, 4f), seg(0, ChordType.MAJOR, 4f, 6f))
        val inAm = listOf(seg(9, ChordType.MINOR, 0f, 2f), seg(2, ChordType.MINOR, 2f, 3f), seg(4, ChordType.MAJOR, 3f, 4f), seg(9, ChordType.MINOR, 4f, 6f))
        val c = RelativeKeyResolver.evidence(cMajor, emptyList(), null, inC)
        val a = RelativeKeyResolver.evidence(cMajor, emptyList(), null, inAm)
        assertTrue("C chords ${c.chords}", c.chords > 1.0)
        assertTrue("Am chords ${a.chords}", a.chords < -1.0)
    }

    @Test
    fun withoutNotesTheChromaStillSeesTheLeadingTone() {
        // A minor with G# where G would be: energy on A, C, E, and G# rather than G.
        val chroma = FloatArray(12).also { it[9] = 1f; it[0] = .6f; it[4] = .8f; it[11] = .4f; it[2] = .4f; it[5] = .3f; it[8] = .4f; it[7] = .05f }
        val e = RelativeKeyResolver.evidence(cMajor, emptyList(), chroma, emptyList())
        assertTrue("leading ${e.leadingTone}", e.leadingTone < -1.0)
        assertTrue("$e", e.total < -t)
    }

    @Test
    fun theDecisionHoldsUntilTheEvidenceTurns() {
        val tr = RelativeModeTracker()
        assertEquals(cMajor, tr.update(cMajor, 0.4)); assertNull(tr.decided)       // leaning, undecided
        assertEquals(cMajor, tr.update(cMajor, -0.3)); assertNull(tr.decided)      // a wobble does not swap the lean
        assertEquals(aMinor, tr.update(cMajor, -0.7)); assertNull(tr.decided)      // a clear turn does
        assertEquals(cMajor, tr.update(aMinor, 2.0)); assertEquals(cMajor, tr.decided)
        assertEquals(cMajor, tr.update(cMajor, 0.8)); assertEquals(cMajor, tr.decided) // weaker, still held
        tr.update(cMajor, 0.2); assertNull(tr.decided)                              // faded: undecided
        assertEquals(aMinor, tr.update(cMajor, -2.0)); assertEquals(aMinor, tr.decided)
        // A different pair starts afresh.
        assertEquals(Key(7, Mode.MAJOR), tr.update(Key(7, Mode.MAJOR), 0.5)); assertNull(tr.decided)
    }
}
