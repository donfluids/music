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
 * Two spectra are used. A long window ([bassFftSize], about 370 ms) resolves the bass, where
 * neighbouring semitones are only a few hertz apart; a shorter one ([fftSize], about 190 ms)
 * follows chord changes above [bassCrossoverHz]. Only spectral peaks are used, with their
 * frequency refined by parabolic interpolation, so a note lands on one pitch class instead of
 * smearing across two bins.
 *
 * The same peaks give a tuning estimate: how many cents the singers are sharp or flat of
 * A4 = [referenceHz].
 */
class ChromaExtractor(
    private val sampleRate: Int,
    val fftSize: Int = 8192,
    val bassFftSize: Int = 16384,
    private val bassCrossoverHz: Double = 260.0,
    private val referenceHz: Double = 440.0,
    /** Time constant (seconds) of the exponential memory used for key estimation. */
    private val memorySeconds: Double = 8.0,
    private val minHz: Double = 55.0,
    private val maxHz: Double = 3000.0,
    /** Peaks above this frequency are weighted down in proportion, to tame high overtones. */
    private val rollOffHz: Double = 1000.0,
    /**
     * Follow the singers' own reference: once the tuning estimate is consistent and clearly
     * off the grid, move the grid onto it so notes fall into one pitch class instead of two.
     */
    private val adaptiveTuning: Boolean = true,
) {
    /** Cents by which the pitch-class grid has been moved from [referenceHz]. */
    var gridOffsetCents: Double = 0.0
        private set
    private var framesSinceGridMove = 0
    /** Samples the caller must provide before the analysis point. */
    val windowSamples: Int get() = maxOf(fftSize, bassFftSize)

    private inner class Spectrum(val size: Int) {
        val fft = Fft(size)
        val window = Fft.hannWindow(size)
        val re = FloatArray(size)
        val im = FloatArray(size)
        val mag = FloatArray(size / 2 + 1)
        val binHz = sampleRate.toDouble() / size
        private val sortScratch = FloatArray(size / 2 + 1)

        fun compute(buffer: FloatArray, end: Int) {
            val start = end - size
            for (i in 0 until size) { re[i] = buffer[start + i] * window[i]; im[i] = 0f }
            fft.transform(re, im)
            val scale = 2f / size
            for (k in 0..size / 2) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
        }

        /** Median magnitude between two bins: a cheap noise-floor estimate. */
        fun noiseFloor(minBin: Int, maxBin: Int): Float {
            val n = maxBin - minBin + 1
            if (n <= 0) return 0f
            System.arraycopy(mag, minBin, sortScratch, 0, n)
            java.util.Arrays.sort(sortScratch, 0, n)
            return sortScratch[n / 2]
        }
    }

    private val main = Spectrum(fftSize)
    private val bass = Spectrum(bassFftSize)

    /** Chroma of the most recent frame only. */
    val frameChroma = FloatArray(12)

    /** Exponentially-decayed chroma used for key finding. */
    val accumulatedChroma = FloatArray(12)

    private var tuningX = 0.0
    private var tuningY = 0.0
    private var frameX = 0.0
    private var frameY = 0.0
    private var frameWeight = 0.0

    /** Sum of frame energy seen so far (decayed). Used to judge whether we have heard anything. */
    var accumulatedWeight = 0.0
        private set

    fun reset() {
        accumulatedChroma.fill(0f)
        frameChroma.fill(0f)
        tuningX = 0.0
        tuningY = 0.0
        accumulatedWeight = 0.0
        gridOffsetCents = 0.0
        framesSinceGridMove = 0
    }

    /** Tuning offset of the music relative to the current grid, in cents (-50..50). */
    private fun offsetFromGridCents(): Double {
        val r = sqrt(tuningX * tuningX + tuningY * tuningY)
        if (r < 1e-9) return 0.0
        return atan2(tuningY, tuningX) / (2.0 * Math.PI) * 100.0
    }

    /** Estimated tuning offset relative to [referenceHz], in cents (-50..50). Positive is sharp. */
    fun tuningOffsetCents(): Double {
        var c = gridOffsetCents + offsetFromGridCents()
        while (c > 50.0) c -= 100.0
        while (c < -50.0) c += 100.0
        return c
    }

    /** How consistent the tuning estimate is, 0..1. */
    fun tuningConfidence(): Double {
        if (accumulatedWeight < 1e-9) return 0.0
        return (sqrt(tuningX * tuningX + tuningY * tuningY) / accumulatedWeight).coerceIn(0.0, 1.0)
    }

    /**
     * Analyse the audio ending at index [end] (exclusive) in [buffer]; at least [windowSamples]
     * samples must precede it. [hopSeconds] is the time since the previous call.
     */
    fun process(buffer: FloatArray, end: Int, hopSeconds: Double) {
        main.compute(buffer, end)
        bass.compute(buffer, end)

        frameChroma.fill(0f)
        frameWeight = 0.0
        frameX = 0.0
        frameY = 0.0
        collectPeaks(bass, minHz, bassCrossoverHz)
        collectPeaks(main, bassCrossoverHz, maxHz)

        val decay = exp(-hopSeconds / memorySeconds)
        for (i in 0 until 12) accumulatedChroma[i] = (accumulatedChroma[i] * decay + frameChroma[i]).toFloat()
        tuningX = tuningX * decay + frameX
        tuningY = tuningY * decay + frameY
        accumulatedWeight = accumulatedWeight * decay + frameWeight
        framesSinceGridMove++
        // Move the grid onto the singers when they are consistently more than 15 cents off it,
        // no more often than every two seconds, so the pitch classes settle rather than hunt.
        if (adaptiveTuning && framesSinceGridMove * hopSeconds > 2.0 && tuningConfidence() > 0.5) {
            val off = offsetFromGridCents()
            if (kotlin.math.abs(off) > 15.0) {
                gridOffsetCents += off
                while (gridOffsetCents > 50.0) gridOffsetCents -= 100.0
                while (gridOffsetCents < -50.0) gridOffsetCents += 100.0
                // The tuning vector was measured against the old grid; re-express it against the new one.
                val r = sqrt(tuningX * tuningX + tuningY * tuningY)
                tuningX = r; tuningY = 0.0
                framesSinceGridMove = 0
            }
        }
    }

    private fun collectPeaks(s: Spectrum, fromHz: Double, toHz: Double) {
        val minBin = (fromHz / s.binHz).toInt().coerceAtLeast(2)
        val maxBin = (toHz / s.binHz).toInt().coerceAtMost(s.size / 2 - 2)
        if (maxBin <= minBin) return
        val threshold = s.noiseFloor(minBin, maxBin) * 4f
        val mag = s.mag
        for (k in minBin..maxBin) {
            val m = mag[k]
            if (m <= threshold || m <= mag[k - 1] || m < mag[k + 1]) continue
            val a = ln(mag[k - 1] + 1e-12f)
            val b = ln(m + 1e-12f)
            val c = ln(mag[k + 1] + 1e-12f)
            val denom = a - 2f * b + c
            val delta = if (denom.isFinite() && denom != 0f) (0.5f * (a - c) / denom).coerceIn(-0.5f, 0.5f) else 0f
            val hz = (k + delta) * s.binHz
            if (hz < fromHz || hz >= toHz) continue
            val semitone = 12.0 * log2(hz / referenceHz) - gridOffsetCents / 100.0
            val nearest = semitone.roundToInt()
            val cents = (semitone - nearest) * 100.0
            val pc = Math.floorMod(nearest + 9, 12)
            val weight = if (hz <= rollOffHz) m.toDouble() else m.toDouble() * (rollOffHz / hz)
            frameChroma[pc] += weight.toFloat()
            frameWeight += weight
            val angle = cents / 100.0 * 2.0 * Math.PI
            frameX += weight * cos(angle)
            frameY += weight * sin(angle)
        }
    }
}
