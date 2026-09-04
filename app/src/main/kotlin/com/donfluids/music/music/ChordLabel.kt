package com.donfluids.music.music

/** Chord types the detector can name. [intervals] are semitones above the root. */
enum class ChordType(val suffix: String, val intervals: IntArray, val isMinor: Boolean, val isSus: Boolean = false) {
    MAJOR("", intArrayOf(0, 4, 7), false),
    MINOR("m", intArrayOf(0, 3, 7), true),
    DOMINANT_SEVENTH("7", intArrayOf(0, 4, 7, 10), false),
    MAJOR_SEVENTH("maj7", intArrayOf(0, 4, 7, 11), false),
    MINOR_SEVENTH("m7", intArrayOf(0, 3, 7, 10), true),
    SUS4("sus4", intArrayOf(0, 5, 7), false, isSus = true),
    SUS2("sus2", intArrayOf(0, 2, 7), false, isSus = true),
}

/** A chord identified by its root pitch class (0 = C) and type. */
data class ChordLabel(val root: Int, val type: ChordType) {

    constructor(root: Int, minor: Boolean) : this(root, if (minor) ChordType.MINOR else ChordType.MAJOR)

    /** Minor-third chords (m, m7); everything else is treated as major-family for key finding. */
    val minor: Boolean get() = type.isMinor

    /** The plain triad this chord stands on, for comparisons that ignore extensions. */
    val triad: ChordLabel get() = ChordLabel(root, minor)

    fun name(flats: Boolean): String = Notes.spell(root, flats) + type.suffix

    /** Name spelled to suit [key], or with sharps when no key is known. */
    fun name(key: Key?): String = name(key?.usesFlats() ?: false)

    /**
     * Roman numeral of this chord relative to [key]: upper case for major, lower for minor,
     * with a flat sign for non-diatonic roots (bVII, bIII ...). Extensions are appended.
     */
    fun numeralIn(key: Key): String {
        val interval = Math.floorMod(root - key.tonic, 12)
        val base = if (key.mode == Mode.MAJOR) MAJOR_DEGREES[interval] else MINOR_DEGREES[interval]
        val numeral = if (minor) base.lowercase() else base
        return when (type) {
            ChordType.MAJOR, ChordType.MINOR -> numeral
            ChordType.DOMINANT_SEVENTH, ChordType.MINOR_SEVENTH -> numeral + "7"
            ChordType.MAJOR_SEVENTH -> numeral + "maj7"
            ChordType.SUS4 -> numeral + "sus4"
            ChordType.SUS2 -> numeral + "sus2"
        }
    }

    override fun toString(): String = name(false)

    private companion object {
        val MAJOR_DEGREES = arrayOf("I", "bII", "II", "bIII", "III", "IV", "#IV", "V", "bVI", "VI", "bVII", "VII")
        val MINOR_DEGREES = arrayOf("I", "bII", "II", "III", "#III", "IV", "#IV", "V", "VI", "#VI", "VII", "#VII")
    }
}

/** A stretch of the song during which one chord (or no chord) was heard. */
data class ChordSegment(val chord: ChordLabel?, val startSeconds: Float, val endSeconds: Float) {
    val duration: Float get() = endSeconds - startSeconds
}
