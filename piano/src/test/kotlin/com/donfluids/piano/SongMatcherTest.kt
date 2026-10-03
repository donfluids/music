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
    fun waitsUntilSure() {
        assertNull(SongMatcher.match(AnalysisResult()))
        assertNull(SongMatcher.match(heard(100f, "4/4", locked = false)))
        assertNull(SongMatcher.match(heard(100f, "4/4", meterConfidence = 0.1f)))
    }
}
