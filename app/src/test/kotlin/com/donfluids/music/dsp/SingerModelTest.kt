package com.donfluids.music.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SingerModelTest {
    @Test
    fun softSharesSplitANoteBetweenTwoKeys() {
        assertEquals(listOf(0 to 1.0), softShares(60.1, 0.0))
        val split = softShares(60.45, 0.3).toMap()
        assertTrue("$split", split.getValue(0) > split.getValue(1) && split.getValue(1) > 0.3)
        val tight = softShares(60.1, 0.1).toMap()
        assertTrue("$tight", tight.getValue(0) > 0.99)
    }

    @Test
    fun vibratoAroundANoteBetweenKeysStaysOneNote() {
        // Sung 45 cents above C with 40 cents of vibrato: it crosses into C# and back all the time.
        val seg = NoteSegmenter()
        var t = 0.0
        while (t < 1.2) { seg.push(60.45 + 0.4 * sin(2 * PI * 5.5 * t), t); t += 0.046 }
        seg.push(Double.NaN, t + 0.2)
        assertEquals(seg.notes.toString(), 1, seg.notes.size)
        assertEquals(60.45, seg.notes[0].pitch, 0.1)
    }

    @Test
    fun aSlowSlideLeavesNoPassingNote() {
        // C held, a 300 ms slide to D, D held: two notes, no C# in between.
        val seg = NoteSegmenter()
        var t = 0.0
        while (t < 0.6) { seg.push(60.0, t); t += 0.046 }
        val slideStart = t
        while (t < slideStart + 0.3) { seg.push(60.0 + 2.0 * (t - slideStart) / 0.3, t); t += 0.046 }
        while (t < slideStart + 0.9) { seg.push(62.0, t); t += 0.046 }
        seg.push(Double.NaN, t + 0.2)
        assertEquals(seg.notes.toString(), listOf(60, 62), seg.notes.map { it.midi })
    }

    @Test
    fun theOffsetFollowsADriftButNotAScatter() {
        // Drifting 60 cents flat over 30 notes, each held 0.6 s: the model should follow.
        val drift = SingerModel()
        var t = 0.0
        for (i in 0 until 30) {
            val truth = -60.0 * i / 29
            // The segmenter reports each note relative to the current offset.
            val seen = 60.0 + (truth - drift.offsetCents) / 100.0
            drift.observe(SungNote(seen, t, 0.6)); t += 0.7
        }
        assertTrue("offset ${drift.offsetCents}", drift.offsetCents < -35.0)
        // Notes scattered at random say nothing about direction: the grid stays put.
        val rnd = java.util.Random(3)
        val scatter = SingerModel()
        for (i in 0 until 40) scatter.observe(SungNote(60.0 + (rnd.nextDouble() - 0.5), i * 0.7, 0.6))
        // Well short of the 50 cents at which notes would be relabelled.
        assertTrue("offset ${scatter.offsetCents}", abs(scatter.offsetCents) < 25.0)
        assertTrue("spread ${scatter.spreadCents}", scatter.spreadCents > 25.0)
    }

    @Test
    fun aSingerConsistentlyFortyFiveCentsSharpIsMet() {
        // Halfway between keys, but always the same way: that is an offset, not carelessness.
        val m = SingerModel()
        var t = 0.0
        for (i in 0 until 20) {
            val seen = 60.0 + (45.0 - m.offsetCents) / 100.0
            m.observe(SungNote(seen, t, 0.6)); t += 0.7
        }
        assertEquals(45.0, m.offsetCents, 8.0)
    }
}
