package com.donfluids.music.dsp

import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** A lone voice singing between the keys, with slides: the case a chroma key finder blurs. */
class MelodyKeyTest {
    private val rate = Synth.SAMPLE_RATE

    /** A sung line: each note slides in from a semitone below over 60 ms, has vibrato, and sits [offsetCents] off the grid. */
    private fun sing(notes: List<Pair<Int, Double>>, offsetCents: Double, gapAfter: Set<Int> = emptySet()): FloatArray {
        val total = notes.sumOf { it.second } + gapAfter.size * 0.5 + 0.5
        val out = FloatArray((total * rate).toInt())
        var t0 = 0.0
        for ((k, note) in notes.withIndex()) {
            val (midi, len) = note
            val f = 440.0 * Math.pow(2.0, (midi - 69 + offsetCents / 100.0) / 12.0)
            val n = (len * rate).toInt()
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val slide = if (t < 0.06) Math.pow(2.0, -(1.0 - t / 0.06) / 12.0) else 1.0
                val vib = 1.0 + 0.004 * sin(2 * PI * 5.5 * t)
                phase += 2 * PI * f * slide * vib / rate
                val env = minOf(1.0, t / 0.02) * minOf(1.0, (n - i).toDouble() / rate / 0.04)
                val idx = (t0 * rate).toInt() + i
                if (idx < out.size) out[idx] += (0.2 * env * (sin(phase) + 0.5 * sin(2 * phase) + 0.25 * sin(3 * phase))).toFloat()
            }
            t0 += len + if (k in gapAfter) 0.5 else 0.0
        }
        return out
    }

    // An E major tune: ends phrases on E, longest note E, uses G#, F#, A, B, D#.
    private val tune = listOf(
        63 to 0.3, 64 to 0.5, 68 to 0.4, 66 to 0.3, 64 to 0.8,   // D# E G# F# E(long)   phrase
        69 to 0.3, 68 to 0.3, 66 to 0.3, 71 to 0.4, 68 to 0.3,   // A G# F# B G#
        66 to 0.3, 64 to 0.3, 63 to 0.3, 64 to 0.9,              // F# E D# E(long)      phrase end
    )

    @Test
    fun sharpSingingWithSlidesStillReadsEMajorAndTheTuning() {
        val audio = sing(tune, offsetCents = 40.0, gapAfter = setOf(4, 13))
        val result = Synth.analyze(audio, MusicAnalyzer(rate, AnalysisPreset.VOICES))
        assertTrue("notes found: ${result.sungNotes.size}", result.sungNotes.size >= 10)
        assertTrue("key from melody", result.keyFromMelody)
        assertEquals(Key(4, Mode.MAJOR), result.key)
        assertEquals("tuning ${result.tuningCents}", 40f, result.tuningCents, 12f)
        // The notes landed on single pitch classes: no D#/E split from the sharpness.
        val pcs = result.sungNotes.map { Math.floorMod(it.midi, 12) }
        assertTrue("E dominant: $pcs", pcs.count { it == 4 } >= pcs.count { it == 5 } * 2)
    }

    @Test
    fun flatSingingReadsTheSameKey() {
        val audio = sing(tune, offsetCents = -35.0, gapAfter = setOf(4, 13))
        val result = Synth.analyze(audio, MusicAnalyzer(rate, AnalysisPreset.VOICES))
        assertEquals(Key(4, Mode.MAJOR), result.key)
        assertEquals(-35f, result.tuningCents, 12f)
    }

    @Test
    fun melodicFinderUsesPhraseEndingsAndTheLongestNote() {
        // Same pitch classes as C major and A minor; the phrases end on A and A is longest: A minor.
        val notes = listOf(
            SungNote(60, 0.0, 0.3), SungNote(64, 0.3, 0.3), SungNote(67, 0.6, 0.3), SungNote(69, 0.9, 1.0),
            SungNote(62, 2.5, 0.3), SungNote(65, 2.8, 0.3), SungNote(64, 3.1, 0.3), SungNote(69, 3.4, 0.9),
        )
        assertEquals(Key(9, Mode.MINOR), MelodicKeyFinder.rank(notes)[0].key)
        // Same notes, phrases ending on C with C longest: C major.
        val cNotes = notes.map { if (it.midi == 69) it.copy(midi = 72) else it }
        assertEquals(Key(0, Mode.MAJOR), MelodicKeyFinder.rank(cNotes)[0].key)
    }

    @Test
    fun pitchTrackerFindsTheFundamentalNotAnOctave() {
        val tracker = PitchTracker(rate)
        for (midi in listOf(43, 50, 57, 64, 72)) {
            val f = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            val x = FloatArray(4096) { (0.2 * (sin(2 * PI * f * it / rate) + 0.6 * sin(4 * PI * f * it / rate) + 0.3 * sin(6 * PI * f * it / rate))).toFloat() }
            assertTrue(tracker.process(x, 4096))
            assertTrue("midi $midi got ${tracker.midi}", abs(tracker.midi - midi) < 0.1)
        }
    }

    @Test
    fun theShownKeyHoldsStillWhileThePhraseIsSung() {
        // A line that starts on the dominant and the sixth, which also fit B major and C# minor,
        // then settles on E. The display should move at most once or twice, never flicker.
        val rate = 44100
        val line = listOf(71, 73, 71, 68, 71, 73, 76, 73, 71, 68, 66, 64, 64, 68, 71, 64)
        val audio = sing(line.mapIndexed { i, m -> m to (if (i == line.size - 1) 0.9 else 0.35) }, offsetCents = 0.0, gapAfter = setOf(7))
        val analyzer = MusicAnalyzer(rate, AnalysisPreset.VOICES)
        val seen = ArrayList<String>()
        var i = 0
        while (i < audio.size) {
            val n = minOf(1024, audio.size - i)
            analyzer.process(audio.copyOfRange(i, i + n), n, ((i + n).toDouble() / rate * 1e9).toLong())
            val k = analyzer.result.key?.label
            if (k != null && (seen.isEmpty() || seen.last() != k)) { seen += k; println("t=${"%.2f".format((i + n).toDouble() / rate)} key=$k held=${analyzer.result.keyHeldSeconds} melody=${analyzer.result.keyFromMelody}") }
            i += n
        }
        assertTrue("shown keys: $seen", seen.size <= 2)
        assertEquals("E major", seen.last())
        val r = analyzer.result
        assertTrue("held ${r.keyHeldSeconds} s", r.keyHeldSeconds > 0.5f)
        assertTrue("score ${r.keyScore}", r.keyScore > 0.3f)
    }

    private fun analyse(audio: FloatArray): AnalysisResult {
        val analyzer = MusicAnalyzer(rate, AnalysisPreset.VOICES)
        var i = 0
        while (i < audio.size) {
            val n = minOf(1024, audio.size - i)
            analyzer.process(audio.copyOfRange(i, i + n), n, ((i + n).toDouble() / rate * 1e9).toLong())
            i += n
        }
        return analyzer.result
    }

    @Test
    fun relativeMajorAndMinorAreToldApartFromTheSinging() {
        // The same white notes, twice: once resting on C, once resting on A with a G# before it.
        val inC = listOf(60 to .4, 62 to .4, 64 to .4, 65 to .4, 67 to .8, 69 to .4, 67 to .4, 65 to .4, 64 to .4, 62 to .4, 60 to 1.2)
        val inA = listOf(69 to .4, 71 to .4, 72 to .4, 74 to .4, 76 to .8, 74 to .4, 72 to .4, 71 to .4, 68 to .4, 71 to .4, 69 to 1.2)
        val c = analyse(sing(inC + inC, offsetCents = 0.0, gapAfter = setOf(4, 10, 15)))
        val a = analyse(sing(inA + inA, offsetCents = 0.0, gapAfter = setOf(4, 10, 15)))
        println("C tune: ${c.key?.label} decided=${c.modeDecided} score=${c.modeScore}; A tune: ${a.key?.label} decided=${a.modeDecided} score=${a.modeScore}")
        assertEquals(Key(0, Mode.MAJOR), c.key)
        assertTrue("C decided, score ${c.modeScore}", c.modeDecided)
        assertEquals(Key(9, Mode.MINOR), a.key)
        assertTrue("A decided, score ${a.modeScore}", a.modeDecided)
        assertEquals(Key(0, Mode.MAJOR), a.relativeKey)
    }
}
