package com.donfluids.music.music

/**
 * How note names are written: letters (C D E) or fixed Do (Do Re Mi, where C is always Do, as
 * in Italy, France, Spain and Latin America). Names are spelled with letters everywhere else in
 * the app; this only rewrites them for display.
 */
enum class NoteNaming(val id: String) {
    LETTERS("letters"),
    FIXED_DO("fixed_do");

    /** Rewrites a letter-spelled name ("F#m7", "Bb") in this style ("Fa#m7", "Sib"). */
    fun of(letterName: String): String {
        if (this == LETTERS || letterName.isEmpty()) return letterName
        val i = LETTERS_ORDER.indexOf(letterName[0])
        return if (i < 0) letterName else SOLFEGE[i] + letterName.substring(1)
    }

    fun name(key: Key): String = of(key.name)

    fun label(key: Key): String = "${name(key)} ${key.mode.label}"

    /** A chord spelled to suit [key] (sharps when there is none). */
    fun chord(chord: ChordLabel, key: Key?): String = of(chord.name(key))

    companion object {
        private const val LETTERS_ORDER = "CDEFGAB"
        private val SOLFEGE = arrayOf("Do", "Re", "Mi", "Fa", "Sol", "La", "Si")

        fun byId(id: String?): NoteNaming = entries.firstOrNull { it.id == id } ?: LETTERS
    }
}
