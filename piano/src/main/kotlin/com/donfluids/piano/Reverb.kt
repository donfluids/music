package com.donfluids.piano

/**
 * A small room: four parallel comb filters into two all-pass filters (Schroeder), mixed back
 * at a low level. Enough to take the dryness off a synthesised tone; not a cathedral.
 */
class Reverb(private val sampleRate: Int) {
    @Volatile var enabled: Boolean = false
    /** Wet level, 0..1. */
    @Volatile var mix: Float = 0.22f

    private class Comb(size: Int, val feedback: Float) {
        val buf = FloatArray(size); var pos = 0; var filtered = 0f
        fun process(x: Float): Float {
            val y = buf[pos]
            // A one-pole low-pass in the loop makes the tail darker as it decays, like a room.
            filtered = y * 0.6f + filtered * 0.4f
            buf[pos] = x + filtered * feedback
            if (++pos == buf.size) pos = 0
            return y
        }
    }

    private class AllPass(size: Int, val g: Float) {
        val buf = FloatArray(size); var pos = 0
        fun process(x: Float): Float {
            val y = buf[pos]
            val v = x + y * g
            buf[pos] = v
            if (++pos == buf.size) pos = 0
            return y - v * g
        }
    }

    private fun ms(m: Double) = (sampleRate * m / 1000.0).toInt().coerceAtLeast(1)
    private val combs = arrayOf(Comb(ms(29.7), 0.78f), Comb(ms(37.1), 0.76f), Comb(ms(41.1), 0.74f), Comb(ms(43.7), 0.72f))
    private val allPasses = arrayOf(AllPass(ms(5.0), 0.5f), AllPass(ms(1.7), 0.5f))

    /** Adds the wet signal to [out] in place. */
    fun process(out: FloatArray, frames: Int) {
        if (!enabled) return
        val wet = mix
        for (i in 0 until frames) {
            val x = out[i]
            var s = 0f
            for (c in combs) s += c.process(x)
            s *= 0.25f
            for (a in allPasses) s = a.process(s)
            out[i] = x + s * wet
        }
    }

    fun clear() {
        for (c in combs) { c.buf.fill(0f); c.filtered = 0f }
        for (a in allPasses) a.buf.fill(0f)
    }
}
