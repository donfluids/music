package com.donfluids.music.music

/** A major or minor triad identified by its root pitch class (0 = C). */
data class ChordLabel(val root: Int, val minor: Boolean) {

    fun name(flats: Boolean): String = Notes.spell(root, flats) + if (minor) "m" else ""

    /** Name spelled to suit [key], or with sharps when no key is known. */
    fun name(key: Key?): String = name(key?.usesFlats() ?: false)

    /**
     * Roman numeral of this chord relative to [key]: upper case for major, lower for minor,
     * with a flat sign for non-diatonic roots (bVII, bIII ...).
     */
    fun numeralIn(key: Key): String {
        val interval = Math.floorMod(root - key.tonic, 12)
        val base = if (key.mode == Mode.MAJOR) MAJOR_DEGREES[interval] else MINOR_DEGREES[interval]
        return if (minor) base.lowercase() else base
    }

    override fun toString(): String = name(false)

    private companion object {
        // Degree names by semitone above the tonic; sharps/flats chosen the way musicians write them.
        val MAJOR_DEGREES = arrayOf("I", "bII", "II", "bIII", "III", "IV", "#IV", "V", "bVI", "VI", "bVII", "VII")
        val MINOR_DEGREES = arrayOf("I", "bII", "II", "III", "#III", "IV", "#IV", "V", "VI", "#VI", "VII", "#VII")
    }
}

/** A stretch of the song during which one chord (or no chord) was heard. */
data class ChordSegment(val chord: ChordLabel?, val startSeconds: Float, val endSeconds: Float) {
    val duration: Float get() = endSeconds - startSeconds
}
