package com.donfluids.music.dsp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

class BasicPitchTest {
    private val model = BasicPitch(TestNotes.weights)
    private val ref = JSONObject(javaClass.getResource("/basic-pitch-reference.json")!!.readText())

    /** The reference script's test signal: C major (C3 E3 G3 C4), then A minor (A2 E3 A3 C4). */
    private fun testSignal(): FloatArray {
        val x = DoubleArray(BasicPitch.WINDOW)
        fun tone(midi: Int, start: Double, end: Double) {
            val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
            for (i in x.indices) {
                val t = i / 22050.0
                if (t < start || t >= end) continue
                val tt = t - start
                var s = 0.0
                for (h in 1..6) s += 0.6.pow(h - 1) * sin(2 * PI * f * h * tt)
                x[i] += s * exp(-tt * 1.5) * 0.08
            }
        }
        for (m in listOf(48, 52, 55, 60)) tone(m, 0.05, 1.0)
        for (m in listOf(45, 52, 57, 60)) tone(m, 1.0, 2.0)
        return FloatArray(x.size) { x[it].toFloat() }
    }

    private fun maxRelDiff(a: FloatArray, b: org.json.JSONArray): Double {
        var worst = 0.0
        var peak = 0.0
        for (i in a.indices) peak = maxOf(peak, abs(b.getDouble(i)))
        for (i in a.indices) worst = maxOf(worst, abs(a[i] - b.getDouble(i)) / peak)
        return worst
    }

    @Test
    fun constantQMatchesTheReference() {
        val x = testSignal()
        for (t in listOf(0, 100, 171)) {
            val d = maxRelDiff(model.cqtFrame(x, t), ref.getJSONArray("cqtFrame$t"))
            assertTrue("frame $t: $d", d < 1e-4)
        }
    }

    @Test
    fun contourAndNotesMatchTheReference() {
        val x = testSignal()
        val c = model.contourFrame(x, 100)
        val cr = ref.getJSONArray("contourFrame100")
        for (i in c.indices) assertEquals("contour bin $i", cr.getDouble(i), c[i].toDouble(), 2e-3)
        val out = Array(172) { FloatArray(88) }
        model.notes(x, 0, 172, out)
        val nr = ref.getJSONArray("note")
        var worst = 0.0
        for (t in 0 until 172) for (k in 0 until 88) worst = maxOf(worst, abs(out[t][k] - nr.getJSONArray(t).getDouble(k)))
        assertTrue("worst note difference $worst", worst < 2e-3)
        // A slice gives the same frames as the whole window.
        val part = Array(20) { FloatArray(88) }
        model.notes(x, 137, 157, part)
        for (t in 0 until 20) for (k in 0 until 88) assertEquals(out[137 + t][k], part[t][k], 1e-5f)
    }

    @Test
    fun theChordsAreHeard() {
        val out = Array(172) { FloatArray(88) }
        model.notes(testSignal(), 0, 172, out)
        fun heard(t: Int) = (0 until 88).filter { out[t][it] > 0.3f }.map { it + BasicPitch.LOWEST_MIDI }.toSet()
        assertTrue(heard(40).toString(), heard(40).containsAll(listOf(48, 52, 55, 60)))
        assertTrue(heard(140).toString(), heard(140).containsAll(listOf(45, 52, 57, 60)))
    }

    @Test
    fun timing() {
        val x = testSignal()
        val part = Array(43) { FloatArray(88) }
        repeat(3) { model.notes(x, 114, 157, part) }
        val n = 10
        val start = System.nanoTime()
        repeat(n) { model.notes(x, 114, 157, part) }
        println("Basic Pitch: %.1f ms per run of 43 frames (0.5 s of audio) on this JVM".format((System.nanoTime() - start) / 1e6 / n))
    }
}
