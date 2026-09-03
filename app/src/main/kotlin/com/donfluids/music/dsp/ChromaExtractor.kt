package com.donfluids.music.dsp

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a block of audio into a 12-bin pitch-class profile ("chroma") and accumulates it over time.
 *
 * Instead of mapping every FFT bin to a pitch class (which smears neighbouring semitones in the
 * bass), only spectral peaks are used. Each peak's frequency is refined by parabolic interpolation
 * so it lands on the right semitone even where FFT bins are coarser than a semitone.
 *
 * The same peaks also give a tuning estimate: how many cents the singers are sharp or flat of
 * A4 = [referenceHz]. That matters for an impromptu song, where the congregation often lands
 * somewhere between two keys.
 */
class ChromaExtractor(
    private val sampleRate: Int,
    val fftSize: Int = 8192,
    private val referenceHz: Double = 440.0,
    /** Time constant (seconds) of the exponential memory used for key estimation. */
    private val memorySeconds: Double = 8.0,
    private val minHz: Double = 55.0,
    private val maxHz: Double = 3000.0,
    /** Peaks above this frequency are weighted down in proportion, to tame high overtones. */
    private val rollOffHz: Double = 1000.0,
) {
    private val fft = Fft(fftSize)
    private val window = Fft.hannWindow(fftSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val mag = FloatArray(fftSize / 2 + 1)
    private val binHz = sampleRate.toDouble() / fftSize
    private val minBin = (minHz / binHz).toInt().coerceAtLeast(2)
    private val maxBin = (maxHz / binHz).toInt().coerceAtMost(fftSize / 2 - 2)

    /** Chroma of the most recent frame only. */
    val frameChroma = FloatArray(12)

    /** Exponentially-decayed chroma used for key finding. */
    val accumulatedChroma = FloatArray(12)

    /** Weighted circular mean of tuning deviation, accumulated as a 2-D vector. */
    private var tuningX = 0.0
    private var tuningY = 0.0

    /** Sum of frame energy seen so far (decayed). Used to judge whether we have heard anything. */
    var accumulatedWeight = 0.0
        private set

    private var frameCount = 0

    fun reset() {
        accumulatedChroma.fill(0f)
        frameChroma.fill(0f)
        tuningX = 0.0
        tuningY = 0.0
        accumulatedWeight = 0.0
        frameCount = 0
    }

    /**
     * Estimated tuning offset of the music relative to [referenceHz], in cents (-50..50).
     * Positive means sharp. Returns 0 until enough sound has been heard.
     */
    fun tuningOffsetCents(): Double {
        val r = sqrt(tuningX * tuningX + tuningY * tuningY)
        if (r < 1e-9) return 0.0
        return atan2(tuningY, tuningX) / (2.0 * Math.PI) * 100.0
    }

    /** How consistent the tuning estimate is, 0..1. */
    fun tuningConfidence(): Double {
        if (accumulatedWeight < 1e-9) return 0.0
        return (sqrt(tuningX * tuningX + tuningY * tuningY) / accumulatedWeight).coerceIn(0.0, 1.0)
    }

    /**
     * Analyse [fftSize] samples starting at [offset] in [buffer].
     * [hopSeconds] is the time since the previous call, used for the exponential decay.
     */
    fun process(buffer: FloatArray, offset: Int, hopSeconds: Double) {
        for (i in 0 until fftSize) {
            re[i] = buffer[offset + i] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        val scale = 2f / fftSize
        for (k in 0..fftSize / 2) {
            mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
        }

        frameChroma.fill(0f)
        val threshold = noiseFloor() * 4f
        var frameWeight = 0.0
        var fx = 0.0
        var fy = 0.0

        for (k in minBin..maxBin) {
            val m = mag[k]
            if (m <= threshold || m <= mag[k - 1] || m < mag[k + 1]) continue
            // Parabolic interpolation of the peak position in log-magnitude domain.
            val a = ln(mag[k - 1] + 1e-12f)
            val b = ln(m + 1e-12f)
            val c = ln(mag[k + 1] + 1e-12f)
            val denom = a - 2f * b + c
            val delta = if (denom.isFinite() && denom != 0f) (0.5f * (a - c) / denom).coerceIn(-0.5f, 0.5f) else 0f
            val hz = (k + delta) * binHz
            val semitone = 12.0 * log2(hz / referenceHz) // 0 = A4
            val nearest = semitone.roundToInt()
            val cents = (semitone - nearest) * 100.0
            val pc = Math.floorMod(nearest + 9, 12) // A -> 9, so C -> 0

            // De-emphasise the upper spectrum: high harmonics (5th, 7th ...) blur the key.
            val weight = if (hz <= rollOffHz) m.toDouble() else m.toDouble() * (rollOffHz / hz)
            frameChroma[pc] += weight.toFloat()
            frameWeight += weight
            val angle = cents / 100.0 * 2.0 * Math.PI
            fx += weight * cos(angle)
            fy += weight * sin(angle)
        }

        val decay = exp(-hopSeconds / memorySeconds)
        for (i in 0 until 12) {
            accumulatedChroma[i] = (accumulatedChroma[i] * decay + frameChroma[i]).toFloat()
        }
        tuningX = tuningX * decay + fx
        tuningY = tuningY * decay + fy
        accumulatedWeight = accumulatedWeight * decay + frameWeight
        frameCount++
    }

    /** Median magnitude in the analysed band, a cheap noise-floor estimate. */
    private val sortScratch = FloatArray(fftSize / 2 + 1)
    private fun noiseFloor(): Float {
        val n = maxBin - minBin + 1
        System.arraycopy(mag, minBin, sortScratch, 0, n)
        java.util.Arrays.sort(sortScratch, 0, n)
        return sortScratch[n / 2]
    }
}
