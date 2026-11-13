package com.donfluids.music.dsp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Reference data for the Beat This! tests, written by tools/beatthis/make_fixtures.py (the same
 * files as the iPhone's tests), and the synthetic signals it was made from. The signals are twins
 * of make_fixtures.py's synth_signal and synth_band and must give the same samples.
 */
object BeatThisFixtures {
    fun json(name: String): JSONObject = JSONObject(javaClass.getResource("/$name.json")!!.readText())

    /** A little-endian float16 file as floats. */
    fun halves(name: String): FloatArray {
        val data = javaClass.getResource("/$name.bin")!!.readBytes()
        return FloatArray(data.size / 2) { i ->
            halfToFloat((data[2 * i].toInt() and 0xFF) or ((data[2 * i + 1].toInt() and 0xFF) shl 8))
        }
    }

    fun halfToFloat(h: Int): Float {
        val sign = if (h shr 15 == 0) 1f else -1f
        val exponent = (h shr 10) and 0x1F
        val mantissa = (h and 0x3FF).toFloat()
        if (exponent == 0) return sign * mantissa * 2f.pow(-24)
        if (exponent == 31) return if (mantissa == 0f) sign * Float.POSITIVE_INFINITY else Float.NaN
        return sign * (1 + mantissa / 1024) * 2f.pow(exponent - 15)
    }

    /** Numerical Recipes' 32-bit LCG, as make_fixtures.py's Lcg. */
    class Lcg(private var s: Int) {
        fun next(): Double {
            s = s * 1664525 + 1013904223
            return (s.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0 * 2.0 - 1.0
        }
    }

    /** make_fixtures.py synth_signal: two tones, a chirp and noise bursts at 22,050 Hz. */
    fun signal(n: Int): FloatArray {
        val rng = Lcg(1)
        return FloatArray(n) { i ->
            val t = i / 22050.0
            var v = 0.3 * sin(2 * PI * 440.0 * t) + 0.1 * sin(2 * PI * 6000.0 * t)
            v += 0.2 * sin(2 * PI * (100.0 * t + 1500.0 * t * t))
            val noise = rng.next()
            if ((i / 2205) % 3 == 1) v += 0.25 * noise
            v.toFloat()
        }
    }

    fun midiHz(m: Int): Double = 440.0 * 2.0.pow((m - 69) / 12.0)

    /** make_fixtures.py synth_band: a loud 4/4 groove at 100 BPM, 44.1 kHz. */
    fun band(rate: Int = 44100, seconds: Double = 12.0, bpm: Double = 100.0): FloatArray {
        val rng = Lcg(7)
        val n = (seconds * rate).toInt()
        val beat = 60.0 / bpm
        val roots = intArrayOf(36, 33, 29, 31)
        var prev = 0.0
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val b = floor(t / beat).toInt()
            val tb = t - b * beat
            val pos = b % 4
            val bar = b / 4
            val half = beat / 2.0
            val te = t - floor(t / half) * half
            val noise = rng.next()
            var v = 0.0
            if (pos == 0 || pos == 2) {
                v += 0.8 * sin(2 * PI * (45.0 * tb + 120.0 * 0.03 * (1.0 - exp(-tb / 0.03)))) * exp(-tb / 0.2)
            } else {
                v += (0.5 * noise + 0.3 * sin(2 * PI * 190.0 * tb)) * exp(-tb / 0.08)
            }
            v += 0.15 * (noise - prev) * exp(-te / 0.015)
            prev = noise
            val root = roots[bar % 4]
            val f = midiHz(root)
            v += 0.35 * (sin(2 * PI * f * tb) + 0.4 * sin(4 * PI * f * tb)) * exp(-tb / 0.5)
            if (pos == 0) {
                val third = if (root == 33) 3 else 4
                for (m in intArrayOf(root + 24, root + 24 + third, root + 31)) {
                    v += 0.08 * (sin(2 * PI * midiHz(m) * tb) + 0.3 * sin(4 * PI * midiHz(m) * tb)) * exp(-tb / 1.0)
                }
            }
            out[i] = (0.6 * v).toFloat()
        }
        return out
    }

    /** A JSON number as a Double, or null for JSON null or a missing field. */
    fun num(o: JSONObject, key: String): Double? = if (o.isNull(key)) null else o.getDouble(key)

    fun nums(a: JSONArray?): List<Double> = if (a == null) emptyList() else List(a.length()) { a.getDouble(it) }

    /** A stored reading's fields. */
    class Reading(d: JSONObject) {
        val t = d.getDouble("t")
        val firstFrame = d.getInt("s0")
        val beats = nums(d.optJSONArray("beats"))
        val downbeats = nums(d.optJSONArray("downbeats"))
        val bpm = num(d, "bpm")
        val period = num(d, "period")
        val last = num(d, "last")
        val beatsPerBar = num(d, "bpb")?.toInt()
        val index = d.getInt("index")
        val locked = d.optBoolean("locked", false)
        val strength = d.getDouble("strength")
        val scores: Map<Int, Double> = d.optJSONObject("scores")?.let { s -> s.keys().asSequence().associate { it.toInt() to s.getDouble(it) } } ?: emptyMap()
    }
}
