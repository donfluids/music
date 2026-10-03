package com.donfluids.piano

import org.junit.Assert.assertEquals
import org.junit.Test

class MelodyHarmonizerTest {
    private val g = Theory.Scale(7, minor = false)
    private fun sung(vararg pcWeights: Pair<Int, Float>) = List(12) { pc -> pcWeights.firstOrNull { it.first == pc }?.second ?: 0f }

    @Test
    fun diatonicChordsOfG() {
        val h = MelodyHarmonizer(g)
        assertEquals(listOf("G", "Am", "Bm", "C", "D", "Em", "F#dim"), h.diatonicChords.map { it.name() })
    }

    @Test
    fun melodyNotesPickTheChordThatContainsThem() {
        val h = MelodyHarmonizer(g)
        val gChord = BandChord(7, BandChord.Quality.MAJOR)
        // Singing C and E over a G chord: that is C major.
        assertEquals("C", h.next(sung(0 to 1f, 4 to 0.8f), gChord).name())
        // Singing D and F#: D major.
        assertEquals("D", h.next(sung(2 to 1f, 6 to 0.7f, 9 to 0.5f), gChord).name())
        // Singing E and G and B: E minor beats G (both contain G and B, but E is only in Em).
        assertEquals("Em", h.next(sung(4 to 1f, 7 to 0.6f, 11 to 0.6f), gChord).name())
    }

    @Test
    fun staysOnTheCurrentChordUnlessClearlyBeaten() {
        val h = MelodyHarmonizer(g)
        val gChord = BandChord(7, BandChord.Quality.MAJOR)
        // B and D are in both G and Bm; nothing clearly better, so stay on G.
        assertEquals("G", h.next(sung(11 to 1f, 2 to 1f), gChord).name())
        // A lone G note: stays.
        assertEquals("G", h.next(sung(7 to 1f), gChord).name())
        // Silence: stays.
        assertEquals("G", h.next(List(12) { 0f }, gChord).name())
        // No current chord: the tonic.
        assertEquals("G", h.next(List(12) { 0f }, null).name())
    }

    @Test
    fun minorKeyWorksToo() {
        val h = MelodyHarmonizer(Theory.Scale(9, minor = true))
        assertEquals(listOf("Am", "Bdim", "C", "Dm", "Em", "F", "G"), h.diatonicChords.map { it.name() })
        val am = BandChord(9, BandChord.Quality.MINOR)
        assertEquals("F", h.next(sung(5 to 1f, 9 to 0.7f, 0 to 0.7f), am).name())
        assertEquals("G", h.next(sung(7 to 1f, 11 to 0.8f, 2 to 0.6f), am).name())
    }
}
