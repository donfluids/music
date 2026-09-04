package com.donfluids.music.dsp

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Estimates tempo and beat phase from a stream of onset-strength values.
 *
 * Method (after Ellis, "Beat Tracking by Dynamic Programming", 2007, simplified):
 *  1. keep the last few seconds of onset strength,
 *  2. remove the slow trend so only the rhythmic part remains,
 *  3. autocorrelate over the plausible range of beat periods,
 *  4. weight by a log-normal tempo prior (people mostly clap between 60 and 160 BPM),
 *  5. pick the best period, then find the phase that lines beats up with strong onsets.
 */
class TempoTracker(
    /** Onset frames per second (sample rate / hop size). */
    val framesPerSecond: Double,
    historySeconds: Double = 8.0,
    minBpm: Double = 40.0,
    maxBpm: Double = 220.0,
    private val priorCenterBpm: Double = 90.0,
    private val priorWidthOctaves: Double = 0.6,
    private val smoothingEstimates: Int = 5,
) {
    class Estimate(
        val bpm: Double,
        /** Beat period in onset frames (fractional). */
        val periodFrames: Double,
        /** How many frames before the newest frame the last beat fell. */
        val lastBeatFramesAgo: Double,
        /** Normalised autocorrelation at the chosen period, roughly 0..1. */
        val strength: Double,
        /** True when the last few estimates agree closely. */
        val locked: Boolean,
    )

    private val capacity = (historySeconds * framesPerSecond).roundToInt()
    private val ring = FloatArray(capacity)
    private var head = 0
    private var count = 0

    private val minLag = max(2, floor(framesPerSecond * 60.0 / maxBpm).toInt())
    private val maxLag = ceil(framesPerSecond * 60.0 / minBpm).toInt().coerceAtMost(capacity / 2)
    /** Half-width of the moving average removed before autocorrelation; wide enough not to erase slow beats. */
    private val trendHalfWidth = (0.75 * framesPerSecond).roundToInt().coerceAtLeast(1)

    private val linear = FloatArray(capacity)
    /** Detrended onset history in time order (oldest first); valid up to [detrendedLength]. */
    val detrended = FloatArray(capacity)
    var detrendedLength = 0
        private set
    // The autocorrelation runs to twice the slowest lag so every candidate can be checked
    // against its double; otherwise slow tempos are structurally disadvantaged.
    private val acf = DoubleArray(2 * maxLag + 1)
    private val score = DoubleArray(maxLag + 1)
    private var lockedPeriod = 0.0
    private var octaveChallenges = 0
    /** Consecutive estimates an octave-related alternative must win before the tempo switches. */
    private val octaveSwitchEstimates = 4
    private val prior = DoubleArray(maxLag + 1) { lag ->
        if (lag == 0) 0.0 else {
            val bpm = framesPerSecond * 60.0 / lag
            val octaves = ln(bpm / priorCenterBpm) / ln(2.0)
            exp(-0.5 * (octaves / priorWidthOctaves) * (octaves / priorWidthOctaves))
        }
    }
    private val recentPeriods = ArrayDeque<Double>()

    /** Seconds of onset history currently held. */
    val secondsBuffered: Double get() = count / framesPerSecond

    fun reset() {
        head = 0
        count = 0
        detrendedLength = 0
        recentPeriods.clear()
        lockedPeriod = 0.0
        octaveChallenges = 0
    }

    fun push(onsetStrength: Float) {
        ring[head] = onsetStrength
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    /** Returns null until at least about three seconds of onsets have been heard. */
    fun estimate(): Estimate? {
        val n = count
        if (n < 3.0 * framesPerSecond || n <= 2 * minLag) return null

        // Unroll the ring into time order.
        val start = (head - n + capacity) % capacity
        for (i in 0 until n) linear[i] = ring[(start + i) % capacity]

        // Remove the slow trend (moving average) so the autocorrelation sees rhythm, not loudness.
        var sum = 0.0
        var lo = 0
        var hi = -1
        for (i in 0 until n) {
            val newHi = minOf(n - 1, i + trendHalfWidth)
            while (hi < newHi) { hi++; sum += linear[hi] }
            val newLo = maxOf(0, i - trendHalfWidth)
            while (lo < newLo) { sum -= linear[lo]; lo++ }
            detrended[i] = linear[i] - (sum / (hi - lo + 1)).toFloat()
        }
        detrendedLength = n

        // Autocorrelation over the candidate lags and their doubles.
        val usableMaxLag = minOf(maxLag, n / 2)
        val acfMaxLag = minOf(2 * maxLag, n / 2)
        var r0 = 0.0
        for (i in 0 until n) r0 += detrended[i].toDouble() * detrended[i]
        if (r0 < 1e-9) return null
        for (lag in 0..acfMaxLag) {
            var s = 0.0
            for (i in lag until n) s += detrended[i].toDouble() * detrended[i - lag]
            acf[lag] = s / (n - lag) / (r0 / n)
        }

        // Score = prior * acf(lag) * (1 + half the acf at twice the lag). A candidate has to be
        // periodic in its own right; a periodic double then strengthens it, as a real beat's
        // bar level does, but cannot rescue a lag that is not itself a pulse.
        for (lag in 0..usableMaxLag) {
            val own = acf[lag].coerceAtLeast(0.0)
            val double = if (2 * lag <= acfMaxLag) acf[2 * lag].coerceAtLeast(0.0) else 0.0
            score[lag] = prior[lag] * own * (1.0 + 0.5 * double)
        }
        // Candidate periods are the local maxima of the score. Re-rank the strongest few by
        // beat salience: how loud the onsets are when sampled on that period's best-phase grid.
        // This separates a 6/8 dotted-quarter pulse from a pair of its eighths, which have
        // similar autocorrelation but very different loudness on the grid.
        val peaks = ArrayList<Int>()
        for (lag in minLag..(usableMaxLag - 1)) {
            if (score[lag] >= score[lag - 1] && score[lag] > score[lag + 1] && score[lag] > 0) peaks += lag
        }
        if (peaks.isEmpty()) return null
        peaks.sortByDescending { score[it] }
        val shortlist = peaks.take(5)
        var maxSalience = 0.0
        val salience = DoubleArray(shortlist.size) { i ->
            val s = phaseAlignedMean(n, shortlist[i].toDouble())
            if (s > maxSalience) maxSalience = s
            s
        }
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for ((i, lag) in shortlist.withIndex()) {
            // Square root softens the salience so it breaks ties without overruling the prior.
            val s = score[lag] * (if (maxSalience > 0) kotlin.math.sqrt(salience[i] / maxSalience) else 1.0)
            if (s > bestScore) { bestScore = s; best = lag }
        }
        if (best < 0) return null

        // Octave hysteresis: once locked, a double, half, triple or third of the current
        // period has to keep winning for a while before the tempo is allowed to jump.
        if (lockedPeriod > 0.0) {
            val ratio = best / lockedPeriod
            val related = OCTAVE_RATIOS.any { kotlin.math.abs(ratio / it - 1.0) < 0.06 }
            val same = kotlin.math.abs(ratio - 1.0) < 0.06
            if (same) {
                octaveChallenges = 0
            } else if (related) {
                val familiar = shortlist.firstOrNull { kotlin.math.abs(it / lockedPeriod - 1.0) < 0.06 }
                if (familiar != null && ++octaveChallenges < octaveSwitchEstimates) {
                    best = familiar
                } else if (familiar == null) {
                    octaveChallenges = 0
                }
            }
        }

        // Parabolic interpolation of the peak for a fractional period.
        val a = score[best - 1]
        val b = score[best]
        val c = score[best + 1]
        val denom = a - 2 * b + c
        val delta = if (denom != 0.0) (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) else 0.0
        val rawPeriod = best + delta

        recentPeriods.addLast(rawPeriod)
        while (recentPeriods.size > smoothingEstimates) recentPeriods.removeFirst()
        val sorted = recentPeriods.sorted()
        val period = sorted[sorted.size / 2]
        val locked = recentPeriods.size >= smoothingEstimates && sorted.last() / sorted.first() < 1.04
        if (locked) { lockedPeriod = period; if (octaveChallenges >= octaveSwitchEstimates) octaveChallenges = 0 }

        val strength = acf[best].coerceIn(0.0, 1.0)
        val phase = bestPhase(n, period)
        return Estimate(
            bpm = framesPerSecond * 60.0 / period,
            periodFrames = period,
            lastBeatFramesAgo = phase,
            strength = strength,
            locked = locked,
        )
    }

    private companion object {
        val OCTAVE_RATIOS = doubleArrayOf(2.0, 0.5, 3.0, 1.0 / 3.0, 1.5, 2.0 / 3.0)
    }

    /**
     * Among all offsets within one period of the newest frame, find the one where beats spaced
     * [period] apart line up with the strongest (positive) onsets over the last eight beats.
     */
    private fun bestPhase(n: Int, period: Double): Double = phaseSearch(n, period).first

    /** Mean onset strength per beat on the best-phase grid for [period]. */
    private fun phaseAlignedMean(n: Int, period: Double): Double = phaseSearch(n, period).second

    private fun phaseSearch(n: Int, period: Double): Pair<Double, Double> {
        val maxPhase = ceil(period).toInt()
        val beats = minOf(8, floor((n - 1) / period).toInt())
        if (beats <= 0) return 0.0 to 0.0
        var bestPhase = 0
        var bestSum = Double.NEGATIVE_INFINITY
        var bestCount = 1
        for (phase in 0 until maxPhase) {
            var s = 0.0
            var used = 0
            for (k in 0 until beats) {
                val idx = (n - 1 - phase - k * period).roundToInt()
                if (idx < 0) break
                // Tolerate one frame of jitter on either side.
                var v = detrended[idx]
                if (idx > 0) v = max(v, detrended[idx - 1])
                if (idx < n - 1) v = max(v, detrended[idx + 1])
                s += max(0f, v)
                used++
            }
            if (s > bestSum) { bestSum = s; bestPhase = phase; bestCount = max(1, used) }
        }
        return bestPhase.toDouble() to bestSum / bestCount
    }
}
