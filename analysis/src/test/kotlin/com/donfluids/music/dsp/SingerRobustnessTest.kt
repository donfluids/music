package com.donfluids.music.dsp

import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * One C major hymn-like tune, sung the way real congregations sing it: off-pitch notes,
 * slow slides, scoops, drifting flat. The app may be unsure; it must never be sure and wrong.
 */
class SingerRobustnessTest {
    private val rate = Synth.SAMPLE_RATE
    private val cMajor = Key(0, Mode.MAJOR)
    private val tune = listOf(55 to 1, 60 to 2, 64 to 1, 60 to 1, 64 to 2, 62 to 1, 60 to 2, 57 to 1, 55 to 3,
        55 to 1, 60 to 2, 64 to 1, 60 to 1, 64 to 2, 62 to 1, 67 to 3, 64 to 1, 67 to 2, 64 to 1, 60 to 1,
        64 to 2, 62 to 1, 60 to 2, 57 to 1, 55 to 2, 55 to 1, 60 to 2, 64 to 1, 60 to 1, 64 to 2, 62 to 1, 60 to 4)
    private val beat = 0.45
    private val seeds = listOf(1L, 2L, 3L, 4L, 5L)

    /** [noteErrCents]: each note's own random error; [glideMs]: slide into each note; [driftCents]: flat or sharp by the end. */
    private data class Singing(val noteErrCents: Double, val glideMs: Double, val driftCents: Double, val vibratoCents: Double = 25.0, val scoopCents: Double = 0.0)

    private fun sing(st: Singing, seed: Long): FloatArray {
        val rnd = Random(seed)
        val total = tune.sumOf { it.second } * beat + 1.0
        val out = FloatArray((total * rate).toInt())
        var t0 = 0.3; var phase = 0.0; var prevCents = Double.NaN
        for ((midi, beats) in tune) {
            val len = beats * beat
            val err = rnd.nextGaussian() * st.noteErrCents
            val n = (len * rate).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val at = t0 + t
                var target = midi * 100.0 + err + st.driftCents * (at / total)
                val g = st.glideMs / 1000.0
                if (!prevCents.isNaN() && t < g) target = prevCents + (target - prevCents) * (t / g)
                if (st.scoopCents > 0 && t < 0.15) target -= st.scoopCents * (1 - t / 0.15)
                val vib = if (t > 0.25) st.vibratoCents * sin(2 * PI * 5.3 * t) else 0.0
                phase += 2 * PI * 440.0 * Math.pow(2.0, ((target + vib) / 100.0 - 69) / 12.0) / rate
                val env = minOf(1.0, t / 0.03) * (if (beats >= 3) minOf(1.0, (n - i).toDouble() / rate / 0.15) else 1.0)
                val idx = (at * rate).toInt()
                if (idx < out.size) out[idx] += (0.18 * env * (sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase) + 0.15 * sin(4 * phase))).toFloat()
            }
            prevCents = midi * 100.0 + err + st.driftCents * ((t0 + len) / total)
            t0 += len
        }
        return out
    }

    private fun hear(st: Singing): List<AnalysisResult> = seeds.map { seed ->
        val audio = sing(st, seed)
        val a = MusicAnalyzer(rate, AnalysisPreset.VOICES)
        var i = 0
        while (i < audio.size) { val n = minOf(1024, audio.size - i); a.process(audio.copyOfRange(i, i + n), n, ((i + n).toDouble() / rate * 1e9).toLong()); i += n }
        a.result
    }

    private fun describe(rs: List<AnalysisResult>) = rs.joinToString { "${it.displayKey?.label} pair=${it.pairDecided} mode=${it.modeDecided} spread=${it.singerSpreadCents.toInt()} tuning=${it.tuningCents.toInt()}" }

    /** The one rule for every kind of singing: decided means right. */
    private fun neverSureAndWrong(rs: List<AnalysisResult>) {
        for (r in rs) {
            if (r.modeDecided) assertEquals(describe(rs), cMajor, r.displayKey)
            if (r.pairDecided) assertEquals(describe(rs), cMajor, RelativeKeyResolver.pair(r.displayKey!!).first)
        }
    }

    @Test
    fun goodSingersSlidesAndScoopsAreReadAndDecided() {
        for (st in listOf(Singing(0.0, 40.0, 0.0), Singing(15.0, 80.0, 0.0), Singing(10.0, 250.0, 0.0), Singing(15.0, 80.0, 0.0, scoopCents = 150.0))) {
            val rs = hear(st)
            neverSureAndWrong(rs)
            assertTrue("$st: ${describe(rs)}", rs.all { it.displayKey == cMajor && it.modeDecided })
        }
    }

    @Test
    fun aCleanSingerMeasuresTightAndAScatteredOneLoose() {
        assertTrue(describe(hear(Singing(0.0, 40.0, 0.0))), hear(Singing(0.0, 40.0, 0.0)).all { it.singerSpreadCents < 10f })
        val poor = hear(Singing(50.0, 150.0, 0.0))
        assertTrue(describe(poor), poor.all { it.singerSpreadCents > 25f })
    }

    @Test
    fun anAverageSingerGetsTheRightPair() {
        // About 30 cents off per note. Always shown in the right pair; decided nearly always.
        val rs = hear(Singing(30.0, 120.0, 0.0))
        neverSureAndWrong(rs)
        assertTrue(describe(rs), rs.all { RelativeKeyResolver.pair(it.displayKey!!).first == cMajor })
        assertTrue(describe(rs), rs.count { it.pairDecided } >= 4)
    }

    @Test
    fun aDriftingSingerIsFollowedAndStillReadsC() {
        val rs = hear(Singing(15.0, 80.0, -80.0))
        neverSureAndWrong(rs)
        assertTrue(describe(rs), rs.all { it.displayKey == cMajor })
        // The tracked offset has moved with them, not stayed at concert pitch.
        assertTrue(describe(rs), rs.all { it.tuningCents < -15f })
    }

    @Test
    fun aPoorOrDriftingAndSlidingSingerIsABestGuessNeverAFalseCertainty() {
        for (st in listOf(Singing(50.0, 150.0, 0.0), Singing(45.0, 200.0, -70.0, vibratoCents = 40.0, scoopCents = 100.0))) {
            val rs = hear(st)
            neverSureAndWrong(rs)
            assertTrue("$st: ${describe(rs)}", rs.none { it.modeDecided && it.displayKey != cMajor })
        }
    }
}
