package com.donfluids.piano

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The last stage before the speaker, for the whole mix (keys, band, reverb, metronome):
 *
 * 1. Gain, so ordinary playing uses the loudness the phone has to offer.
 * 2. Speaker tuning, only while the phone's own speaker is playing. A phone speaker cannot
 *    produce much below about 150 Hz, and power spent there only causes distortion, so that
 *    range is cut. The kick and the bass are kept audible by adding their upper harmonics,
 *    from which the ear fills in the missing low note. A lift around 2.8 kHz, where hearing is
 *    most sensitive, makes the same power sound louder.
 * 3. A slow compressor that follows the overall level over tenths of a second and evens out
 *    loud passages (everything at full volume with the band), without reacting to single drum
 *    hits, so the limiter after it rarely has much to do and the mix does not pump.
 * 4. A look-ahead limiter that keeps peaks just under full scale, so nothing ever clips: it sees
 *    each peak 1.5 ms before it plays and turns the gain down smoothly in time, then lets it back
 *    up over about 80 ms.
 */
class MasterBus(private val sampleRate: Int) {
    /** Make-up gain in decibels before the limiter. */
    @Volatile var gainDb: Float = 9f
    /** True while the phone's own speaker is the output; headphones and external speakers get a flat sound. */
    @Volatile var speakerTuning: Boolean = true

    /** Highest output level, a little under full scale so conversion never clips. */
    private val ceiling = 0.89f // -1 dBFS

    // Speaker tuning.
    private val highPass = Biquad.highPass(sampleRate, 140.0, 0.707)
    private val presence = Biquad.peaking(sampleRate, 2800.0, 0.9, 4.0)
    private val lowSplit = Biquad.lowPass(sampleRate, 140.0, 0.707)
    private val harmonicHigh = Biquad.highPass(sampleRate, 140.0, 0.707)
    private val harmonicLow = Biquad.lowPass(sampleRate, 600.0, 0.707)
    private var lowEnvelope = 0.0
    private val lowAttack = 1 - exp(-1.0 / (sampleRate * 0.005))
    private val lowRelease = 1 - exp(-1.0 / (sampleRate * 0.05))

    // Compressor: RMS over about 50 ms, 3:1 above -14 dBFS, gain moving over 30 ms down and 300 ms up.
    private var meanSquare = 0.0
    private val rmsCoef = 1 - exp(-1.0 / (sampleRate * 0.05))
    private var compDb = 0.0
    private val compAttack = 1 - exp(-1.0 / (sampleRate * 0.03))
    private val compRelease = 1 - exp(-1.0 / (sampleRate * 0.3))
    private val thresholdDb = -14.0
    private val ratio = 3.0

    /** Compressor gain reduction at the end of the last block, in decibels. */
    @Volatile var compressionDb: Float = 0f
        private set

    // Limiter.
    private val lookAhead = (sampleRate * 0.0015).toInt().coerceAtLeast(1)
    private val delay = FloatArray(lookAhead)
    private var delayPos = 0
    private val window = FloatArray(lookAhead)
    private var envelope = 0.0
    // Reaches its target well within the look-ahead, so the gain is down before the peak plays.
    private val attack = 1 - exp(-8.0 / lookAhead)
    private val release = 1 - exp(-1.0 / (sampleRate * 0.08))

    /** Gain reduction at the end of the last block, in decibels (0 when the limiter is resting). */
    @Volatile var reductionDb: Float = 0f
        private set

    fun process(buf: FloatArray, frames: Int) {
        val g = 10.0.pow(gainDb / 20.0)
        val tune = speakerTuning
        for (i in 0 until frames) {
            var x = buf[i] * g
            if (tune) {
                val low = lowSplit.next(x)
                lowEnvelope += (abs(low) - lowEnvelope) * (if (abs(low) > lowEnvelope) lowAttack else lowRelease)
                // Harmonics of the low band, kept proportional to its level so quiet bass stays quiet.
                val env = lowEnvelope + 1e-5
                val u = low / env
                val harmonics = env * (0.6 * tanh(3.0 * u) + 0.4 * abs(u))
                val added = harmonicLow.next(harmonicHigh.next(harmonics))
                x = presence.next(highPass.next(x)) + 0.7 * added
            }
            meanSquare += (x * x - meanSquare) * rmsCoef
            val levelDb = 10 * kotlin.math.log10(meanSquare + 1e-12)
            val wantDb = if (levelDb > thresholdDb) (levelDb - thresholdDb) * (1 - 1 / ratio) else 0.0
            compDb += (wantDb - compDb) * (if (wantDb > compDb) compAttack else compRelease)
            x *= 10.0.pow(-compDb / 20.0)
            // Look-ahead: the sample that plays now left the input lookAhead samples ago. The window
            // holds every sample from that one to the newest, so no peak plays before it is seen.
            val delayed = delay[delayPos]
            delay[delayPos] = x.toFloat()
            window[delayPos] = abs(x).toFloat()
            delayPos = (delayPos + 1) % lookAhead
            var peak = abs(delayed)
            for (v in window) if (v > peak) peak = v
            envelope += (peak - envelope) * (if (peak > envelope) attack else release)
            // The envelope is all but there by the time a peak plays; never less than the sample itself.
            val level = maxOf(envelope, abs(delayed).toDouble())
            val gain = if (level > ceiling) ceiling / level else 1.0
            // The gain already holds the sample at or under the ceiling; the clamp only catches rounding.
            buf[i] = (delayed * gain).toFloat().coerceIn(-ceiling, ceiling)
        }
        reductionDb = if (envelope > ceiling) (20 * kotlin.math.log10(envelope / ceiling)).toFloat() else 0f
        compressionDb = compDb.toFloat()
    }

    fun reset() {
        delay.fill(0f); window.fill(0f); envelope = 0.0; lowEnvelope = 0.0; meanSquare = 0.0; compDb = 0.0
        for (f in listOf(highPass, presence, lowSplit, harmonicHigh, harmonicLow)) f.reset()
    }
}

/** A second-order filter (RBJ audio-EQ cookbook), transposed direct form II. */
class Biquad private constructor(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun next(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun lowPass(rate: Int, hz: Double, q: Double): Biquad {
            val w = 2 * PI * hz / rate; val al = sin(w) / (2 * q); val c = cos(w)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
        }

        fun highPass(rate: Int, hz: Double, q: Double): Biquad {
            val w = 2 * PI * hz / rate; val al = sin(w) / (2 * q); val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
        }

        fun peaking(rate: Int, hz: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w = 2 * PI * hz / rate; val al = sin(w) / (2 * q); val c = cos(w)
            return make(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        }
    }
}
