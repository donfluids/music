package com.donfluids.music.dsp

import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Untrained singers wander between neighbouring keys. The app shows the centre (the key that
 * keeps a keyboard closest to them overall) and where they are now.
 */
class WanderingSingersTest {
    private val rate = Synth.SAMPLE_RATE
    private val tune = listOf(55 to 1, 60 to 2, 64 to 1, 60 to 1, 64 to 2, 62 to 1, 60 to 2, 57 to 1, 55 to 3,
        55 to 1, 60 to 2, 64 to 1, 60 to 1, 64 to 2, 62 to 1, 67 to 3, 64 to 1, 67 to 2, 64 to 1, 60 to 1,
        64 to 2, 62 to 1, 60 to 2, 57 to 1, 55 to 2, 55 to 1, 60 to 2, 64 to 1, 60 to 1, 64 to 2, 62 to 1, 60 to 4)
    private val beat = 0.45
    private val verses = 3
    private val total = verses * tune.sumOf { it.second } * beat + 1.0
    private val c = Key(0, Mode.MAJOR)

    private fun sing(level: (Double) -> Double, errCents: Double, seed: Long = 1): FloatArray {
        val rnd = Random(seed)
        val song = (1..verses).flatMap { tune }
        val out = FloatArray((total * rate).toInt())
        var t0 = 0.3; var phase = 0.0
        for ((midi, beats) in song) {
            val len = beats * beat; val e = rnd.nextGaussian() * errCents; val n = (len * rate).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / rate; val at = t0 + t
                val vib = if (t > 0.25) 25.0 * sin(2 * PI * 5.3 * t) else 0.0
                phase += 2 * PI * 440.0 * Math.pow(2.0, ((midi * 100.0 + e + level(at) + vib) / 100.0 - 69) / 12.0) / rate
                val env = minOf(1.0, t / 0.03) * (if (beats >= 3) minOf(1.0, (n - i).toDouble() / rate / 0.15) else 1.0)
                val idx = (at * rate).toInt()
                if (idx < out.size) out[idx] += (0.18 * env * (sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase))).toFloat()
            }
            t0 += len
        }
        return out
    }

    private fun hear(audio: FloatArray): AnalysisResult {
        val a = MusicAnalyzer(rate, AnalysisPreset.VOICES)
        var i = 0
        while (i < audio.size) { val n = minOf(1024, audio.size - i); a.process(audio.copyOfRange(i, i + n), n, ((i + n).toDouble() / rate * 1e9).toLong()); i += n }
        return a.result
    }

    private fun describe(r: AnalysisResult) = "centre ${r.displayKey?.label} (pair=${r.pairDecided} mode=${r.modeDecided}) now ${r.keyNow?.label} range ${r.keyRangeLow?.label}..${r.keyRangeHigh?.label} ${r.keyMove} tuning ${r.tuningCents.toInt()} spread ${r.singerSpreadCents.toInt()}"
    private fun pairOf(k: Key?) = k?.let { RelativeKeyResolver.pair(it).first }

    @Test
    fun steadySingingIsOneKeyWithNoWandering() {
        val r = hear(sing({ 0.0 }, 10.0))
        println("steady: ${describe(r)}")
        assertEquals(describe(r), c, r.displayKey)
        assertEquals(describe(r), c, r.keyNow)
        assertEquals(describe(r), c, r.keyRangeLow); assertEquals(describe(r), c, r.keyRangeHigh)
    }

    @Test
    fun risingFromCToDAndBackCentresOnCSharpAndEndsInC() {
        val r = hear(sing({ t -> 100.0 * (1 - cos(2 * PI * t / total)) }, 10.0))
        println("C to D and back: ${describe(r)}")
        assertEquals(describe(r), c.transposed(1), pairOf(r.displayKey))
        assertEquals(describe(r), c, pairOf(r.keyNow))
        assertEquals(describe(r), c, pairOf(r.keyRangeLow)); assertEquals(describe(r), c.transposed(2), pairOf(r.keyRangeHigh))
    }

    @Test
    fun wobblingBetweenCAndDCentresOnCSharp() {
        val r = hear(sing({ t -> 100.0 + 100.0 * sin(2 * PI * t / 25.0) }, 10.0))
        println("wobbling C..D: ${describe(r)}")
        assertEquals(describe(r), c.transposed(1), pairOf(r.displayKey))
        assertTrue(describe(r), pairOf(r.keyRangeLow) == c && pairOf(r.keyRangeHigh) == c.transposed(2))
    }

    @Test
    fun anAverageSingerCreepingSharpIsFollowed() {
        val r = hear(sing({ t -> 150.0 * t / total }, 30.0))
        println("creeping sharp: ${describe(r)}")
        assertTrue(describe(r), pairOf(r.keyNow) == c.transposed(1) || pairOf(r.keyNow) == c.transposed(2))
        assertEquals(describe(r), KeyLevelTracker.Move.RISING, r.keyMove)
        // Never sure of a key the singing was not in.
        if (r.pairDecided) assertTrue(describe(r), pairOf(r.displayKey) in listOf(c, c.transposed(1)))
    }

    @Test
    fun aLastVerseLiftedASemitoneIsANewKey() {
        val lift = 2 * tune.sumOf { it.second } * beat + 0.3
        val r = hear(sing({ t -> if (t < lift) 0.0 else 100.0 }, 10.0))
        println("lifted last verse: ${describe(r)}")
        assertEquals(describe(r), c, r.displayKey)
        assertEquals(describe(r), c.transposed(1), r.keyNow)
        assertEquals(describe(r), KeyLevelTracker.Move.SETTLED, r.keyMove)
    }
}
