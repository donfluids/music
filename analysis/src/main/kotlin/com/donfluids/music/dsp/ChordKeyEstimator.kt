package com.donfluids.music.dsp

import com.donfluids.music.music.ChordLabel
import com.donfluids.music.music.ChordSegment
import com.donfluids.music.music.ChordType
import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode

/**
 * Suggests a key from the chords heard over a whole song.
 *
 * Every key earns points for each second a chord that belongs to it was sounding (the tonic
 * most, then IV and V, then the other diatonic triads, small credit for common borrowings),
 * loses points for foreign chords, and earns a bonus for each V-to-I cadence and for opening
 * on its tonic. This is deliberately simple: it is meant to explain itself on screen.
 */
object ChordKeyEstimator {

    data class Suggestion(
        val key: Key,
        /** 0..1, roughly the share of the song that fits the key, after bonuses. */
        val score: Double,
        /** Number of V-to-I (or V-to-i) cadences heard in this key. */
        val cadences: Int,
    )

    /** Weight of a chord by its interval above the tonic and quality, for a major key. */
    private fun majorWeight(interval: Int, minor: Boolean): Double = when {
        interval == 0 && !minor -> 1.0   // I
        interval == 5 && !minor -> 0.8   // IV
        interval == 7 && !minor -> 0.8   // V
        interval == 9 && minor -> 0.6    // vi
        interval == 2 && minor -> 0.5    // ii
        interval == 4 && minor -> 0.4    // iii
        interval == 10 && !minor -> 0.25 // bVII, common in worship songs
        interval == 5 && minor -> 0.15   // iv borrowed
        interval == 2 && !minor -> 0.1   // II (V of V)
        interval == 4 && !minor -> 0.0   // III (V of vi), common in gospel
        interval == 9 && !minor -> 0.0   // VI (V of ii)
        else -> -0.3
    }

    /** Weight of a chord for a minor key (natural and harmonic minor both allowed). */
    private fun minorWeight(interval: Int, minor: Boolean): Double = when {
        interval == 0 && minor -> 1.0    // i
        interval == 5 && minor -> 0.7    // iv
        interval == 7 && !minor -> 0.7   // V (harmonic minor)
        interval == 7 && minor -> 0.5    // v
        interval == 8 && !minor -> 0.6   // VI
        interval == 3 && !minor -> 0.5   // III
        interval == 10 && !minor -> 0.5  // VII
        interval == 5 && !minor -> 0.15  // IV (dorian)
        interval == 2 && minor -> 0.4    // ii (the diminished chord is heard as minor)
        else -> -0.3
    }

    fun weight(key: Key, chord: ChordLabel): Double {
        val interval = Math.floorMod(chord.root - key.tonic, 12)
        val base = if (key.mode == Mode.MAJOR) majorWeight(interval, chord.minor) else minorWeight(interval, chord.minor)
        // Suspended chords say less about mode; a dominant seventh on V says more.
        return when {
            chord.type.isSus -> base * 0.8
            chord.type == ChordType.DOMINANT_SEVENTH && interval == 7 -> base + 0.1
            else -> base
        }
    }

    private fun isTonic(key: Key, chord: ChordLabel) =
        chord.root == key.tonic && chord.minor == (key.mode == Mode.MINOR)

    private fun isDominant(key: Key, chord: ChordLabel) =
        Math.floorMod(chord.root - key.tonic, 12) == 7 && (!chord.minor || key.mode == Mode.MINOR)

    /** Ranks all 24 keys for the chords in [segments], best first. Empty when nothing was heard. */
    fun suggest(segments: List<ChordSegment>): List<Suggestion> {
        val chords = segments.filter { it.chord != null && it.duration > 0f }
        if (chords.isEmpty()) return emptyList()
        val total = chords.sumOf { it.duration.toDouble() }
        if (total <= 0.0) return emptyList()

        val out = ArrayList<Suggestion>(24)
        for (key in Key.all()) {
            var score = 0.0
            for (s in chords) score += weight(key, s.chord!!) * s.duration
            score /= total

            var cadences = 0
            var previous: ChordLabel? = null
            for (s in chords) {
                val c = s.chord!!
                if (previous != null && previous != c && isDominant(key, previous) && isTonic(key, c)) cadences++
                previous = c
            }
            // Cadences add a bounded bonus so a short excerpt with two of them is not swamped.
            score += minOf(cadences * 0.03, 0.15)
            if (isTonic(key, chords.first().chord!!)) score += 0.05
            if (isTonic(key, chords.last().chord!!)) score += 0.05
            out += Suggestion(key, score, cadences)
        }
        out.sortByDescending { it.score }
        return out
    }

    /** 0..1 confidence in the top suggestion: its fit and its lead over the runner-up. */
    fun confidence(ranked: List<Suggestion>): Double {
        if (ranked.size < 2) return 0.0
        val best = ranked[0].score
        val second = ranked[1].score
        return (0.5 * best.coerceIn(0.0, 1.0) + 0.5 * ((best - second) * 4.0).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
    }
}
