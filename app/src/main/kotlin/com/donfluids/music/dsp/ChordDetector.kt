package com.donfluids.music.dsp

import com.donfluids.music.music.ChordLabel
import kotlin.math.sqrt

/**
 * Recognises the major or minor triad in a stream of chroma frames.
 *
 * Each frame is averaged with its recent neighbours, compared by cosine similarity with the 24
 * triad templates, and a new chord is only announced once it has won a few frames in a row.
 * Frames that are quiet or do not resemble any triad are reported as no chord.
 */
class ChordDetector(
    /** Frames averaged before matching (about 0.37 s at the default 46 ms frame). */
    private val smoothingFrames: Int = 8,
    /** Consecutive winning frames before the reported chord changes. */
    private val holdFrames: Int = 4,
    /** Cosine similarity below which nothing is reported. A flat chroma scores 0.5 by chance. */
    private val minSimilarity: Float = 0.6f,
    /** Frame energy below which the frame is treated as silence. */
    private val minEnergy: Float = 1e-4f,
) {
    private val history = Array(smoothingFrames) { FloatArray(12) }
    private var historyIndex = 0
    private var historyCount = 0
    private val smoothed = FloatArray(12)

    private var candidate: ChordLabel? = null
    private var candidateFrames = 0

    /** The chord currently considered to be sounding, or null. */
    var current: ChordLabel? = null
        private set

    /** Similarity of the current chord, 0..1. */
    var currentScore: Float = 0f
        private set

    fun reset() {
        for (h in history) h.fill(0f)
        historyIndex = 0
        historyCount = 0
        candidate = null
        candidateFrames = 0
        current = null
        currentScore = 0f
    }

    /** Feeds one chroma frame and returns the (possibly unchanged) current chord. */
    fun process(frameChroma: FloatArray): ChordLabel? {
        System.arraycopy(frameChroma, 0, history[historyIndex], 0, 12)
        historyIndex = (historyIndex + 1) % smoothingFrames
        if (historyCount < smoothingFrames) historyCount++

        smoothed.fill(0f)
        for (i in 0 until historyCount) for (pc in 0 until 12) smoothed[pc] += history[i][pc]
        var energy = 0f
        for (v in smoothed) energy += v
        energy /= historyCount

        val best = if (energy < minEnergy) null else match(smoothed)
        val bestChord = best?.first
        if (bestChord == candidate) candidateFrames++ else { candidate = bestChord; candidateFrames = 1 }
        if (candidateFrames >= holdFrames && candidate != current) {
            current = candidate
        }
        currentScore = if (current != null && best != null && best.first == current) best.second else 0f
        return current
    }

    /** Best triad for a chroma vector, or null when nothing fits well enough. */
    fun match(chroma: FloatArray): Pair<ChordLabel, Float>? {
        var norm = 0f
        for (v in chroma) norm += v * v
        if (norm <= 0f) return null
        norm = sqrt(norm)
        var best: ChordLabel? = null
        var bestScore = 0f
        for (root in 0 until 12) {
            for (minor in booleanArrayOf(false, true)) {
                val third = Math.floorMod(root + if (minor) 3 else 4, 12)
                val fifth = Math.floorMod(root + 7, 12)
                val dot = chroma[root] * ROOT_W + chroma[third] * THIRD_W + chroma[fifth] * FIFTH_W
                val score = dot / (norm * TEMPLATE_NORM)
                if (score > bestScore) { bestScore = score; best = ChordLabel(root, minor) }
            }
        }
        val chord = best ?: return null
        return if (bestScore >= minSimilarity) chord to bestScore else null
    }

    private companion object {
        const val ROOT_W = 1.0f
        const val THIRD_W = 0.8f
        const val FIFTH_W = 0.9f
        val TEMPLATE_NORM = sqrt(ROOT_W * ROOT_W + THIRD_W * THIRD_W + FIFTH_W * FIFTH_W)
    }
}
