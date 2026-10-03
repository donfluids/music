package com.donfluids.piano

/**
 * Picks the chord of the key that best fits what has just been sung, the way an arranger's
 * auto-chord feature does. Works from a short-memory chroma (which notes sounded in the last
 * half second), scores the seven diatonic triads by how much of that energy sits on their
 * chord tones, favours the common chords a little, and changes only when a challenger beats
 * the chord already playing by a clear margin.
 */
class MelodyHarmonizer(
    private val scale: Theory.Scale,
    /** A challenger must score this much more than the current chord to replace it. */
    private val switchMargin: Float = 0.2f,
) {
    private val chords: List<BandChord> = scale.chords().map { c ->
        val root = c.pitchClasses[0]
        val third = Math.floorMod(c.pitchClasses[1] - root, 12)
        val fifth = Math.floorMod(c.pitchClasses[2] - root, 12)
        BandChord(root, when { fifth == 6 -> BandChord.Quality.DIMINISHED; third == 3 -> BandChord.Quality.MINOR; else -> BandChord.Quality.MAJOR })
    }

    // I, IV, V and vi carry most songs; ii and iii less; the diminished chord rarely.
    private val priors = if (!scale.minor) floatArrayOf(1.0f, 0.85f, 0.8f, 0.95f, 0.95f, 0.92f, 0.6f)
    else floatArrayOf(1.0f, 0.6f, 0.9f, 0.92f, 0.9f, 0.95f, 0.9f)

    /** Score of each diatonic chord for a chroma vector, 0..1-ish. */
    fun scores(chroma: List<Float>): FloatArray {
        val total = chroma.sum().coerceAtLeast(1e-6f)
        return FloatArray(chords.size) { i ->
            val c = chords[i]
            val pcs = c.pitchClasses
            // Root and fifth are the sung notes that define a chord; the third tells major from minor.
            val onChord = chroma[pcs[0]] * 1.1f + chroma[pcs[1]] * 1.0f + chroma[pcs[2]] * 0.9f
            (onChord / total) * priors[i]
        }
    }

    /**
     * The chord to play next, given what was just sung and what is playing now. Returns
     * [current] when nothing clearly better is heard, or when too little was heard at all.
     * [bandLeak] scales the current chord's own tones in [chroma] (1 = trust the microphone,
     * 0.4 suits a phone speaker in the room); it costs nothing on a clean signal.
     */
    fun next(chroma: List<Float>, current: BandChord?, minEnergy: Float = 1e-4f, bandLeak: Float = 1f): BandChord {
        val fallback = current ?: chords[0]
        if (chroma.sum() < minEnergy) return fallback
        // The band's own chord reaches the microphone from the speaker. Its chord tones are
        // discounted so a sung note outside the chord still shows through.
        val heard = if (bandLeak < 1f && current != null) chroma.mapIndexed { pc, v -> if (pc in current.pitchClasses) v * bandLeak else v } else chroma
        val s = scores(heard)
        var best = 0
        for (i in s.indices) if (s[i] > s[best]) best = i
        val currentIndex = chords.indexOfFirst { it.root == current?.root && it.quality.intervals[1] == current.quality.intervals[1] }
        if (currentIndex < 0) return chords[best]
        return if (s[best] > s[currentIndex] * (1f + switchMargin)) chords[best] else chords[currentIndex]
    }

    val diatonicChords: List<BandChord> get() = chords
}
