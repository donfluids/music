package com.donfluids.music.dsp

import com.donfluids.music.music.ChordLabel
import com.donfluids.music.music.ChordType
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Chord templates: 12 roots × 7 types, compared with a chroma vector by cosine similarity.
 * Extensions carry a small penalty so a plain triad with a little overtone energy on the
 * seventh is not read as a seventh chord.
 */
class ChordTemplates {
    val chords: List<ChordLabel> = ChordType.entries.flatMap { t -> (0 until 12).map { ChordLabel(it, t) } }
    private val weights: Array<FloatArray> = Array(chords.size) { i ->
        val c = chords[i]
        FloatArray(12).also { w ->
            c.type.intervals.forEachIndexed { k, interval ->
                w[Math.floorMod(c.root + interval, 12)] = when (k) { 0 -> 1.0f; 1 -> 0.8f; 2 -> 0.9f; else -> 0.7f }
            }
        }
    }
    private val norms = FloatArray(chords.size) { i -> sqrt(weights[i].sumOf { (it * it).toDouble() }).toFloat() }

    /** Cosine similarity of [chroma] with every template, written into [out]. Returns the chroma norm. */
    fun similarities(chroma: FloatArray, out: FloatArray): Float {
        var norm = 0f
        for (v in chroma) norm += v * v
        norm = sqrt(norm)
        if (norm <= 0f) { out.fill(0f); return 0f }
        for (i in chords.indices) {
            val w = weights[i]
            var dot = 0f
            for (pc in 0 until 12) dot += chroma[pc] * w[pc]
            out[i] = dot / (norm * norms[i])
        }
        return norm
    }

    /** Best template for a chroma vector, or null when nothing fits well enough. */
    fun match(chroma: FloatArray, minSimilarity: Float = 0.6f): Pair<ChordLabel, Float>? {
        val sims = FloatArray(chords.size)
        if (similarities(chroma, sims) <= 0f) return null
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in chords.indices) {
            val s = sims[i] - penalty(chords[i].type)
            if (s > bestScore) { bestScore = s; best = i }
        }
        return if (best >= 0 && sims[best] >= minSimilarity) chords[best] to sims[best] else null
    }

    companion object {
        /** Similarity handicap for extended chords, in similarity units. */
        fun penalty(type: ChordType): Float = when (type) {
            ChordType.MAJOR, ChordType.MINOR -> 0f
            ChordType.SUS4, ChordType.SUS2 -> 0.05f
            else -> 0.04f
        }
    }
}

/**
 * Tracks chords over time with a hidden Markov model decoded by fixed-lag Viterbi.
 *
 * States are the 84 chords plus "no chord". Each observation (an averaged chroma vector) scores
 * every state by template similarity; the model prefers to stay on the current chord, with a
 * stickiness that depends on how much time the observation spans. The decision for an
 * observation is made [lag] observations later, once the following context is known, which
 * removes the one-off misreads a frame-by-frame detector produces.
 */
class ChordTracker(
    /** Observations of delay before a decision is made. */
    val lag: Int = 4,
    /** Sharpness of the emission model: log-probability per unit of similarity. */
    private val beta: Double = 12.0,
    /** Expected chord duration in seconds; sets the self-transition probability. */
    private val chordSeconds: Double = 1.5,
    /** Similarity credited to the no-chord state; a flat chroma scores about 0.5 by chance. */
    private val noChordSimilarity: Double = 0.58,
    /** Mean chroma energy below which the observation counts as silence. */
    private val minEnergy: Float = 1e-4f,
) {
    private val templates = ChordTemplates()
    private val stateCount = templates.chords.size + 1
    private val noChord = stateCount - 1
    private val sims = FloatArray(templates.chords.size)

    private var delta = DoubleArray(stateCount)
    private var nextDelta = DoubleArray(stateCount)
    private val backPointers = Array(lag) { IntArray(stateCount) }
    private var ring = 0
    private var observations = 0

    /** Chord decided for the observation [lag] steps ago; null means no chord. */
    var decided: ChordLabel? = null
        private set

    /** True after [observe] produced a decision (false during the first [lag] observations). */
    var hasDecision = false
        private set

    /** Index of the observation the latest decision refers to. */
    var decidedIndex = -1
        private set

    fun reset() {
        delta.fill(0.0)
        observations = 0
        ring = 0
        decided = null
        hasDecision = false
        decidedIndex = -1
    }

    /**
     * Feeds one averaged chroma vector spanning [seconds]. Returns true when a decision was
     * made for the observation [lag] steps back; read it from [decided] and [decidedIndex].
     */
    fun observe(chroma: FloatArray, seconds: Double): Boolean {
        val norm = templates.similarities(chroma, sims)
        var energy = 0f
        for (v in chroma) energy += v
        energy /= 12f
        val silent = norm <= 0f || energy < minEnergy

        val stay = exp(-seconds.coerceAtLeast(0.01) / chordSeconds)
        val logStay = ln(stay)
        val logSwitch = ln((1.0 - stay) / (stateCount - 1))
        val psi = backPointers[ring]

        if (observations == 0) {
            for (s in 0 until stateCount) { nextDelta[s] = emission(s, silent); psi[s] = s }
        } else {
            // Best and second-best predecessors, since switching cost is the same from anywhere.
            var best = 0
            var second = -1
            for (s in 1 until stateCount) {
                if (delta[s] > delta[best]) { second = best; best = s } else if (second < 0 || delta[s] > delta[second]) second = s
            }
            for (s in 0 until stateCount) {
                val fromSelf = delta[s] + logStay
                val fromOther = if (best != s) delta[best] + logSwitch else delta[second] + logSwitch
                if (fromSelf >= fromOther) { nextDelta[s] = fromSelf; psi[s] = s } else { nextDelta[s] = fromOther; psi[s] = if (best != s) best else second }
                nextDelta[s] += emission(s, silent)
            }
        }
        // Keep the numbers bounded.
        var max = Double.NEGATIVE_INFINITY
        for (v in nextDelta) if (v > max) max = v
        for (s in 0 until stateCount) nextDelta[s] -= max
        val t = delta; delta = nextDelta; nextDelta = t
        observations++
        ring = (ring + 1) % lag

        if (observations < lag) { hasDecision = false; return false }
        // Backtrack lag - 1 steps from the current best state.
        var state = 0
        for (s in 1 until stateCount) if (delta[s] > delta[state]) state = s
        var r = ring
        repeat(lag - 1) {
            r = (r - 1 + lag) % lag
            state = backPointers[r][state]
        }
        decided = if (state == noChord) null else templates.chords[state]
        decidedIndex = observations - lag
        hasDecision = true
        return true
    }

    private fun emission(state: Int, silent: Boolean): Double {
        if (state == noChord) return beta * noChordSimilarity + if (silent) 5.0 else 0.0
        if (silent) return 0.0
        val chord = templates.chords[state]
        return beta * (sims[state] - ChordTemplates.penalty(chord.type))
    }
}
