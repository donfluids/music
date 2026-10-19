package com.donfluids.music.music

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteNamingTest {
    private val doReMi = NoteNaming.FIXED_DO

    @Test fun lettersAreUnchanged() {
        assertEquals("F#m7", NoteNaming.LETTERS.of("F#m7"))
        assertEquals("Bb major", NoteNaming.LETTERS.label(Key(10, Mode.MAJOR)))
    }

    @Test fun fixedDoKeepsAccidentalsAndSuffixes() {
        assertEquals("Do", doReMi.of("C"))
        assertEquals("Fa#m7", doReMi.of("F#m7"))
        assertEquals("Sib", doReMi.of("Bb"))
        assertEquals("Solsus4", doReMi.of("Gsus4"))
    }

    @Test fun keysAndChordsFollowTheKeySpelling() {
        assertEquals("Mib major", doReMi.label(Key(3, Mode.MAJOR)))
        assertEquals("Re# minor", doReMi.label(Key(3, Mode.MINOR)))
        assertEquals("Lab", doReMi.chord(ChordLabel(8, ChordType.MAJOR), Key(3, Mode.MAJOR)))
        assertEquals("Sol#", doReMi.chord(ChordLabel(8, ChordType.MAJOR), null))
    }

    @Test fun unknownIdsFallBackToLetters() {
        assertEquals(NoteNaming.FIXED_DO, NoteNaming.byId("fixed_do"))
        assertEquals(NoteNaming.LETTERS, NoteNaming.byId(null))
        assertEquals(NoteNaming.LETTERS, NoteNaming.byId("german"))
    }
}
