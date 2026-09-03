package com.donfluids.music.dsp

import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Every preset must still get the basics right; they differ in emphasis, not in kind. */
class PresetTest {

    @Test
    fun everyPresetFindsTheKey() {
        val progression = Synth.progression(
            listOf(listOf(50, 57, 62, 66), listOf(55, 59, 62, 67), listOf(57, 61, 64, 69), listOf(50, 57, 62, 66)),
            secondsEach = 2.0,
        )
        for (preset in AnalysisPreset.ALL) {
            val result = Synth.analyze(progression, MusicAnalyzer(Synth.SAMPLE_RATE, preset))
            assertEquals(preset.title, Key(2, Mode.MAJOR), result.key)
        }
    }

    @Test
    fun everyPresetFindsFourFour() {
        val audio = Synth.clickTrack(110.0, 20.0, accents = listOf(1f, 0.4f, 0.7f, 0.4f))
        for (preset in AnalysisPreset.ALL) {
            val result = Synth.analyze(audio, MusicAnalyzer(Synth.SAMPLE_RATE, preset))
            assertNotNull(preset.title, result.bpm)
            assertEquals(preset.title, 110.0, result.bpm!!.toDouble(), 3.5)
            assertEquals(preset.title, "4/4", result.meterLabel)
        }
    }

    @Test
    fun presetLookupFallsBackToBand() {
        assertEquals(AnalysisPreset.BAND, AnalysisPreset.byId(null))
        assertEquals(AnalysisPreset.BAND, AnalysisPreset.byId("nonsense"))
        assertEquals(AnalysisPreset.VOICES, AnalysisPreset.byId("voices"))
    }
}
