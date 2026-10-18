package com.donfluids.music.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLevelTrackerTest {
    // A C major tune, 32 notes.
    private val tune = listOf(55, 60, 64, 60, 64, 62, 60, 57, 55, 55, 60, 64, 60, 64, 62, 67, 64, 67, 64, 60, 64, 62, 60, 57, 55, 55, 60, 64, 60, 64, 62, 60)

    /** The tune sung [times] over, with the whole level at [levelCents] (time) above C. */
    private fun sing(times: Int = 2, levelCents: (Double) -> Double): List<SungNote> {
        val out = ArrayList<SungNote>()
        var t = 0.0
        repeat(times) { for (m in tune) { out += SungNote(m + levelCents(t) / 100.0, t, 0.45, 0.0); t += 0.5 } }
        return out
    }

    @Test
    fun aSteadyTuneSitsInOneKey() {
        val l = KeyLevelTracker().analyse(sing { 0.0 })!!
        assertEquals(0.0, l.centreCents, 15.0)
        assertEquals(0, l.nowShift); assertEquals(0, l.lowShift); assertEquals(0, l.highShift)
        assertEquals(KeyLevelTracker.Move.STEADY, l.move)
        // The notes come back as sung, exactly in tune.
        assertEquals(tune, l.notes.take(tune.size).map { it.midi })
    }

    @Test
    fun aSteadyTuneSungFortyCentsSharpIsStillOneKey() {
        val l = KeyLevelTracker().analyse(sing { 40.0 })!!
        assertEquals(40.0, l.centreCents, 15.0)
        assertEquals(0, l.nowShift)
        assertTrue(l.notes.all { kotlin.math.abs(it.pitch - Math.round(it.pitch)) < 0.15 })
    }

    @Test
    fun aSlowCreepIsFollowedAcrossTheSemitone() {
        // C rising steadily to C# plus 50 over 32 seconds: the curve passes the semitone.
        val l = KeyLevelTracker().analyse(sing { t -> 150.0 * t / 32.0 })!!
        assertTrue("now ${l.nowCents}", l.nowCents in 110.0..170.0)
        assertEquals("now nearest C#", 1, Math.round(l.nowCents / 100.0).toInt())
        // Half the time below C# minus 50, half above: the centre is C#, not C.
        assertEquals("centre ${l.centreCents}", 1, Math.round(l.centreCents / 100.0).toInt())
        assertEquals(KeyLevelTracker.Move.RISING, l.move)
        // Moved back to the centre, the tune reads as one key again: the same notes each time round.
        val first = l.notes.take(tune.size).map { it.midi - l.notes[0].midi }
        val second = l.notes.drop(tune.size).map { it.midi - l.notes[0].midi }
        assertEquals(first, second)
    }

    @Test
    fun wanderingBetweenCAndDCentresOnCSharp() {
        val l = KeyLevelTracker().analyse(sing(times = 3) { t -> 100.0 + 100.0 * kotlin.math.sin(2 * Math.PI * t / 24.0) })!!
        // The centre sits on a semitone (the wobble is symmetric), and the range spans one either side.
        val fine = l.centreCents - 100.0 * Math.round(l.centreCents / 100.0)
        assertEquals("centre ${l.centreCents}", 0.0, fine, 25.0)
        assertTrue("range ${l.lowShift}..${l.highShift}", l.lowShift <= -1 && l.highShift >= 1)
        // Moved back to the centre, the tune sits a semitone above how it was written: C#.
        assertEquals(1, Math.floorMod(l.notes[1].midi - tune[1], 12))
    }

    @Test
    fun aCleanStepUpThatHoldsIsANewKey() {
        // Two times round in C, then the last time a semitone up, held.
        val l = KeyLevelTracker().analyse(sing(times = 3) { t -> if (t < 32.0) 0.0 else 100.0 })!!
        assertEquals(KeyLevelTracker.Move.SETTLED, l.move)
        assertEquals(1, l.nowShift)
        assertEquals(0, Math.round(l.centreCents / 100.0).toInt())
    }
}
