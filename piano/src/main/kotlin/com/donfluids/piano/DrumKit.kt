package com.donfluids.piano

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Synthesised kick, snare, hats, shaker and rim; a small pool of one-shot voices. */
class DrumKit(private val sampleRate: Int, voices: Int = 12) {
    private class Hit {
        var active = false
        var drum = Drum.KICK
        var velocity = 0f
        var n = 0
        var phase = 0.0
        var noise = 0f
        var hp = 0f
        var lp = 0f
        var seed = 12345
    }

    private val hits = Array(voices) { Hit() }
    private var next = 0
    private val lock = Any()

    fun trigger(drum: Drum, velocity: Float) {
        synchronized(lock) {
            // Closing the hat silences an open hat, as on a real kit.
            if (drum == Drum.HAT) for (h in hits) if (h.active && h.drum == Drum.OPEN_HAT) h.active = false
            val h = hits.firstOrNull { !it.active } ?: hits[next++ % hits.size]
            h.active = true; h.drum = drum; h.velocity = velocity; h.n = 0; h.phase = 0.0; h.hp = 0f; h.lp = 0f
        }
    }

    fun allOff() { synchronized(lock) { for (h in hits) h.active = false } }

    /** Adds [frames] samples of drum sound into [out] starting at [offset]. */
    fun render(out: FloatArray, offset: Int, frames: Int) {
        synchronized(lock) {
            for (h in hits) {
                if (!h.active) continue
                for (i in 0 until frames) {
                    val t = h.n.toDouble() / sampleRate
                    val s = when (h.drum) {
                        Drum.KICK -> {
                            val f = 50.0 + 110.0 * exp(-t / 0.045)
                            h.phase += 2 * PI * f / sampleRate
                            (sin(h.phase) * exp(-t / 0.22) * 1.1 + click(h, t, 0.004) * 0.3).toFloat()
                        }
                        Drum.SNARE -> {
                            val tone = sin(2 * PI * 185.0 * t) * exp(-t / 0.06) * 0.5
                            val nz = highPassed(h, white(h), 0.2f) * exp(-t / 0.11).toFloat() * 0.9f
                            (tone + nz).toFloat()
                        }
                        Drum.HAT -> (highPassed(h, white(h), 0.7f) * exp(-t / 0.028).toFloat() * 0.5f)
                        Drum.OPEN_HAT -> (highPassed(h, white(h), 0.7f) * exp(-t / 0.25).toFloat() * 0.4f)
                        Drum.SHAKER -> (highPassed(h, white(h), 0.5f) * (if (t < 0.01) (t / 0.01).toFloat() else exp(-(t - 0.01) / 0.05).toFloat()) * 0.4f)
                        Drum.RIM -> (sin(2 * PI * 420.0 * t) * exp(-t / 0.018) * 0.6 + click(h, t, 0.002) * 0.4).toFloat()
                    }
                    out[offset + i] += s * h.velocity
                    h.n++
                }
                val length = when (h.drum) { Drum.KICK -> 0.5; Drum.SNARE -> 0.3; Drum.HAT -> 0.1; Drum.OPEN_HAT -> 0.8; Drum.SHAKER -> 0.2; Drum.RIM -> 0.08 }
                if (h.n > length * sampleRate) h.active = false
            }
        }
    }

    private fun white(h: Hit): Float {
        h.seed = h.seed * 1103515245 + 12345
        return ((h.seed ushr 8) and 0xFFFF) / 32768f - 1f
    }

    /** One-pole high-pass: [k] near 1 keeps only the top. */
    private fun highPassed(h: Hit, x: Float, k: Float): Float {
        h.lp += (x - h.lp) * (1f - k)
        return x - h.lp
    }

    private fun click(h: Hit, t: Double, length: Double): Double = if (t < length) white(h) * (1 - t / length) else 0.0
}
