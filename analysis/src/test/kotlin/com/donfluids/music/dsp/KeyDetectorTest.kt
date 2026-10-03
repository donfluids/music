package com.donfluids.music.dsp

import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDetectorTest {

    @Test
    fun cMajorScaleProfileIsCMajor() {
        // C D E F G A B with a heavy tonic and dominant.
        val chroma = floatArrayOf(5f, 0f, 2f, 0f, 3f, 2f, 0f, 4f, 0f, 2f, 0f, 1.5f)
        val ranked = KeyDetector.rank(chroma)
        assertEquals(Key(0, Mode.MAJOR), ranked.first().key)
        assertTrue(KeyDetector.confidence(ranked) > 0.3)
    }

    @Test
    fun aMinorWithLeadingToneIsAMinor() {
        // A B C D E F G# with emphasis on A, E and C.
        val chroma = floatArrayOf(3f, 0f, 1.5f, 0f, 3.5f, 1.5f, 0f, 0.5f, 2f, 5f, 0f, 1.5f)
        val ranked = KeyDetector.rank(chroma)
        assertEquals(Key(9, Mode.MINOR), ranked.first().key)
    }

    @Test
    fun silenceHasNoConfidence() {
        val ranked = KeyDetector.rank(FloatArray(12))
        assertEquals(0.0, KeyDetector.confidence(ranked), 1e-9)
    }

    @Test
    fun synthesisedDMajorProgressionIsDMajor() {
        // D - G - A - Bm - D voiced in the range a congregation sings.
        val progression = Synth.progression(
            listOf(
                listOf(50, 57, 62, 66), // D
                listOf(55, 59, 62, 67), // G
                listOf(57, 61, 64, 69), // A
                listOf(59, 62, 66, 71), // Bm
                listOf(50, 57, 62, 66), // D
            ),
            secondsEach = 1.5,
        )
        val result = Synth.analyze(progression)
        assertEquals(Key(2, Mode.MAJOR), result.key)
        assertTrue("confidence ${result.keyConfidence}", result.keyConfidence > 0.3f)
        assertTrue("tuning ${result.tuningCents}", kotlin.math.abs(result.tuningCents) < 5f)
    }

    @Test
    fun sharpSingingReportsTuningOffset() {
        // Every note 30 cents sharp: shift the reference the synth uses.
        val n = (3.0 * Synth.SAMPLE_RATE).toInt()
        val notes = listOf(57, 61, 64)
        val out = FloatArray(n)
        for (midi in notes) {
            val f = Synth.midiToHz(midi) * Math.pow(2.0, 30.0 / 1200.0)
            for (i in 0 until n) out[i] += (0.1 * kotlin.math.sin(2 * Math.PI * f * i / Synth.SAMPLE_RATE)).toFloat()
        }
        val result = Synth.analyze(out)
        assertEquals(30f, result.tuningCents, 6f)
    }
}
