package com.donfluids.music.history

import com.donfluids.music.UiState
import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SongHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val g = Key(7, Mode.MAJOR)
    private val em = Key(4, Mode.MINOR)

    @Test fun survivesARestart() {
        val file = tmp.newFolder().resolve("history.json")
        val a = SongRecord(1000L, g, transpose = 2, bpm = 96, bpmTapped = true, seconds = 200, presetId = "voices")
        val b = SongRecord(2000L, em, bpm = 120, meter = "6/8", seconds = 180, presetId = "band")
        SongHistory(file).apply { add(a); add(b) }
        assertEquals(listOf(b, a), SongHistory(file).all())
    }

    @Test fun keepsTheNewestUpToTheLimit() {
        val h = SongHistory(tmp.newFolder().resolve("h.json"), limit = 3)
        (1..5).forEach { h.add(SongRecord(it.toLong(), g)) }
        assertEquals(listOf(5L, 4L, 3L), h.all().map { it.atMillis })
    }

    @Test fun removesOneOrAll() {
        val file = tmp.newFolder().resolve("h.json")
        val h = SongHistory(file)
        (1..3).forEach { h.add(SongRecord(it.toLong(), g)) }
        assertEquals(listOf(3L, 1L), h.remove(2L).map { it.atMillis })
        assertTrue(h.clear().isEmpty())
        assertTrue(SongHistory(file).all().isEmpty())
    }

    @Test fun anUndoneDeleteGoesBackInPlace() {
        val h = SongHistory(tmp.newFolder().resolve("h.json"))
        val songs = (1..3).map { SongRecord(it.toLong(), g) }
        songs.forEach { h.add(it) }
        h.remove(2L)
        assertEquals(listOf(3L, 2L, 1L), h.add(songs[1]).map { it.atMillis })
    }

    @Test fun aDamagedFileStartsEmpty() {
        val file = tmp.newFolder().resolve("h.json").apply { writeText("{not json") }
        assertTrue(SongHistory(file).all().isEmpty())
    }

    @Test fun savesWhatIsOnScreen() {
        val heard = UiState(
            preset = AnalysisPreset.BAND, transpose = -1, tempoMultiplier = 2f,
            analysis = AnalysisResult(secondsHeard = 95f, displayKey = g, key = em, bpm = 62f, tempoLocked = true, meterLabel = "4/4"),
        )
        val r = SongRecord.from(heard, 42L)!!
        assertEquals(SongRecord(42L, g, transpose = -1, bpm = 124, bpmTapped = false, meter = "4/4", seconds = 95, presetId = "band"), r)
        // Hold wins over the analysis.
        assertEquals(em, SongRecord.from(heard.copy(heldKey = em), 0L)!!.key)
    }

    @Test fun voicesSaveTheTappedTempoAndNoMetre() {
        val sung = UiState(
            preset = AnalysisPreset.VOICES, tapBpm = 71.6f,
            analysis = AnalysisResult(secondsHeard = 60f, displayKey = g, bpm = 140f, tempoLocked = true, meterLabel = "3/4"),
        )
        val r = SongRecord.from(sung, 0L)!!
        assertEquals(72, r.bpm)
        assertTrue(r.bpmTapped)
        assertNull(r.meter)
        assertNull(SongRecord.from(sung.copy(tapBpm = null), 0L)!!.bpm)
    }

    @Test fun falseStartsAreNotSaved() {
        assertNull(SongRecord.from(UiState(analysis = AnalysisResult(secondsHeard = 60f)), 0L))
        assertNull(SongRecord.from(UiState(analysis = AnalysisResult(secondsHeard = 10f, displayKey = g)), 0L))
    }
}
