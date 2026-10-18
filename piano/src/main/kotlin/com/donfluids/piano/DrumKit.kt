package com.donfluids.piano

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Synthesised kick, snare, hats, ride, shaker and rim; a pool of one-shot voices. */
class DrumKit(private val sampleRate: Int, voices: Int = 24) {
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

    /** Observes every hit; for tests and displays. */
    @Volatile var onTrigger: ((Drum, Float) -> Unit)? = null

    fun trigger(drum: Drum, velocity: Float) {
        onTrigger?.invoke(drum, velocity)
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
                            // The shell's body drops in pitch as it rings, as a real snare's does, so
                            // it reads as a drum rather than a sung note.
                            val f = 165.0 + 75.0 * exp(-t / 0.012)
                            h.phase += 2 * PI * f / sampleRate
                            val tone = sin(h.phase) * exp(-t / 0.05) * 0.45
                            val nz = highPassed(h, white(h), 0.2f) * exp(-t / 0.11).toFloat() * 0.9f
                            (tone + nz).toFloat()
                        }
                        Drum.HAT -> (highPassed(h, white(h), 0.7f) * exp(-t / 0.028).toFloat() * 0.5f)
                        Drum.OPEN_HAT -> (highPassed(h, white(h), 0.7f) * exp(-t / 0.25).toFloat() * 0.4f)
                        Drum.SHAKER -> (highPassed(h, white(h), 0.5f) * (if (t < 0.01) (t / 0.01).toFloat() else exp(-(t - 0.01) / 0.05).toFloat()) * 0.4f)
                        Drum.RIM -> (sin(2 * PI * 420.0 * t) * exp(-t / 0.018) * 0.6 + click(h, t, 0.002) * 0.4).toFloat()
                        Drum.TOM_HI -> {
                            val f = 160.0 + 90.0 * exp(-t / 0.04)
                            h.phase += 2 * PI * f / sampleRate
                            (sin(h.phase) * exp(-t / 0.18) * 0.9 + click(h, t, 0.003) * 0.25).toFloat()
                        }
                        Drum.TOM_LO -> {
                            val f = 100.0 + 60.0 * exp(-t / 0.05)
                            h.phase += 2 * PI * f / sampleRate
                            (sin(h.phase) * exp(-t / 0.24) * 1.0 + click(h, t, 0.003) * 0.25).toFloat()
                        }
                        Drum.RIDE -> {
                            // A metallic ping over a softer wash: inharmonic partials, medium decay.
                            val ping = (sin(2 * PI * 2350.0 * t) * 0.5 + sin(2 * PI * 3520.0 * t) * 0.35 + sin(2 * PI * 5110.0 * t) * 0.2) * exp(-t / 0.22)
                            (ping * 0.22 + highPassed(h, white(h), 0.75f) * exp(-t / 0.5).toFloat() * 0.18f + click(h, t, 0.002) * 0.15).toFloat()
                        }
                        Drum.CRASH -> {
                            // Bright noise with a touch of inharmonic ring, long decay.
                            val ring = (sin(2 * PI * 3140.0 * t) + sin(2 * PI * 4730.0 * t)) * 0.08 * exp(-t / 0.5)
                            (highPassed(h, white(h), 0.6f) * exp(-t / 0.7).toFloat() * 0.45f + ring).toFloat()
                        }
                    }
                    out[offset + i] += s * h.velocity
                    h.n++
                }
                val length = when (h.drum) { Drum.KICK -> 0.5; Drum.SNARE -> 0.3; Drum.HAT -> 0.1; Drum.OPEN_HAT -> 0.8; Drum.SHAKER -> 0.2; Drum.RIM -> 0.08; Drum.CRASH -> 2.0; Drum.TOM_HI -> 0.4; Drum.TOM_LO -> 0.5; Drum.RIDE -> 1.2 }
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
