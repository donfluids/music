package com.donfluids.music.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Follows where untrained singers have taken the key, as one continuous position in cents.
 *
 * Singers may start in C, wander up to C# and D, and come back. Tracking pitch within a
 * semitone cannot see that (a tune moved up exactly a semitone fits the new grid perfectly),
 * and counting all the notes mixes two keys into a third. Instead:
 *
 * 1. Every note is compared with a pitch pattern placed at each of 120 positions around the
 *    octave, 10 cents apart. A Viterbi pass links the notes in time: the position may drift a
 *    few cents a second at little cost, a clean step of one or two semitones (a deliberate key
 *    change) is allowed at a fixed cost, and anything bigger between two notes is ruled out. The
 *    position never resets at a semitone, so the result is a single curve: C, C plus 40, C# plus
 *    10, D, back to C.
 * 2. The pattern is the song itself. Many songs use only five notes (C D E G A), which fit
 *    three seven-note scales; moved up a semitone they fit three shifted ones, two of them a
 *    semitone apart in opposite directions, so a generic scale cannot tell which way the singers
 *    went. The tune matches itself only at the true shift. The first pass uses the opening
 *    notes, before the singers have had time to wander, blended with the scale that fits them;
 *    then the notes, moved back along that curve, become the song's own pitch profile, and the
 *    curve is decoded again against it, twice.
 * 3. From the curve: the centre (where the singing spent most of its time), now, the range, and
 *    whether the singers are still moving or have settled somewhere new. An instant step and a
 *    quick slide that then holds look alike once real voices blur the change, and to a player
 *    they mean the same thing.
 *
 * The position is of the major scale's tonic; a relative minor shares it. Which of the pair is
 * home is left to the key finders, which get the notes moved back to the centre.
 */
