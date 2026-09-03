package com.donfluids.music.music

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTest {
    @Test
    fun spellingFollowsKeySignature() {
        assertEquals(listOf("G", "A", "B", "C", "D", "E", "F#"), Key(7, Mode.MAJOR).scaleNames)
        assertEquals(listOf("F", "G", "A", "Bb", "C", "D", "E"), Key(5, Mode.MAJOR).scaleNames)
        assertEquals(listOf("Eb", "F", "G", "Ab", "Bb", "C", "D"), Key(3, Mode.MAJOR).scaleNames)
        assertEquals(listOf("D", "E", "F", "G", "A", "Bb", "C"), Key(2, Mode.MINOR).scaleNames)
    }

    @Test
    fun relativeKeys() {
        assertEquals(Key(9, Mode.MINOR), Key(0, Mode.MAJOR).relative)
        assertEquals(Key(0, Mode.MAJOR), Key(9, Mode.MINOR).relative)
        assertEquals("E minor", Key(7, Mode.MAJOR).relative.label)
    }

    @Test
    fun diatonicChords() {
        val c = Key(0, Mode.MAJOR).chords
        assertEquals(listOf("C", "Dm", "Em", "F", "G", "Am", "B°"), c.map { it.name })
        assertEquals(listOf("I", "ii", "iii", "IV", "V", "vi", "vii°"), c.map { it.numeral })
        val am = Key(9, Mode.MINOR).chords
        assertEquals(listOf("Am", "B°", "C", "Dm", "Em", "F", "G"), am.map { it.name })
    }

    @Test
    fun signatures() {
        assertEquals(0, Key(0, Mode.MAJOR).signature)
        assertEquals(2, Key(2, Mode.MAJOR).signature)
        assertEquals(-3, Key(3, Mode.MAJOR).signature)
        assertEquals(-3, Key(0, Mode.MINOR).signature)
        assertEquals(6, Key(6, Mode.MAJOR).signature)
    }
}
