package com.donfluids.music.dsp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sin

class CrepePitchTest {
    private val weights = TestPitch.weights
    private val rate = 44100

    @Test
    fun theNetworkMatchesTheReferenceImplementation() {
        // Activations from a numpy implementation run on the authors' Keras weights (rounded to
        // half precision, as shipped): a voice-like tone, a tone with vibrato, and noise.
        val ref = JSONObject(javaClass.getResource("/crepe-reference.json")!!.readText())
        val frames = ref.getJSONArray("frames")
        val expected = ref.getJSONArray("activations")
        val crepe = CrepePitch(weights, rate)
        for (f in 0 until frames.length()) {
            val input = frames.getJSONArray(f).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            val got = crepe.activationsOf(input)
            val want = expected.getJSONArray(f)
            var worst = 0.0
            for (k in 0 until CrepePitch.BINS) worst = maxOf(worst, abs(got[k] - want.getDouble(k)))
            assertTrue("frame $f differs by $worst", worst < 2e-3)
        }
    }

    private fun voice(hz: Double, seconds: Double = 0.3): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            (0.3 * sin(2 * PI * hz * t) + 0.15 * sin(4 * PI * hz * t) + 0.08 * sin(6 * PI * hz * t)).toFloat()
        }
    }

    private fun cents(a: Double, b: Double) = 1200 * log2(a / b)

    @Test
    fun findsTheFundamentalAcrossTheSingingRange() {
        for (refine in listOf(false, true)) {
            val crepe = CrepePitch(weights, rate, refine = refine)
            for (hz in listOf(87.31, 110.0, 196.0, 261.63, 440.0, 659.25, 880.0)) {
                val x = voice(hz)
                assertTrue("$hz voiced", crepe.process(x, x.size))
                val err = cents(crepe.hz, hz)
                assertTrue("$hz refine=$refine read ${crepe.hz} ($err cents)", abs(err) < if (refine) 3.0 else 15.0)
            }
        }
    }

    @Test
    fun silenceAndNoiseAreNotVoiced() {
        val crepe = CrepePitch(weights, rate)
        val silence = FloatArray(rate / 4)
        assertFalse(crepe.process(silence, silence.size))
        val rnd = java.util.Random(4)
        val noise = FloatArray(rate / 4) { (rnd.nextGaussian() * 0.1).toFloat() }
        assertFalse("noise read as ${crepe.hz} at ${crepe.clarity}", crepe.process(noise, noise.size))
    }

    @Test
    fun theOldTrackerReadsALowNoteTenTimesTooHigh() {
        // Kept as a record of why the tracker was replaced: at 110 Hz the autocorrelation
        // tracker's "first strong peak" search accepted the shortest lag against an unfilled
        // neighbour. CREPE reads the note.
        val x = voice(110.0)
        val old = PitchTracker(rate)
        old.process(x, x.size)
        val crepe = CrepePitch(weights, rate)
        crepe.process(x, x.size)
        assertEquals(110.0, crepe.hz, 1.0)
        println("autocorrelation tracker read ${old.hz} Hz for 110 Hz")
    }

    @Test
    fun timing() {
        val crepe = CrepePitch(weights, rate)
        val x = voice(220.0, 2.0)
        repeat(20) { crepe.process(x, x.size) }
        val runs = 100
        val t0 = System.nanoTime()
        repeat(runs) { crepe.process(x, x.size - it * 100) }
        println("CREPE-tiny on this JVM: ${"%.2f".format((System.nanoTime() - t0) / 1e6 / runs)} ms per frame")
    }
}
