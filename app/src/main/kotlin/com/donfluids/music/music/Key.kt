package com.donfluids.music.music

enum class Mode(val label: String) { MAJOR("major"), MINOR("minor") }

/** A musical key: a tonic pitch class (0 = C) and a mode. */
data class Key(val tonic: Int, val mode: Mode) {

    /** Spelled with sharps or flats according to the key signature. */
    val name: String get() = Notes.spell(tonic, usesFlats())

    val label: String get() = "$name ${mode.label}"

    /** Relative major/minor. */
    val relative: Key
        get() = if (mode == Mode.MAJOR) Key(Math.floorMod(tonic - 3, 12), Mode.MINOR)
        else Key(Math.floorMod(tonic + 3, 12), Mode.MAJOR)

    /** The same key moved by [semitones]. */
    fun transposed(semitones: Int): Key = Key(Math.floorMod(tonic + semitones, 12), mode)

    /** Number of sharps (positive) or flats (negative) in the key signature. */
    val signature: Int
        get() {
            val majorTonic = if (mode == Mode.MAJOR) tonic else Math.floorMod(tonic + 3, 12)
            // Position on the circle of fifths: C=0, G=1, D=2 ... F=-1, Bb=-2 ...
            val fifths = Math.floorMod(majorTonic * 7, 12)
            return if (fifths <= 6) fifths else fifths - 12
        }

    fun usesFlats(): Boolean = signature < 0

    /** Pitch classes of the diatonic scale, tonic first. */
    val scale: List<Int>
        get() {
            val steps = if (mode == Mode.MAJOR) MAJOR_STEPS else NATURAL_MINOR_STEPS
            return steps.map { Math.floorMod(tonic + it, 12) }
        }

    /** Scale note names spelled for this key. */
    val scaleNames: List<String> get() = scale.map { Notes.spell(it, usesFlats()) }

    /** Diatonic triads with roman numerals, e.g. I = C, ii = Dm. */
    val chords: List<Chord>
        get() {
            val qualities = if (mode == Mode.MAJOR) MAJOR_TRIADS else MINOR_TRIADS
            val numerals = if (mode == Mode.MAJOR) MAJOR_NUMERALS else MINOR_NUMERALS
            val s = scale
            return s.indices.map { i ->
                Chord(
                    numeral = numerals[i],
                    name = Notes.spell(s[i], usesFlats()) + qualities[i].suffix,
                    quality = qualities[i],
                )
            }
        }

    override fun toString(): String = label

    companion object {
        private val MAJOR_STEPS = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        private val NATURAL_MINOR_STEPS = intArrayOf(0, 2, 3, 5, 7, 8, 10)
        private val MAJOR_TRIADS = listOf(
            ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.MINOR, ChordQuality.MAJOR,
            ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DIMINISHED,
        )
        private val MINOR_TRIADS = listOf(
            ChordQuality.MINOR, ChordQuality.DIMINISHED, ChordQuality.MAJOR, ChordQuality.MINOR,
            ChordQuality.MINOR, ChordQuality.MAJOR, ChordQuality.MAJOR,
        )
        private val MAJOR_NUMERALS = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
        private val MINOR_NUMERALS = listOf("i", "ii°", "III", "iv", "v", "VI", "VII")

        fun all(): List<Key> = Mode.entries.flatMap { m -> (0 until 12).map { Key(it, m) } }
    }
}

enum class ChordQuality(val suffix: String) { MAJOR(""), MINOR("m"), DIMINISHED("°") }

data class Chord(val numeral: String, val name: String, val quality: ChordQuality)

object Notes {
    private val SHARP = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    fun spell(pitchClass: Int, flats: Boolean): String {
        val pc = Math.floorMod(pitchClass, 12)
        return if (flats) FLAT[pc] else SHARP[pc]
    }

    /** Neutral spelling for raw pitch classes (the chroma bars). */
    fun neutral(pitchClass: Int): String = SHARP[Math.floorMod(pitchClass, 12)]
}
