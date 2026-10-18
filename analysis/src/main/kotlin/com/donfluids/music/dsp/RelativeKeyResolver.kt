package com.donfluids.music.dsp

import com.donfluids.music.music.ChordSegment
import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import kotlin.math.abs

/**
 * Major or minor? A major key and its relative minor (C major and A minor) use the same seven
 * notes, so counting notes only finds the pair. This decides which note of the pair is home.
 *
 * Each piece of evidence is a score in log-odds style: positive leans to the major key,
 * negative to the minor. They are summed with a small prior toward major, and the sum is
 * compared with a threshold; between the thresholds the answer is "undecided". Weights are
 * set by hand from music theory, not learned, and want tuning against real recordings.
 */
object RelativeKeyResolver {

    data class Evidence(
        /** Which notes phrases end on and are held: home is where a phrase comes to rest. */
        val endings: Double,
        /** The raised seventh of the minor (G# in A minor): minor keys use it, the major never does. */
        val leadingTone: Double,
        /** How the cadence notes are approached: B to C and G to C point to C; G# to A, E to A point to A. */
        val approaches: Double,
        /** Tonic chord time, the minor's major dominant (E major), and V to I cadences. */
        val chords: Double,
        /** The key-profile fit of each, from the note-based or chroma-based finder. */
        val profile: Double,
        val prior: Double,
    ) {
        val total: Double get() = endings + leadingTone + approaches + chords + profile + prior
    }

    /** The pair a key belongs to: its major and its relative minor. */
    fun pair(key: Key): Pair<Key, Key> {
        val major = if (key.mode == Mode.MAJOR) key else key.relative
        return major to major.relative
    }

    const val PRIOR_MAJOR = 0.25
    const val THRESHOLD = 1.5

    /** Seconds of singing (or of sound, without notes) before the evidence counts in full. */
    const val FULL_EVIDENCE_SECONDS = 8.0

    /**
     * Evidence for [key]'s pair. [notes] are sung notes (empty when not tracking a melody),
     * [chroma] the long-term pitch-class energy, [segments] the chords heard so far,
     * [heardSeconds] how long the analyser has listened and [nowSeconds] the current time,
     * so a last note counts as a resting note only once silence has followed it.
     *
     * Evidence from a few notes is weak, so the note and profile terms grow with the amount
     * heard; a G# or an E major chord is telling at any time and is not scaled. [sigma] is the
     * singer's spread in semitones: a note between two keys counts partly as each.
     */
    fun evidence(
        key: Key, notes: List<SungNote>, chroma: FloatArray?, segments: List<ChordSegment>,
        heardSeconds: Double = Double.POSITIVE_INFINITY, nowSeconds: Double = Double.POSITIVE_INFINITY,
        sigma: Double = 0.0,
    ): Evidence {
        val (major, minor) = pair(key)
        val M = major.tonic
        val m = minor.tonic
        val material = if (notes.isNotEmpty()) notes.sumOf { it.durationSeconds } else heardSeconds
        val amount = (material / FULL_EVIDENCE_SECONDS).coerceIn(0.0, 1.0)
        return Evidence(
            endings = amount * endings(notes, M, m, nowSeconds, sigma),
            leadingTone = leadingTone(notes, chroma, M, m, sigma),
            approaches = amount * approaches(notes, M, m, nowSeconds),
            chords = chords(segments, M, m),
            profile = amount * profile(notes, chroma, major, minor, sigma),
            prior = PRIOR_MAJOR,
        )
    }

    private fun pc(n: SungNote) = Math.floorMod(n.midi, 12)

    /** Notes where the singing comes to rest: before a breath, the last note once silence follows, the longest note. */
    private fun restingNotes(notes: List<SungNote>, nowSeconds: Double, phraseGapSeconds: Double = 0.4): List<Pair<SungNote, Double>> {
        if (notes.isEmpty()) return emptyList()
        val out = ArrayList<Pair<SungNote, Double>>()
        for ((i, n) in notes.withIndex()) {
            val end = n.startSeconds + n.durationSeconds
            val nextStart = notes.getOrNull(i + 1)?.startSeconds ?: nowSeconds
            val w = n.durationSeconds.coerceAtMost(1.5)
            // The very last note, once the singing stops, is the strongest resting point of all.
            if (nextStart - end > phraseGapSeconds) out += n to if (i == notes.size - 1) 2 * w else w
        }
        val longest = notes.maxBy { it.durationSeconds }
        if (out.none { it.first === longest }) out += longest to longest.durationSeconds.coerceAtMost(1.5)
        return out
    }

    private fun endings(notes: List<SungNote>, M: Int, m: Int, nowSeconds: Double, sigma: Double): Double {
        val resting = restingNotes(notes, nowSeconds)
        if (resting.isEmpty()) return 0.0
        var sum = 0.0
        var weight = 0.0
        for ((n, w) in resting) {
            weight += w
            for ((p, share) in softShares(n.pitch, sigma)) {
                sum += w * share * when (p) {
                    M -> 1.0
                    m -> -1.0
                    Math.floorMod(M + 7, 12) -> 0.3   // the major's fifth: a half cadence
                    Math.floorMod(M + 4, 12) -> -0.15 // the minor's fifth, but also the major's third
                    else -> 0.0
                }
            }
        }
        return 2.0 * sum / weight
    }