class KeyLevelTracker(
    /**
     * How fast the level may drift, as variance per second (cents squared): about a semitone in
     * a minute comes easily, so wandering is followed, but a careless singer's note-to-note
     * scatter is not mistaken for it.
     */
    private val driftVariancePerSecond: Double = 150.0,
    /** Cost of a clean step of one or two semitones, in log-likelihood. */
    private val stepCost: Double = 6.0,
    private val minNotes: Int = 8,
    private val maxNotes: Int = 400,
) {
    /**
     * How the level has been moving lately. [SETTLED]: it moved most of a semitone or more in
     * the last 25 seconds and has held the new level for at least five, whether by a deliberate
     * key change or a quick slide: the singers are now in a new key, so follow them. [RISING]
     * and [FALLING]: still on the move.
     */
    enum class Move { STEADY, RISING, FALLING, SETTLED }

    data class Level(
        /** The notes moved back to the centre key and onto its exact semitones. */
        val notes: List<SungNote>,
        /**
         * Centre and now, in cents at concert pitch, as positions of the scale's anchor. Their
         * cents within a semitone and their differences are what count: for a five-note tune the
         * whole-semitone part may sit on any of the scales that fit it, which cancels out.
         */
        val centreCents: Double,
        val nowCents: Double,
        /** Where most of the singing sat (10th to 90th percentile), relative to the centre's nearest semitone. */
        val lowCents: Double,
        val highCents: Double,
        val move: Move,
        /** How far notes sat from the scale once the wandering is removed, in cents. */
        val spreadCents: Double,
    ) {
        /** Semitones from the centre's nearest key to where the singers are now. */
        val nowShift: Int get() = (nowCents / 100.0).roundToInt() - (centreCents / 100.0).roundToInt()
        val lowShift: Int get() = (lowCents / 100.0).roundToInt()
        val highShift: Int get() = (highCents / 100.0).roundToInt()
    }

    private var cachedCount = -1
    private var cachedFirst: SungNote? = null
    private var cachedPath = DoubleArray(0)
    private var spreadForTemplate = 30.0

    /** Null until there are enough notes to place the scale. */
    fun analyse(notes: List<SungNote>): Level? {
        val use = if (notes.size > maxNotes) notes.subList(notes.size - maxNotes, notes.size) else notes
        if (use.size < minNotes) return null
        // The tracker must never assume a tighter singer than the notes show, or it would
        // explain careless notes away as wandering.
        floorSpread = noteToNoteSpread(use)
        // The note still being sung changes every frame; the path is decoded over closed notes
        // and reused until another note closes.
        if (use.size != cachedCount || use.first() != cachedFirst) {
            cachedPath = refine(use)
            cachedCount = use.size
            cachedFirst = use.first()
        }
        val path = cachedPath
        val n = use.size

        // Centre: the duration-weighted median of the curve.
        val weights = DoubleArray(n) { use[it].durationSeconds.coerceIn(0.1, 1.5) }
        val centre = weightedPercentile(path, weights, 0.5)
        val centreGrid = 100.0 * (centre / 100.0).roundToInt()
        val low = weightedPercentile(path, weights, 0.1) - centreGrid
        val high = weightedPercentile(path, weights, 0.9) - centreGrid

        // Now: the last few notes.
        val tail = maxOf(1, minOf(4, n / 4))
        var nowSum = 0.0; var nowW = 0.0
        for (i in n - tail until n) { nowSum += path[i] * weights[i]; nowW += weights[i] }
        val now = nowSum / nowW

        // Each note moved back by however far the level had wandered from the centre's semitone,
        // so the key finders see one steady key, exactly in tune.
        val moved = use.mapIndexed { i, note ->
            SungNote(note.concertPitch - (path[i] - centreGrid) / 100.0, note.startSeconds, note.durationSeconds, 0.0)
        }

        // Spread: distance from the nearest scale note once the wandering is removed.
        val dists = DoubleArray(n) { i -> distanceToScale(use[i].concertPitch * 100.0, path[i]) }
        val spread = maxOf(1.4826 * weightedPercentile(dists, weights, 0.5), floorSpread)
        spreadForTemplate = spread.coerceIn(15.0, 45.0)

        return Level(moved, centre, now, low, high, classify(use, path), spread)
    }

    private fun classify(notes: List<SungNote>, path: DoubleArray): Move {
        val n = notes.size
        val endT = notes.last().startSeconds
        // Settled somewhere new: in the last 25 seconds the level moved most of a semitone or more
        // within about six seconds, then held for at least five. A wobble does not hold; a creep
        // is too slow to move that far that fast.
        for (i in n - 1 downTo 1) {
            val at = notes[i].startSeconds
            if (endT - at > 25.0) break
            if (endT - at < 5.0) continue
            val held = (i until n).map { path[it] }.toDoubleArray()
            val ones = DoubleArray(held.size) { 1.0 }
            val heldLevel = weightedPercentile(held, ones, 0.5)
            if (weightedPercentile(held, ones, 0.9) - weightedPercentile(held, ones, 0.1) >= 40.0) continue
            val earlier = (0 until i).filter { at - notes[it].startSeconds in 0.0..6.0 }
            if (earlier.any { abs(heldLevel - path[it]) >= 70.0 }) return Move.SETTLED
        }
        // Otherwise the slope over the last 15 seconds.
        val recent = (0 until n).filter { endT - notes[it].startSeconds <= 15.0 }
        if (recent.size < 4) return Move.STEADY
        val ts = recent.map { notes[it].startSeconds }
        val ys = recent.map { path[it] }
        val tm = ts.average(); val ym = ys.average()
        var num = 0.0; var den = 0.0
        for (k in ts.indices) { num += (ts[k] - tm) * (ys[k] - ym); den += (ts[k] - tm) * (ts[k] - tm) }
        val slope = if (den > 1e-9) num / den else 0.0
        return when {
            slope > 0.8 -> Move.RISING
            slope < -0.8 -> Move.FALLING
            else -> Move.STEADY
        }
    }

    /**
     * Decodes against a first pattern built from the song's opening notes (where the singers
     * have not yet had time to wander), blended with the seven-note scale that best fits them;
     * then twice against the song's own pitch profile, built from all the notes moved back along
     * the previous curve.
     */
    private fun refine(notes: List<SungNote>): DoubleArray {
        val sigma = maxOf(spreadForTemplate, floorSpread)
        val weights = DoubleArray(notes.size) { notes[it].durationSeconds.coerceIn(0.1, 1.5) }
        val opening = notes.indices.filter { notes[it].startSeconds - notes[0].startSeconds <= 8.0 }.ifEmpty { notes.indices.toList() }
        // The scale position that best fits the opening.
        val fit = DoubleArray(STATES)
        for (i in opening) { val e = scaleEmission(notes[i], sigma); for (s in 0 until STATES) fit[s] += e[s] }
        val scaleAt = (0 until STATES).maxByOrNull { fit[it] }!! * STEP
        val first = DoubleArray(STATES)
        addToProfile(first, opening.map { notes[it].concertPitch * 100.0 }, opening.map { weights[it] }, sigma)
        normalise(first)
        for (b in 0 until STATES) {
            val d = distanceToScale(b * STEP, scaleAt)
            first[b] = 0.5 * first[b] + 0.5 * exp(-d * d / (2 * sigma * sigma))
        }
        // The opening profile is in concert terms, its level at the fitted scale position: decoding
        // against it keeps the curve in concert cents.
        var path = decode(notes) { note -> profileEmission(note, first, scaleAt) }
        repeat(2) {
            val ref = weightedPercentile(path, weights, 0.5)
            val profile = DoubleArray(STATES)
            // Each note as if sung at the reference level.
            addToProfile(profile, notes.indices.map { notes[it].concertPitch * 100.0 - (path[it] - ref) }, weights.toList(), sigma)
            normalise(profile)
            path = decode(notes) { note -> profileEmission(note, profile, ref) }
        }
        // Whole octaves are arbitrary: put the centre within half an octave of C.
        val centre = weightedPercentile(path, weights, 0.5)
        val octaves = 1200.0 * Math.floor((centre + 600.0) / 1200.0)
        for (i in path.indices) path[i] -= octaves
        return path
    }

    private fun addToProfile(profile: DoubleArray, cents: List<Double>, weights: List<Double>, sigma: Double) {
        for ((k, c) in cents.withIndex()) {
            for (b in 0 until STATES) {
                var d = Math.floorMod(Math.round(c - b * STEP).toInt(), 1200).toDouble()
                if (d > 600.0) d = 1200.0 - d
                profile[b] += weights[k] * exp(-d * d / (2 * sigma * sigma))
            }
        }
    }

    private fun normalise(profile: DoubleArray) {
        val peak = profile.maxOrNull()!!.coerceAtLeast(1e-9)
        for (b in profile.indices) profile[b] /= peak
    }

    /** State s places the profile's reference level [ref] at position s: the note is looked up relative to it. */
    private fun profileEmission(note: SungNote, profile: DoubleArray, ref: Double): DoubleArray {
        val c = note.concertPitch * 100.0
        val w = note.durationSeconds.coerceIn(0.1, 1.5) / 0.5
        return DoubleArray(STATES) { s ->
            val bin = Math.floorMod(Math.round((c - (s * STEP - ref)) / STEP).toInt(), STATES)
            w * ln(profile[bin] + OUT_OF_SCALE)
        }
    }

    /** Viterbi over 120 positions with the given per-note emission; returns the unwrapped curve in cents. */
    private fun decode(notes: List<SungNote>, emission: (SungNote) -> DoubleArray): DoubleArray {
        val n = notes.size
        val emit = Array(n) { i -> emission(notes[i]) }
        val score = DoubleArray(STATES) { emit[0][it] }
        val back = Array(n) { IntArray(STATES) }
        val next = DoubleArray(STATES)
        for (i in 1 until n) {
            val dt = (notes[i].startSeconds - notes[i - 1].startSeconds).coerceAtLeast(0.05)
            val variance = driftVariancePerSecond * dt + 5.0 * 5.0
            for (s in 0 until STATES) {
                var best = Double.NEGATIVE_INFINITY; var arg = s
                for (k in -BAND..BAND) {
                    val from = Math.floorMod(s - k, STATES)
                    val cents = abs(k) * STEP
                    val drift = -(cents * cents) / (2 * variance)
                    val step = if (cents in 60.0..240.0) -stepCost else Double.NEGATIVE_INFINITY
                    val move = maxOf(drift, step)
                    val v = score[from] + move
                    if (v > best) { best = v; arg = from }
                }
                next[s] = best + emit[i][s]
                back[i][s] = arg
            }
            System.arraycopy(next, 0, score, 0, STATES)
        }
        var s = (0 until STATES).maxByOrNull { score[it] }!!
        val states = IntArray(n)
        for (i in n - 1 downTo 0) {
            states[i] = s
            if (i > 0) s = back[i][s]
        }
        // Unwrap: each step is the short way round, so the curve never resets at a semitone.
        val path = DoubleArray(n)
        path[0] = states[0] * STEP
        for (i in 1 until n) {
            var d = states[i] - states[i - 1]
            if (d > STATES / 2) d -= STATES
            if (d < -STATES / 2) d += STATES
            path[i] = path[i - 1] + d * STEP
        }
        return path
    }

    /** Log-likelihood of [note] for the major scale's tonic at each position, weighted by duration. */
    private fun scaleEmission(note: SungNote, sigma: Double): DoubleArray {
        val cents = note.concertPitch * 100.0
        val w = note.durationSeconds.coerceIn(0.1, 1.5) / 0.5
        return DoubleArray(STATES) { s ->
            val d = distanceToScale(cents, s * STEP)
            w * ln(exp(-d * d / (2 * sigma * sigma)) + OUT_OF_SCALE)
        }
    }

    private fun distanceToScale(cents: Double, tonicCents: Double): Double {
        val rel = Math.floorMod(Math.round(cents - tonicCents).toInt(), 1200).toDouble()
        var best = 600.0
        for (deg in MAJOR_DEGREES) {
            var d = abs(rel - deg * 100.0)
            if (d > 600.0) d = 1200.0 - d
            if (d < best) best = d
        }
        return best
    }

    /**
     * The singer's scatter from one note to the next, in cents. Each note sits some way off the
     * nearest semitone; a wandering singer carries that offset along, moving it a few cents per
     * note, while a careless one lands somewhere new each time. The median change between
     * neighbouring notes (wrapped to a semitone, divided by the square root of two because both
     * notes scatter) measures the scatter and ignores the wandering, like differencing a trend.
     */
    private fun noteToNoteSpread(notes: List<SungNote>): Double {
        val diffs = ArrayList<Double>()
        val w = ArrayList<Double>()
        for (i in 1 until notes.size) {
            if (notes[i].startSeconds - notes[i - 1].startSeconds > 3.0) continue
            val a = notes[i].concertPitch * 100.0
            val b = notes[i - 1].concertPitch * 100.0
            var d = Math.floorMod(Math.round(a - b).toInt(), 100).toDouble()
            if (d > 50.0) d = 100.0 - d
            diffs += d
            w += minOf(notes[i].durationSeconds, notes[i - 1].durationSeconds).coerceIn(0.1, 1.5)
        }
        if (diffs.size < 4) return 0.0
        return 1.4826 * weightedPercentile(diffs.toDoubleArray(), w.toDoubleArray(), 0.5) / kotlin.math.sqrt(2.0)
    }

    private fun weightedPercentile(values: DoubleArray, weights: DoubleArray, q: Double): Double {
        val order = values.indices.sortedBy { values[it] }
        val total = weights.sum()
        var acc = 0.0
        for (i in order) { acc += weights[i]; if (acc >= q * total) return values[i] }
        return values[order.last()]
    }

    private var floorSpread = 0.0

    fun reset() { cachedCount = -1; cachedFirst = null; spreadForTemplate = 30.0; floorSpread = 0.0 }

    private companion object {
        const val STATES = 120
        const val STEP = 10.0
        /** Up to three semitones between two notes; more than that is not a wander. */
        const val BAND = 30
        /** A note off the scale is unlikely but not impossible: a passing chromatic note. */
        const val OUT_OF_SCALE = 0.05
        val MAJOR_DEGREES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    }
}
