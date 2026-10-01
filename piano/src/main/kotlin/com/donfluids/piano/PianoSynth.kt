package com.donfluids.piano

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * A small polyphonic piano-like synthesiser: each voice is a handful of harmonics whose upper
 * partials fade faster than the fundamental, as on a struck string, with a short attack and a
 * release when the key is lifted. Pure Kotlin, no Android types, so it is unit-testable.
 *
 * Thread safety: [noteOn], [noteOff] and [sustain] may be called from any thread; [render]
 * is meant for one audio thread. Voice state is guarded by a lock held only briefly.
 */
class PianoSynth(val sampleRate: Int, private val maxVoices: Int = 16) {

    /** The instrument every new note uses. */
    @Volatile var tone: Tone = Tone.PIANO

    /** Output level, 0..1. */
    @Volatile var volume: Float = 0.8f

    private class Voice {
        var midi = -1
        var active = false
        var phase = 0.0
        var phaseStep = 0.0
        val harmonicAmp = DoubleArray(HARMONICS)
        val harmonicDecay = DoubleArray(HARMONICS)
        var envelope = 0.0
        var attackStep = 0.0
        var releasing = false
        var releaseFactor = 1.0
        var held = false
        var startedAt = 0L
    }

    private val voices = Array(maxVoices) { Voice() }
    private val lock = Any()
    private var sequence = 0L

    /** While true, lifted keys keep ringing until sustain is released. */
    @Volatile var sustain: Boolean = false
        set(value) {
            field = value
            if (!value) synchronized(lock) { for (v in voices) if (v.active && !v.held) release(v) }
        }

    /** Number of voices currently sounding. */
    val activeVoices: Int get() = synchronized(lock) { voices.count { it.active } }

    fun noteOn(midi: Int, velocity: Float = 1f) {
        val vel = velocity.coerceIn(0.05f, 1f).toDouble()
        synchronized(lock) {
            // Retrigger a held note on the same voice; otherwise take a free voice, or the oldest.
            val same = voices.firstOrNull { it.active && it.midi == midi }
            val v = same ?: voices.firstOrNull { !it.active } ?: voices.minByOrNull { it.startedAt }!!
            val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val t = tone
            v.midi = midi
            v.active = true
            v.held = true
            v.releasing = false
            // Retriggering a ringing key keeps its phase and current level, so there is no click;
            // a stolen or free voice starts from silence.
            if (same == null) { v.phase = 0.0; v.envelope = 0.0 }
            v.phaseStep = 2.0 * PI * freq / sampleRate
            // Lower notes ring longer; each higher harmonic dies off sooner than the one below.
            // An organ does not decay at all while the key is held.
            val baseTau = 3.0 * (220.0 / freq).pow(0.4).coerceIn(0.4, 6.0)
            for (k in 0 until HARMONICS) {
                val n = k + 1
                v.harmonicAmp[k] = t.levels[k] * vel * (if (n * freq > sampleRate / 2.5) 0.0 else 1.0)
                v.harmonicDecay[k] = if (t.decays) exp(-1.0 / (sampleRate * baseTau / (1.0 + 0.7 * k))) else 1.0
            }
            v.attackStep = 1.0 / (sampleRate * t.attackSeconds)
            v.releaseFactor = exp(-1.0 / (sampleRate * t.releaseSeconds))
            v.startedAt = sequence++
        }
    }

    fun noteOff(midi: Int) {
        synchronized(lock) {
            for (v in voices) if (v.active && v.midi == midi && v.held) {
                v.held = false
                if (!sustain) release(v)
            }
        }
    }

    private fun release(v: Voice) { v.releasing = true }

    fun allNotesOff() {
        synchronized(lock) { for (v in voices) { v.held = false; if (v.active) release(v) } }
    }

    /** Renders [frames] mono samples into [out], replacing its contents. */
    fun render(out: FloatArray, frames: Int) {
        java.util.Arrays.fill(out, 0, frames, 0f)
        synchronized(lock) {
            for (v in voices) {
                if (!v.active) continue
                for (i in 0 until frames) {
                    if (v.envelope < 1.0 && !v.releasing) v.envelope = minOf(1.0, v.envelope + v.attackStep)
                    else if (v.releasing) v.envelope *= v.releaseFactor
                    var sample = 0.0
                    for (k in 0 until HARMONICS) {
                        val a = v.harmonicAmp[k]
                        if (a <= 1e-6) continue
                        sample += a * sine(v.phase * (k + 1))
                        v.harmonicAmp[k] = a * v.harmonicDecay[k]
                    }
                    v.phase += v.phaseStep
                    if (v.phase >= 2.0 * PI) v.phase -= 2.0 * PI
                    out[i] += (sample * v.envelope).toFloat()
                }
                if ((v.releasing && v.envelope < 2e-3) || v.harmonicAmp[0] < 1e-4) v.active = false
            }
        }
        // Gentle limiter so many keys at once do not clip harshly.
        val gain = volume
        for (i in 0 until frames) out[i] = tanh(out[i] * MASTER).toFloat() * gain
    }

    /** Instruments: harmonic levels, whether partials decay while held, and envelope times. */
    enum class Tone(val label: String, val levels: DoubleArray, val decays: Boolean, val attackSeconds: Double, val releaseSeconds: Double) {
        PIANO("Piano", doubleArrayOf(1.0, 0.55, 0.3, 0.2, 0.12, 0.08), true, 0.003, 0.12),
        ELECTRIC("E-piano", doubleArrayOf(1.0, 0.25, 0.08, 0.3, 0.05, 0.02), true, 0.004, 0.2),
        ORGAN("Organ", doubleArrayOf(0.8, 0.6, 0.45, 0.35, 0.0, 0.3), false, 0.008, 0.05),
    }

    companion object {
        const val HARMONICS = 6
        // Chosen so a four-note chord at full velocity stays in the limiter's linear region and
        // ten notes saturate gently rather than hard.
        private const val MASTER = 0.15f

        private const val TABLE_SIZE = 4096
        private val SINE = FloatArray(TABLE_SIZE + 1) { sin(2.0 * PI * it / TABLE_SIZE).toFloat() }
        private fun sine(phase: Double): Double {
            val p = phase / (2.0 * PI)
            val x = (p - Math.floor(p)) * TABLE_SIZE
            val i = x.toInt()
            val f = x - i
            return SINE[i] + (SINE[i + 1] - SINE[i]) * f
        }
    }
}
