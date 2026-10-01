package com.donfluids.piano

/** Just enough music theory for scale and chord highlighting. */
object Theory {
    val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MINOR = intArrayOf(0, 2, 3, 5, 7, 8, 10)
    private val MAJOR_NUMERALS = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
    private val MINOR_NUMERALS = listOf("i", "ii°", "III", "iv", "v", "VI", "VII")

    data class Scale(val tonic: Int, val minor: Boolean) {
        val pitchClasses: List<Int> get() = (if (minor) MINOR else MAJOR).map { Math.floorMod(tonic + it, 12) }

        /** Keys with flats in their signature are spelled with flats. */
        private val usesFlats: Boolean get() {
            val majorTonic = if (minor) Math.floorMod(tonic + 3, 12) else tonic
            val fifths = Math.floorMod(majorTonic * 7, 12)
            return fifths > 6
        }

        fun spell(pc: Int): String = (if (usesFlats) FLAT_NAMES else NOTE_NAMES)[Math.floorMod(pc, 12)]
        val label: String get() = spell(tonic) + if (minor) " minor" else " major"

        /** The seven diatonic triads as pitch-class lists, with numeral and name. */
        fun chords(): List<Chord> {
            val pcs = pitchClasses
            val numerals = if (minor) MINOR_NUMERALS else MAJOR_NUMERALS
            return pcs.indices.map { i ->
                val notes = listOf(pcs[i], pcs[(i + 2) % 7], pcs[(i + 4) % 7])
                val third = Math.floorMod(notes[1] - notes[0], 12)
                val fifth = Math.floorMod(notes[2] - notes[0], 12)
                val suffix = when { fifth == 6 -> "°"; third == 3 -> "m"; else -> "" }
                Chord(numerals[i], spell(notes[0]) + suffix, notes)
            }
        }
    }

    data class Chord(val numeral: String, val name: String, val pitchClasses: List<Int>)
}