    private fun leadingTone(notes: List<SungNote>, chroma: FloatArray?, M: Int, m: Int, sigma: Double): Double {
        val raised = Math.floorMod(M + 8, 12)
        if (notes.isNotEmpty()) {
            var count = 0.0
            for ((i, n) in notes.withIndex()) {
                // A G sung sharp is only partly a G#.
                val share = softShares(n.pitch, sigma).firstOrNull { it.first == raised }?.second ?: continue
                // A held G# is aimed; a blip of a tenth of a second may be a slide or a mix-up.
                val held = (n.durationSeconds / 0.4).coerceAtMost(1.0)
                // Resolving up to the minor's tonic is the textbook use; any G# still counts.
                count += share * held * if (notes.getOrNull(i + 1)?.let { pc(it) == m } == true) 1.0 else 0.5
            }
            return -(1.2 * count).coerceAtMost(2.5)
        }
        if (chroma == null) return 0.0
        val natural = chroma[Math.floorMod(M + 7, 12)]
        val r = chroma[raised] / (natural + chroma[raised] + 1e-6f)
        return -2.5 * ((r - 0.15) / 0.5).toDouble().coerceIn(0.0, 1.0)
    }

    private fun approaches(notes: List<SungNote>, M: Int, m: Int, nowSeconds: Double): Double {
        val resting = restingNotes(notes, nowSeconds).map { it.first }.toSet()
        var sum = 0.0
        var count = 0
        for (i in 1 until notes.size) {
            val to = notes[i]
            if (to !in resting) continue
            count++
            val from = notes[i - 1]
            val leap = abs(to.midi - from.midi)
            val f = Math.floorMod(from.midi - M, 12)
            sum += when (pc(to)) {
                M -> when {
                    f == 11 && leap == 1 -> 0.6            // B up to C
                    f == 2 && leap == 2 -> 0.4             // D down to C
                    f == 7 && (leap == 5 || leap == 7) -> 0.6 // G to C
                    else -> 0.0
                }
                m -> when {
                    f == 8 && leap == 1 -> -1.0            // G# up to A
                    f == 11 && leap == 2 -> -0.4           // B down to A
                    f == 4 && (leap == 5 || leap == 7) -> -0.6 // E to A
                    f == 7 && leap == 2 -> -0.3            // G up to A
                    else -> 0.0
                }
                else -> 0.0
            }
        }
        if (count == 0) return 0.0
        return (2.5 * sum / maxOf(4, count)).coerceIn(-2.0, 2.0)
    }

    private fun chords(segments: List<ChordSegment>, M: Int, m: Int): Double {
        val heard = segments.filter { it.chord != null }
        val total = heard.sumOf { it.duration.toDouble() }
        if (total < 2.0) return 0.0
        var tonicMajor = 0.0
        var tonicMinor = 0.0
        var majorDominantOfMinor = 0.0
        var cadences = 0.0
        val E = Math.floorMod(M + 4, 12)
        val G = Math.floorMod(M + 7, 12)
        var previous: com.donfluids.music.music.ChordLabel? = null
        for (s in heard) {
            val c = s.chord!!
            val d = s.duration.toDouble()
            when {
                c.root == M && !c.minor -> tonicMajor += d
                c.root == m && c.minor -> tonicMinor += d
                c.root == E && !c.minor -> majorDominantOfMinor += d
            }
            val p = previous
            if (p != null && p != c) {
                if (p.root == G && !p.minor && c.root == M && !c.minor) cadences += 0.4
                if (p.root == E && c.root == m && c.minor) cadences -= if (p.minor) 0.2 else 0.4
            }
            previous = c
        }
        val share = 2.0 * (tonicMajor - tonicMinor) / total
        val dominant = -1.5 * (majorDominantOfMinor / 2.0).coerceAtMost(1.0)
        return (share + dominant + cadences).coerceIn(-2.5, 2.5)
    }

    private fun profile(notes: List<SungNote>, chroma: FloatArray?, major: Key, minor: Key, sigma: Double): Double {
        if (notes.size >= 8) {
            val ranked = MelodicKeyFinder.rank(notes, sigma = sigma)
            val a = ranked.firstOrNull { it.key == major }?.score ?: return 0.0
            val b = ranked.firstOrNull { it.key == minor }?.score ?: return 0.0
            return (5.0 * (a - b)).coerceIn(-1.2, 1.2)
        }
        if (chroma == null) return 0.0
        val ranked = KeyDetector.rank(chroma)
        val a = ranked.first { it.key == major }.correlation
        val b = ranked.first { it.key == minor }.correlation
        return (6.0 * (a - b)).coerceIn(-1.2, 1.2)
    }
}

/**
 * Holds the major-or-minor decision steady. It decides once the score clears the threshold,
 * keeps the decision while the score stays on its side, and lets go to "undecided" only when
 * the evidence has clearly faded. A different pair starts afresh.
 */
class RelativeModeTracker(private val threshold: Double = RelativeKeyResolver.THRESHOLD) {
    var decided: Key? = null
        private set
    var score: Double = 0.0
        private set
    private var pairMajor: Key? = null
    private var lean: Key? = null

    /**
     * Returns the key to show: the decision, or the lean when undecided. The lean has its own
     * small dead band so an undecided display does not swap between the pair on every note.
     * [threshold] is raised for a scattered singer, whose evidence is weaker.
     */
    fun update(key: Key, score: Double, threshold: Double = this.threshold): Key {
        val (major, minor) = RelativeKeyResolver.pair(key)
        if (major != pairMajor) { pairMajor = major; decided = null; lean = null }
        this.score = score
        decided = when {
            score > threshold -> major
            score < -threshold -> minor
            decided == major && score > threshold / 3 -> major
            decided == minor && score < -threshold / 3 -> minor
            else -> null
        }
        lean = when {
            decided != null -> decided
            lean == null -> if (score >= 0) major else minor
            lean == major && score < -LEAN_BAND -> minor
            lean == minor && score > LEAN_BAND -> major
            else -> lean
        }
        return lean!!
    }

    fun reset() { decided = null; score = 0.0; pairMajor = null; lean = null }

    private companion object { const val LEAN_BAND = 0.5 }
}
