package com.donfluids.piano

import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongMatcherTest {
    private fun heard(bpm: Float, meter: String, compound: Boolean = false, key: Key? = Key(7, Mode.MAJOR), locked: Boolean = true, meterConfidence: Float = 0.6f) =
        AnalysisResult(bpm = bpm, tempoLocked = locked, meterLabel = meter, meterCompound = compound, meterConfidence = meterConfidence, key = key, displayKey = key)

    @Test
    fun picksByMeterThenTempo() {
        assertEquals(Style.WORSHIP_SLOW, SongMatcher.match(heard(72f, "4/4"))!!.style)
        assertEquals(Style.POP_16, SongMatcher.match(heard(100f, "4/4"))!!.style)
        assertEquals(Style.ROCK_8, SongMatcher.match(heard(124f, "4/4"))!!.style)
        assertEquals(Style.POWER_ROCK, SongMatcher.match(heard(150f, "4/4"))!!.style)
        assertEquals(Style.WALTZ, SongMatcher.match(heard(96f, "3/4"))!!.style)
        assertEquals(Style.WORSHIP_68, SongMatcher.match(heard(56f, "6/8", compound = true))!!.style)
        assertEquals(Style.WORSHIP_68, SongMatcher.match(heard(60f, "12/8", compound = true))!!.style)
    }

    @Test
    fun carriesTempoKeyAndTonicChord() {
        val m = SongMatcher.match(heard(119.6f, "4/4", key = Key(4, Mode.MINOR)))!!
        assertEquals(120, m.bpm)
        assertEquals(Theory.Scale(4, minor = true), m.scale)
        assertEquals(BandChord(4, BandChord.Quality.MINOR), m.chord)
        val noKey = SongMatcher.match(heard(100f, "4/4", key = null))!!
        assertNull(noKey.scale)
        assertNull(noKey.chord)
    }

    @Test
    fun nextDownbeatFollowsTheAnalysersGrid() {
        val period = 500_000_000L // 120 BPM
        // Last beat at 10.0 s was beat index 1 of a four-beat bar: the downbeat is three beats on, at 11.5 s.
        val a = AnalysisResult(bpm = 120f, beatPeriodMillis = 500f, lastBeatAtNanos = 10_000_000_000L, beatsPerBar = 4, lastBeatIndexInBar = 1)
        assertEquals(11_500_000_000L, SongMatcher.nextDownbeatNanos(a, nowNanos = 10_100_000_000L))
        // Too close to now: step a whole bar forward.
        assertEquals(13_500_000_000L, SongMatcher.nextDownbeatNanos(a, nowNanos = 11_400_000_000L))
        // On the downbeat itself, the next one is a bar later.
        val onOne = AnalysisResult(bpm = 120f, beatPeriodMillis = 500f, lastBeatAtNanos = 10_000_000_000L, beatsPerBar = 4, lastBeatIndexInBar = 0)
        assertEquals(12_000_000_000L, SongMatcher.nextDownbeatNanos(onOne, nowNanos = 10_000_000_000L))
        assertNull(SongMatcher.nextDownbeatNanos(AnalysisResult(), 0L))
        assertEquals(4 * period, 2_000_000_000L)
    }

    @Test
    fun followsOnlyChordsInTheKey() {
        val g = Theory.Scale(7, minor = false)
        assertEquals(true, SongMatcher.followable(BandChord(0, BandChord.Quality.MAJOR), g))   // C
        assertEquals(true, SongMatcher.followable(BandChord(4, BandChord.Quality.MINOR), g))   // Em
        assertEquals(false, SongMatcher.followable(BandChord(5, BandChord.Quality.MAJOR), g))  // F has no place in G
        assertEquals(false, SongMatcher.followable(BandChord(4, BandChord.Quality.MAJOR), g))  // E major has G#
        assertEquals(true, SongMatcher.followable(BandChord(5, BandChord.Quality.MAJOR), null))
    }

    @Test
    fun waitsUntilSure() {
        assertNull(SongMatcher.match(AnalysisResult()))
        assertNull(SongMatcher.match(heard(100f, "4/4", locked = false)))
        assertNull(SongMatcher.match(heard(100f, "4/4", meterConfidence = 0.1f)))
    }

    @Test
    fun asksForTapsWhenTheKeyIsInButNoPulseLocks() {
        val sung = AnalysisResult(secondsHeard = 9f, tempoLocked = false, key = Key(4, Mode.MAJOR), displayKey = Key(4, Mode.MAJOR))
        assertEquals(true, SongMatcher.needsTap(sung))
        assertEquals(false, SongMatcher.needsTap(sung.copy(secondsHeard = 5f)))
        assertEquals(false, SongMatcher.needsTap(sung.copy(key = null, displayKey = null)))
        assertEquals(false, SongMatcher.needsTap(sung.copy(tempoLocked = true)))
    }

    @Test
    fun tappedMatchTakesTheKeyFromTheEarAndTheStyleFromTheTaps() {
        val sung = AnalysisResult(secondsHeard = 9f, key = Key(4, Mode.MAJOR), displayKey = Key(4, Mode.MAJOR))
        val slow = SongMatcher.matchTapped(sung, 76, 4)
        assertEquals(Style.WORSHIP_SLOW, slow.style)
        assertEquals(76, slow.bpm)
        assertEquals(Theory.Scale(4, minor = false), slow.scale)
        assertEquals(BandChord(4, BandChord.Quality.MAJOR), slow.chord)
        assertEquals(Style.WALTZ, SongMatcher.matchTapped(sung, 100, 3).style)
        assertEquals(Style.WORSHIP_68, SongMatcher.matchTapped(sung, 60, 6).style)
        assertEquals(Style.POWER_ROCK, SongMatcher.matchTapped(sung, 150, 4).style)
    }

    @Test
    fun fourTapsSetTheTempoAndTheNextDownbeat() {
        val t = TapEntry()
        val s = 1_000_000_000L
        assertNull(t.tap(10 * s)); assertEquals(1, t.count)
        assertNull(t.tap(10 * s + 750_000_000L))
        assertNull(t.tap(11 * s + 500_000_000L))
        val e = t.tap(12 * s + 250_000_000L)!!
        assertEquals(80, e.bpm)
        assertEquals(750_000_000L, e.periodNanos)
        assertEquals(13 * s, e.downbeatNanos)
        assertEquals(0, t.count)
        // A long pause starts the count over.
        assertNull(t.tap(20 * s)); assertNull(t.tap(20 * s + 500_000_000L))
        assertNull(t.tap(25 * s)); assertEquals(1, t.count)
        // A waltz counts three.
        val w = TapEntry(beatsNeeded = 3)
        assertNull(w.tap(0)); assertNull(w.tap(s))
        assertEquals(60, w.tap(2 * s)!!.bpm)
    }

    @Test
    fun theKeyIsSettledOnceItHasHeldStill() {
        val e = Key(4, Mode.MAJOR)
        val heard = AnalysisResult(key = e, displayKey = e, keyConfidence = 0.5f, keyHeldSeconds = 7f)
        assertEquals(true, SongMatcher.keySettled(heard, 6))
        assertEquals(false, SongMatcher.keySettled(heard.copy(keyHeldSeconds = 2f), 6))
        assertEquals(false, SongMatcher.keySettled(heard.copy(keyConfidence = 0.1f), 6))
        assertEquals(false, SongMatcher.keySettled(AnalysisResult(keyHeldSeconds = 20f), 6))
    }
}
