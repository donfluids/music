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
 * pulse, or on the whole bar, and a slow 6/8 is often tracked at two eighths too. So the odd
 * templates (5 and 7) and the six-eighth bar are also tried on finer grids:
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
    /** Where the most recent tracked beat sits on a counted grid, and how to reach that grid. */
    class Position(
        /** Bar position (0 = downbeat) of the counted pulse at or just before the tracked beat. */
        val index: Int,
        /** Tracked periods to move the beat anchor back so it lands on the counted grid. */
        val anchorShift: Double,
    )

    class Result(
        /** Beats per bar at the counted pulse, e.g. 4 for 4/4, 2 for 6/8 felt in two, 7 for 7/8. */
        val beatsPerBar: Int,
        /** Counted pulse period as a multiple of the tracked beat period (0.5 for 7/8 tracked in twos, 1.5 for 6/8 tracked in twos). */
        val pulseRatio: Double,
        /** True when each beat divides in three (6/8, 9/8, 12/8 felt at the dotted quarter). */
        val compound: Boolean,
        /** Position of the most recent tracked beat on the counted grid. */
        val position: Position,
        /** Display label such as "4/4" or "6/8". */
        val label: String,
        val confidence: Double,
        /** Best score for each candidate bar length, for the diagnostics display. */
        val scores: Map<Int, Double>,
        /** The (bar length, grid divisor) hypothesis that won. */
        val grid: Pair<Int, Int>,
        /** Counted-grid position for every hypothesis, so a kept bar length can follow a new beat phase. */
        val positions: Map<Pair<Int, Int>, Position>,
    ) {
        val lastBeatIndexInBar: Int get() = position.index
    }

    private class Template(val beats: Int, val accents: DoubleArray, val prior: Double)

    private val templates = listOf(
        Template(2, doubleArrayOf(1.0, 0.2), 0.90),
        Template(3, doubleArrayOf(1.0, 0.3, 0.3), 1.00),
        Template(4, doubleArrayOf(1.0, 0.2, 0.6, 0.2), 1.05),
        Template(5, doubleArrayOf(1.0, 0.2, 0.6, 0.2, 0.2), 0.85), // 2+3
        Template(5, doubleArrayOf(1.0, 0.2, 0.2, 0.6, 0.2), 0.85), // 3+2
        Template(6, doubleArrayOf(1.0, 0.25, 0.25, 0.55, 0.25, 0.25), 0.95),
        Template(7, doubleArrayOf(1.0, 0.2, 0.6, 0.2, 0.6, 0.2, 0.2), 0.85), // 2+2+3
        Template(7, doubleArrayOf(1.0, 0.2, 0.2, 0.6, 0.2, 0.6, 0.2), 0.85), // 3+2+2
        Template(7, doubleArrayOf(1.0, 0.2, 0.6, 0.2, 0.2, 0.6, 0.2), 0.85), // 2+3+2
    ).map { Template(it.beats, zScore(it.accents), it.prior) }

    /** Grids to try: divisor of the tracked period, prior, and which bar lengths apply. */
    private class Grid(val divisor: Int, val prior: Double, val beats: Set<Int>)

    private val grids = listOf(
        Grid(1, 1.00, setOf(2, 3, 4, 5, 6, 7)),
        Grid(2, 1.05, setOf(5, 6, 7)), // 6: a slow 6/8 tracked at the two-eighth pulse
        Grid(5, 0.90, setOf(5)),
        Grid(7, 0.90, setOf(7)),
    )

    /**
     * A finer grid only deserves its prior when there is real onset energy between the tracked
     * pulses; on a song with nothing between beats it is discounted, which keeps 5/4 and 7/8
     * from appearing on ordinary music.
     */
    private fun gridPrior(grid: Grid, strengths: DoubleArray): Double {
        if (grid.divisor == 1) return grid.prior
        var on = 0.0; var onCount = 0
        var off = 0.0; var offCount = 0
        val count = strengths.size
        for (i in strengths.indices) {
            if ((count - 1 - i) % grid.divisor == 0) { on += strengths[i]; onCount++ } else { off += strengths[i]; offCount++ }
        }
        if (onCount == 0 || offCount == 0 || on <= 0.0) return 0.6
        val ratio = (off / offCount) / (on / onCount)
        return 0.6 + (grid.prior - 0.6) * (ratio / 0.35).coerceIn(0.0, 1.0)
    }

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
        // Best folded pattern and its fit per (bar length, grid), for the six-versus-three check.
        val foldedByGrid = HashMap<Pair<Int, Int>, DoubleArray>()
        val fitByGrid = HashMap<Pair<Int, Int>, Double>()
        val scoreByGrid = HashMap<Pair<Int, Int>, Double>()

        for (grid in grids) {
            val step = periodFrames / grid.divisor
            if (step < minGridFrames) continue
            val strengths = gridStrengths(onset, length, newestBeat, step) ?: continue
            val z = zScore(strengths)
            val count = strengths.size
            val gridPrior = gridPrior(grid, strengths)
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
                    val score = gridPrior * prior * (0.65 * fit + 0.35 * periodicity)
                    if (score > gridBest) {
                        gridBest = score; gridOffset = offset
                        foldedByGrid[m to grid.divisor] = folded; fitByGrid[m to grid.divisor] = fit
                    }
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
                scoreByGrid[key] = max(scoreByGrid[key] ?: Double.NEGATIVE_INFINITY, gridBest)
                scores[m] = max(scores[m] ?: Double.NEGATIVE_INFINITY, gridBest)
            }
        }
        if (bestBeats == 0) return null

        // Three versus six on the tracked pulse: two bars of three fold into a six whose fourth
        // pulse is as strong as its first, whereas a real 6/8 has a weaker secondary accent
        // there. When the six-fold also fits well, prefer it. The same accent structure at the
        // three level is invisible, because any two-level pattern matches the three template.
        if (bestBeats == 3 && bestDivisor == 1) {
            // The six may live on the tracked pulse (a slow 6/8 tracked at the eighth) or on the
            // half grid (a 6/8 tracked at two eighths, whose secondary accent falls between pulses).
            for (divisor in intArrayOf(1, 2)) {
                val six = foldedByGrid[6 to divisor] ?: continue
                val sixFit = fitByGrid[6 to divisor] ?: 0.0
                if (sixFit >= 0.9 && six[3] < 0.8 * six[0] && six[3] > six[1] && six[3] > six[5]) {
                    bestBeats = 6
                    bestDivisor = divisor
                    bestFit = sixFit
                    bestScore = scoreByGrid[6 to divisor] ?: bestScore
                    break
                }
            }
        }

        val compound = bestDivisor == 1 && bestBeats <= 4 && compoundPulse
        val runnerUp = scores.filterKeys { it != bestBeats }.values.maxOrNull() ?: 0.0
        val margin = if (bestScore > 0) ((bestScore - runnerUp) / bestScore).coerceIn(0.0, 1.0) else 0.0
        val confidence = (0.5 * bestFit.coerceIn(0.0, 1.0) + 0.5 * margin).coerceIn(0.0, 1.0)

        val positions = indexByGrid.mapValues { (grid, fineIndex) -> position(grid.first, grid.second, fineIndex) }
        val grid = bestBeats to bestDivisor
        val sixInTwo = bestBeats == 6 && bestDivisor <= 2
        return Result(
            beatsPerBar = if (sixInTwo) 2 else bestBeats,
            pulseRatio = pulseRatio(bestBeats, bestDivisor),
            compound = compound || sixInTwo,
            position = positions[grid] ?: Position(0, 0.0),
            label = if (sixInTwo) "6/8" else label(bestBeats, compound),
            confidence = confidence,
            scores = scores,
            grid = grid,
            positions = positions,
        )
    }

    /** Counted period over tracked period for a hypothesis; six eighths are always counted in two. */
    private fun pulseRatio(beats: Int, divisor: Int): Double = when {
        beats == 6 && divisor == 2 -> 1.5 // tracked at two eighths, counted at three
        beats == 6 && divisor == 1 -> 3.0 // tracked at the eighth, counted at three
        else -> 1.0 / divisor
    }

    /** Maps a fine-grid bar index to the counted grid, moving the anchor back onto it if needed. */
    private fun position(beats: Int, divisor: Int, fineIndex: Int): Position = when {
        beats == 6 && divisor <= 2 -> Position(index = fineIndex / 3, anchorShift = (fineIndex % 3).toDouble() / divisor)
        else -> Position(index = fineIndex, anchorShift = 0.0)
    }

    /** Onset strength sampled on a grid ending at [newest] with spacing [step], oldest first. */
    private fun gridStrengths(onset: FloatArray, length: Int, newest: Double, step: Double): DoubleArray? {
        val count = ((newest - step / 2.0) / step).toInt() + 1
        if (count < minBeats) return null
        val window = step / 8.0
        return DoubleArray(count) { i -> windowMax(onset, length, newest - (count - 1 - i) * step, window) }
    }

    /**
     * Is the space between tracked beats split in three rather than two? Both thirds must carry
     * energy: a swung eighth sits at two thirds alone and is duple, not compound.
     */
    private fun isCompound(onset: FloatArray, length: Int, newest: Double, period: Double): Boolean {
        val sub = period / 20.0
        var half = 0.0
        var firstThird = 0.0
        var secondThird = 0.0
        var beat = 0.0
        var t = newest
        while (t - period >= 0) {
            val start = t - period
            beat += windowMax(onset, length, start, period / 8.0)
            half += windowMax(onset, length, start + period / 2.0, sub)
            firstThird += windowMax(onset, length, start + period / 3.0, sub)
            secondThird += windowMax(onset, length, start + 2.0 * period / 3.0, sub)
            t -= period
        }
        val third = 0.5 * (firstThird + secondThird)
        val bothThirds = minOf(firstThird, secondThird) > 0.35 * maxOf(firstThird, secondThird)
        return bothThirds && third > 1.3 * half && third > 0.25 * beat
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
