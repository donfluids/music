package com.donfluids.music.dsp

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Guesses the time signature from how loud the onsets are on each beat.
 *
 * Given the beat period and phase from [TempoTracker], it samples the onset strength on a grid
 * of beats, folds that sequence into bars of 2–7 beats at every possible downbeat offset, and
 * correlates each folded bar with accent templates (strong 1, medium 3 for 4/4; strong 1 and 4
 * for 6/8; 2+2+3 and friends for 7/8).
 *
 * Uneven meters cannot be a single tracked pulse: in 7/8 the tracker settles on a two-eighth
 * pulse, or on the whole bar. So the odd templates (5 and 7) are also tried on finer grids:
 * the half-pulse, and the pulse divided by 5 or 7. The grid that wins decides the pulse the
 * screen counts in ([Result.pulseDivisor]).
 *
 * A separate check of energy halfway versus a third of the way between beats tells simple
 * from compound meter (6/8 felt in two, 12/8 felt in four).
 */
class MeterDetector(
    /** Beats needed before an answer is given. */
    private val minBeats: Int = 8,
    /** Ignore grids finer than this many frames (about 90 ms at the default hop). */
    private val minGridFrames: Double = 7.5,
) {
    class Result(
        /** Beats per bar at the counted pulse, e.g. 4 for 4/4, 2 for 6/8 felt in two, 7 for 7/8. */
        val beatsPerBar: Int,
        /** The counted pulse is the tracked beat period divided by this (1, 2, 5 or 7). */
        val pulseDivisor: Int,
        /** True when each beat divides in three (6/8, 9/8, 12/8 felt at the dotted quarter). */
        val compound: Boolean,
        /** Position of the most recent tracked beat within the bar, 0 = downbeat. */
        val lastBeatIndexInBar: Int,
        /** Display label such as "4/4" or "6/8". */
        val label: String,
        val confidence: Double,
        /** Best score for each candidate bar length, for the diagnostics display. */
        val scores: Map<Int, Double>,
        /** Bar position of the most recent tracked beat for each (beatsPerBar, pulseDivisor). */
        val lastBeatIndexByGrid: Map<Pair<Int, Int>, Int>,
    )

    private class Template(val beats: Int, val accents: DoubleArray, val prior: Double)

    private val templates = listOf(
        Template(2, doubleArrayOf(1.0, 0.2), 0.90),
        Template(3, doubleArrayOf(1.0, 0.3, 0.3), 1.00),
        Template(4, doubleArrayOf(1.0, 0.2, 0.6, 0.2), 1.05),
        Template(5, doubleArrayOf(1.0, 0.2, 0.6, 0.2, 0.2), 0.85), // 2+3
        Template(5, doubleArrayOf(1.0, 0.2, 0.2, 0.6, 0.2), 0.85), // 3+2
        Template(6, doubleArrayOf(1.0, 0.2, 0.2, 0.7, 0.2, 0.2), 0.95),
        Template(7, doubleArrayOf(1.0, 0.2, 0.6, 0.2, 0.6, 0.2, 0.2), 0.85), // 2+2+3
        Template(7, doubleArrayOf(1.0, 0.2, 0.2, 0.6, 0.2, 0.6, 0.2), 0.85), // 3+2+2
        Template(7, doubleArrayOf(1.0, 0.2, 0.6, 0.2, 0.2, 0.6, 0.2), 0.85), // 2+3+2
    ).map { Template(it.beats, zScore(it.accents), it.prior) }

    /** Grids to try: divisor of the tracked period, prior, and which bar lengths apply. */
    private class Grid(val divisor: Int, val prior: Double, val beats: Set<Int>)

    private val grids = listOf(
        Grid(1, 1.00, setOf(2, 3, 4, 5, 6, 7)),
        Grid(2, 0.90, setOf(5, 7)),
        Grid(5, 0.80, setOf(5)),
        Grid(7, 0.80, setOf(7)),
    )

    /**
     * @param onset detrended onset strength, oldest first, valid for [length] entries
     * @param periodFrames tracked beat period in frames
     * @param lastBeatFramesAgo frames between the newest entry and the last tracked beat
     */
    fun analyze(onset: FloatArray, length: Int, periodFrames: Double, lastBeatFramesAgo: Double): Result? {
        if (periodFrames < 4.0 || length < periodFrames * minBeats) return null
        val newestBeat = (length - 1) - lastBeatFramesAgo
        // Decide simple vs compound first: it changes which bar lengths are common.
        // In compound time a bar of two (6/8) is the usual case, not four (12/8).
        val compoundPulse = isCompound(onset, length, newestBeat, periodFrames)

        var bestScore = Double.NEGATIVE_INFINITY
        var bestBeats = 0
        var bestDivisor = 1
        var bestFit = 0.0
        val scores = HashMap<Int, Double>()
        val indexByGrid = HashMap<Pair<Int, Int>, Int>()

        for (grid in grids) {
            val step = periodFrames / grid.divisor
            if (step < minGridFrames) continue
            val strengths = gridStrengths(onset, length, newestBeat, step) ?: continue
            val z = zScore(strengths)
            val count = strengths.size
            for (template in templates) {
                val m = template.beats
                if (m !in grid.beats || count < 2 * m) continue
                val periodicity = max(0.0, autocorrelation(z, m))
                val prior = if (compoundPulse && grid.divisor == 1 && m <= 4) COMPOUND_PRIORS.getValue(m) else template.prior
                var gridBest = Double.NEGATIVE_INFINITY
                var gridOffset = 0
                for (offset in 0 until m) {
                    val folded = DoubleArray(m)
                    val counts = IntArray(m)
                    for (i in z.indices) {
                        val pos = Math.floorMod(i - offset, m)
                        folded[pos] += z[i]
                        counts[pos]++
                    }
                    for (j in 0 until m) folded[j] /= counts[j]
                    val fit = dot(zScore(folded), template.accents) / m
                    val score = grid.prior * prior * (0.65 * fit + 0.35 * periodicity)
                    if (score > gridBest) { gridBest = score; gridOffset = offset }
                    if (score > bestScore) {
                        bestScore = score
                        bestBeats = m
                        bestDivisor = grid.divisor
                        bestFit = fit
                    }
                }
                val key = m to grid.divisor
                val previous = indexByGrid[key]
                if (previous == null || gridBest > (scores[m] ?: Double.NEGATIVE_INFINITY)) {
                    indexByGrid[key] = Math.floorMod(count - 1 - gridOffset, m)
                }
                scores[m] = max(scores[m] ?: Double.NEGATIVE_INFINITY, gridBest)
            }
        }
        if (bestBeats == 0) return null

        val compound = bestDivisor == 1 && bestBeats <= 4 && compoundPulse
        val runnerUp = scores.filterKeys { it != bestBeats }.values.maxOrNull() ?: 0.0
        val margin = if (bestScore > 0) ((bestScore - runnerUp) / bestScore).coerceIn(0.0, 1.0) else 0.0
        val confidence = (0.5 * bestFit.coerceIn(0.0, 1.0) + 0.5 * margin).coerceIn(0.0, 1.0)

        return Result(
            beatsPerBar = bestBeats,
            pulseDivisor = bestDivisor,
            compound = compound,
            lastBeatIndexInBar = indexByGrid[bestBeats to bestDivisor] ?: 0,
            label = label(bestBeats, compound),
            confidence = confidence,
            scores = scores,
            lastBeatIndexByGrid = indexByGrid,
        )
    }

    /** Onset strength sampled on a grid ending at [newest] with spacing [step], oldest first. */
    private fun gridStrengths(onset: FloatArray, length: Int, newest: Double, step: Double): DoubleArray? {
        val count = ((newest - step / 2.0) / step).toInt() + 1
        if (count < minBeats) return null
        val window = step / 8.0
        return DoubleArray(count) { i -> windowMax(onset, length, newest - (count - 1 - i) * step, window) }
    }

    /** Is the space between tracked beats split in three rather than two? */
    private fun isCompound(onset: FloatArray, length: Int, newest: Double, period: Double): Boolean {
        val sub = period / 20.0
        var half = 0.0
        var third = 0.0
        var beat = 0.0
        var t = newest
        while (t - period >= 0) {
            val start = t - period
            beat += windowMax(onset, length, start, period / 8.0)
            half += windowMax(onset, length, start + period / 2.0, sub)
            third += 0.5 * (windowMax(onset, length, start + period / 3.0, sub) +
                windowMax(onset, length, start + 2.0 * period / 3.0, sub))
            t -= period
        }
        return third > 1.3 * half && third > 0.25 * beat
    }

    private companion object {
        val COMPOUND_PRIORS = mapOf(2 to 1.00, 3 to 0.95, 4 to 0.95)
    }

    private fun label(beats: Int, compound: Boolean): String = when {
        compound && beats == 2 -> "6/8"
        compound && beats == 3 -> "9/8"
        compound && beats == 4 -> "12/8"
        beats == 2 -> "2/4"
        beats == 3 -> "3/4"
        beats == 4 -> "4/4"
        beats == 5 -> "5/4"
        beats == 6 -> "6/8"
        beats == 7 -> "7/8"
        else -> "$beats/4"
    }

    private fun windowMax(x: FloatArray, length: Int, center: Double, halfWidth: Double): Double {
        val lo = (center - halfWidth).roundToInt().coerceAtLeast(0)
        val hi = (center + halfWidth).roundToInt().coerceAtMost(length - 1)
        if (lo > hi) return 0.0
        var m = Float.NEGATIVE_INFINITY
        for (i in lo..hi) if (x[i] > m) m = x[i]
        return max(0f, m).toDouble()
    }

    private fun autocorrelation(z: DoubleArray, lag: Int): Double {
        var num = 0.0
        var den = 0.0
        for (i in z.indices) den += z[i] * z[i]
        if (den < 1e-12) return 0.0
        for (i in lag until z.size) num += z[i] * z[i - lag]
        return num / den * z.size / (z.size - lag)
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun zScore(x: DoubleArray): DoubleArray {
        val mean = x.average()
        var v = 0.0
        for (e in x) v += (e - mean) * (e - mean)
        val sd = sqrt(v / x.size)
        return if (sd < 1e-9) DoubleArray(x.size) else DoubleArray(x.size) { (x[it] - mean) / sd }
    }
}
