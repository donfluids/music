package com.donfluids.piano

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The master stage: louder ordinary playing, no clipping however hot the band, and no
 * pumping on ordinary material.
 */
class LoudnessTest {
    private val rate = 44100
    private fun db(x: Double) = 20 * log10(x.coerceAtLeast(1e-9))
    private fun peak(x: FloatArray) = x.maxOf { abs(it.toDouble()) }
    /** Loudness a phone speaker can reproduce: level above 200 Hz. */
    private fun speakerRms(x: FloatArray): Double {
        val hp = Biquad.highPass(rate, 200.0, 0.707)
        var sq = 0.0
        for (v in x) { val y = hp.next(v.toDouble()); sq += y * y }
        return sqrt(sq / x.size)
    }

    private fun render(seconds: Double, fill: (FloatArray, Int) -> Unit): FloatArray {
        val out = FloatArray((seconds * rate).toInt()); val b = FloatArray(256); var i = 0
        while (i < out.size) { java.util.Arrays.fill(b, 0f); fill(b, 256); for (k in 0 until 256) if (i + k < out.size) out[i + k] = b[k]; i += 256 }
        return out
    }

    private fun piano(notes: List<Int>, vel: Float, vol: Float = 0.8f) = render(1.5) { b, n ->
        val s = synth ?: PianoSynth(rate).also { it.volume = vol; notes.forEach { m -> it.noteOn(m, vel) }; synth = it }
        s.render(b, n)
    }.also { synth = null }
    private var synth: PianoSynth? = null

    private fun band(style: Style, vol: Float, withChord: Boolean = false): FloatArray {
        val a = Accompaniment(rate); a.style = style; a.bpm = 110; a.volume = vol; a.playing = true
        a.setChord(BandChord(0, BandChord.Quality.MAJOR))
        val keys = PianoSynth(rate)
        if (withChord) listOf(48, 60, 64, 67, 72).forEach { keys.noteOn(it, 1f) }
        val tmp = FloatArray(256)
        return render(6.0) { b, n -> keys.render(tmp, n); for (k in 0 until n) b[k] = tmp[k]; a.mix(b, n, 0L) }
    }

    private data class Measured(val name: String, val beforePeak: Double, val afterPeak: Double, val beforeLoud: Double, val afterLoud: Double, val maxReduction: Double, val swing: Double)

    private fun measure(name: String, x: FloatArray, speaker: Boolean = true): Measured {
        val before = FloatArray(x.size) { x[it].coerceIn(-1f, 1f) } // what the phone would play today
        val master = MasterBus(rate).also { it.speakerTuning = speaker }
        val after = x.copyOf()
        var maxRed = 0.0
        val reductions = ArrayList<Double>()
        var i = 0
        while (i < after.size) {
            val n = minOf(256, after.size - i)
            val blk = after.copyOfRange(i, i + n); master.process(blk, n); System.arraycopy(blk, 0, after, i, n)
            maxRed = maxOf(maxRed, master.reductionDb.toDouble())
            if (i > rate) reductions += master.reductionDb.toDouble() // after the first second
            i += n
        }
        // Pumping: how much the limiter's gain swings once the music has settled (10th to 90th percentile).
        val sorted = reductions.sorted()
        val swing = if (sorted.isEmpty()) 0.0 else sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)] - sorted[(sorted.size * 0.1).toInt()]
        val m = Measured(name, db(peak(x)), db(peak(after)), db(speakerRms(before)), db(speakerRms(after)), maxRed, swing)
        println("LOUD %-36s peak %+5.1f -> %+5.1f dBFS | speaker loudness %+5.1f -> %+5.1f dB | limiter up to %4.1f dB, swing %4.1f dB".format(m.name, m.beforePeak, m.afterPeak, m.beforeLoud, m.afterLoud, m.maxReduction, m.swing))
        return m
    }

    @Test
    fun ordinaryPlayingIsLouderAndNothingEverClips() {
        val cases = listOf(
            measure("one note, default volume", piano(listOf(60), 0.7f)),
            measure("melody note high, default", piano(listOf(72), 0.7f)),
            measure("four-note chord, full", piano(listOf(48, 60, 64, 67), 1f, 1f)),
            measure("eight notes, full", piano(listOf(36, 48, 55, 60, 64, 67, 72, 76), 1f, 1f)),
            measure("band Pop 16, default", band(Style.POP_16, 0.8f)),
            measure("band Power Rock, full", band(Style.POWER_ROCK, 1f)),
            measure("band Worship Slow + chord, full", band(Style.WORSHIP_SLOW, 1f, withChord = true)),
            measure("band Power Rock + chord, full", band(Style.POWER_ROCK, 1f, withChord = true)),
        )
        for (c in cases) {
            assertTrue("${c.name} peaks at ${c.afterPeak} dBFS", c.afterPeak <= -0.9)
            // Nothing gets quieter in a way anyone would notice.
            assertTrue("${c.name} lost loudness: $c", c.afterLoud >= c.beforeLoud - 1.0)
            // No pumping: once the music settles, the limiter's gain barely moves.
            assertTrue("${c.name} pumps: $c", c.swing <= 2.0)
        }
        // A single note on the speaker is clearly louder.
        assertTrue("one note: ${cases[0]}", cases[0].afterLoud - cases[0].beforeLoud >= 6.0)
        // The band, which clipped before, keeps its loudness without the clipping.
        assertTrue("band: ${cases[5]}", cases[5].afterLoud >= cases[5].beforeLoud - 3.0)
    }

    @Test
    fun headphonesGetAFlatSoundThatIsStillLouderAndClean() {
        val c = measure("one note, headphones", piano(listOf(60), 0.7f), speaker = false)
        assertTrue(c.afterPeak <= -0.9)
        assertTrue("headphones one note: $c", c.afterLoud - c.beforeLoud >= 6.0)
    }

    @Test
    fun theLimiterDoesNotClick() {
        // A loud chord struck out of silence: the gain change must be smooth.
        val x = piano(listOf(48, 60, 64, 67, 72, 76), 1f, 1f)
        val master = MasterBus(rate)
        val y = x.copyOf(); master.process(y, y.size)
        var worst = 0.0
        for (i in 2 until y.size) {
            // Second difference: a click is a sudden change in slope, far beyond what the notes make.
            worst = maxOf(worst, abs((y[i] - 2 * y[i - 1] + y[i - 2]).toDouble()))
        }
        var notes = 0.0
        val g = Math.pow(10.0, 9.0 / 20.0)
        for (i in 2 until x.size) notes = maxOf(notes, abs((x[i] - 2 * x[i - 1] + x[i - 2]).toDouble()) * g)
        println("LOUD click check: output ${"%.4f".format(worst)} vs input at gain ${"%.4f".format(notes)}")
        assertTrue("limiter adds sharp corners: $worst vs $notes", worst <= notes * 1.5 + 1e-3)
    }
}
