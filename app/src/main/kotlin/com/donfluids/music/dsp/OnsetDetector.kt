package com.donfluids.music.dsp

import kotlin.math.ln1p
import kotlin.math.sqrt

/**
 * Spectral-flux onset strength: how much louder each frequency bin got since the last frame.
 * Produces one number per hop. Larger means "something just started" (a drum hit, a new
 * syllable, a chord change). The tempo and meter trackers work entirely from this signal.
 */
class OnsetDetector(
    sampleRate: Int,
    val fftSize: Int = 2048,
    minHz: Double = 30.0,
    maxHz: Double = 8000.0,
    /** Log-compression strength. Scale-free-ish thanks to downstream normalisation. */
    private val gamma: Float = 20f,
    /** Onsets below this frequency get [lowBandWeight] extra weight (kick, bass). 0 disables. */
    lowBandMaxHz: Double = 0.0,
    private val lowBandWeight: Float = 0f,
) {
    private val fft = Fft(fftSize)
    private val window = Fft.hannWindow(fftSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val current = FloatArray(fftSize / 2 + 1)
    private val previous = FloatArray(fftSize / 2 + 1)
    private val binHz = sampleRate.toDouble() / fftSize
    private val minBin = (minHz / binHz).toInt().coerceAtLeast(1)
    private val maxBin = (maxHz / binHz).toInt().coerceAtMost(fftSize / 2)
    private val lowBandMaxBin = if (lowBandMaxHz > 0) (lowBandMaxHz / binHz).toInt().coerceIn(minBin, maxBin) else minBin - 1
    private var hasPrevious = false

    fun reset() {
        previous.fill(0f)
        hasPrevious = false
    }

    /** Returns the onset strength for the [fftSize] samples at [offset] in [buffer]. */
    fun process(buffer: FloatArray, offset: Int): Float {
        for (i in 0 until fftSize) {
            re[i] = buffer[offset + i] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        val scale = 2f / fftSize
        var flux = 0f
        for (k in minBin..maxBin) {
            val m = ln1p(gamma * sqrt(re[k] * re[k] + im[k] * im[k]) * scale)
            current[k] = m
            val d = m - previous[k]
            if (d > 0f) flux += if (k <= lowBandMaxBin) d * (1f + lowBandWeight) else d
        }
        val result = if (hasPrevious) flux else 0f
        System.arraycopy(current, minBin, previous, minBin, maxBin - minBin + 1)
        hasPrevious = true
        return result
    }
}
