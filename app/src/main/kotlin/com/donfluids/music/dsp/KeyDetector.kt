package com.donfluids.music.dsp

import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import kotlin.math.sqrt

/**
 * The major and minor key profiles a chroma vector is matched against, tonic first.
 */
enum class KeyProfile(val major: DoubleArray, val minor: DoubleArray) {
    /** Krumhansl & Kessler (1982) probe-tone ratings. */
    KRUMHANSL_KESSLER(
        doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88),
        doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17),
    ),

    /**
     * Temperley (1999), as in MelodicKeyFinder. On GuitarSet heard through Basic Pitch it finds
     * the key of solo lines far more often than Krumhansl & Kessler (49 % against 35 %) and
     * strummed takes about as often (tools/keyeval).
     */
    TEMPERLEY(
        doubleArrayOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0),
        doubleArrayOf(5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0),
    ),
}

/**
 * Krumhansl–Schmuckler key finding: correlate a 12-bin chroma vector with the 24
 * major/minor key profiles and pick the best match.
 */
object KeyDetector {

    data class Candidate(val key: Key, val correlation: Double)

    /** All 24 keys ranked by correlation with [chroma] under [profiles], best first. */
    fun rank(chroma: FloatArray, profiles: KeyProfile = KeyProfile.KRUMHANSL_KESSLER): List<Candidate> {
        require(chroma.size == 12)
        val x = DoubleArray(12) { chroma[it].toDouble() }
        val xMean = x.average()
        var xVar = 0.0
        for (v in x) xVar += (v - xMean) * (v - xMean)
        if (xVar < 1e-12) {
            // Silence or a flat profile: nothing to say.
            return Key.all().map { Candidate(it, 0.0) }
        }
        val out = ArrayList<Candidate>(24)
        for (mode in Mode.entries) {
            val profile = if (mode == Mode.MAJOR) profiles.major else profiles.minor
            val pMean = profile.average()
            var pVar = 0.0
            for (v in profile) pVar += (v - pMean) * (v - pMean)
            for (tonic in 0 until 12) {
                var cov = 0.0
                for (i in 0 until 12) {
                    val p = profile[Math.floorMod(i - tonic, 12)]
                    cov += (x[i] - xMean) * (p - pMean)
                }
                out += Candidate(Key(tonic, mode), cov / sqrt(xVar * pVar))
            }
        }
        out.sortByDescending { it.correlation }
        return out
    }

    /**
     * A 0..1 confidence for the top candidate: how well it fits, and how clearly it beats
     * the runner-up. Both halves are heuristics; the number is for the display, not for science.
     */
    fun confidence(ranked: List<Candidate>): Double {
        if (ranked.size < 2) return 0.0
        val best = ranked[0].correlation
        val second = ranked[1].correlation
        val fit = best.coerceIn(0.0, 1.0)
        val margin = ((best - second) * 4.0).coerceIn(0.0, 1.0)
        return 0.5 * fit + 0.5 * margin
    }
}
