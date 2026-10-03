package com.donfluids.piano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BandTest {
    private val rate = 44100
    private fun q(name: String, vararg midis: Int) = BandChord.recognise(midis.toList())!!.name()

    @Test
    fun recognisesChordsLikeAnArranger() {
        assertEquals("C", q("one key", 48))
        assertEquals("Cm", q("black below", 46, 48))
        assertEquals("C7", q("white below", 47, 48))
        assertEquals("Cm", q("minor third", 48, 51))
        assertEquals("C", q("major third", 48, 52))
        assertEquals("C", q("fifth", 48, 55))
        assertEquals("C", q("triad", 48, 52, 55))
        assertEquals("Am", q("minor triad", 45, 48, 52))
        assertEquals("G7", q("seventh", 43, 47, 50, 53))
        assertEquals("Dm7", q("minor seventh", 50, 53, 57, 60))
        assertEquals("Fmaj7", q("major seventh", 41, 45, 48, 52))
        assertEquals("Bdim", q("diminished", 47, 50, 53))
        assertEquals("Gsus4", q("sus4", 43, 48, 50))
        assertEquals("C", q("first inversion", 52, 55, 60))
    }

    @Test
    fun bassAndVoicingsSitInTheirRegisters() {
        val c = BandChord(0, BandChord.Quality.MAJOR)
        assertEquals(48, Accompaniment.bassMidi(c, BassDegree.ROOT))
        assertEquals(55, Accompaniment.bassMidi(c, BassDegree.FIFTH))
        assertEquals(listOf(48, 52, 55), Accompaniment.voicing(c, Voicing.FULL))
        assertEquals(listOf(52, 55, 60), Accompaniment.voicing(c, Voicing.UPPER))
        val g7 = BandChord(7, BandChord.Quality.DOMINANT7)
        assertEquals(listOf(55, 59, 62, 65), Accompaniment.voicing(g7, Voicing.FULL))
        assertEquals(43 + 10, Accompaniment.bassMidi(g7, BassDegree.SEVENTH))
        for (root in 0 until 12) {
            val r = Accompaniment.bassMidi(BandChord(root, BandChord.Quality.MAJOR), BassDegree.ROOT)
            assertTrue("bass root $r in range", r in 40..51)
        }
    }

    private fun run(band: Accompaniment, seconds: Double, onBlock: ((Int) -> Unit)? = null): FloatArray {
        val out = FloatArray((seconds * rate).toInt())
        var i = 0
        while (i < out.size) {
            val n = minOf(256, out.size - i)
            val b = FloatArray(n)
            band.mix(b, n, (i.toDouble() / rate * 1e9).toLong())
            System.arraycopy(b, 0, out, i, n)
            onBlock?.invoke(i)
            i += n
        }
        return out
    }

    @Test
    fun rockBeatKeepsTimeAndCountsBeats() {
        val band = Accompaniment(rate)
        band.style = Style.ROCK_8
        band.bpm = 120
        band.setChord(BandChord(0, BandChord.Quality.MAJOR))
        band.playing = true
        val beats = ArrayList<Pair<Long, Int>>()
        var seen = -1L
        run(band, 4.0) { _ ->
            if (band.beatIndex >= 0 && band.beatAtNanos != seen) { seen = band.beatAtNanos; beats += band.beatAtNanos to band.beatIndex }
        }
        // Two seconds per bar at 120 in 4/4: eight beats in four seconds, half a second apart.
        assertEquals(8, beats.size)
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3), beats.map { it.second })
        for ((k, b) in beats.withIndex()) assertEquals(k * 0.5, b.first / 1e9, 0.003)
    }

    @Test
    fun bandPlaysTheChordItIsGiven() {
        val band = Accompaniment(rate)
        band.style = Style.WORSHIP_SLOW
        band.bpm = 72
        band.setChord(BandChord(7, BandChord.Quality.MAJOR)) // G
        band.playing = true
        run(band, 0.3)
        val x = run(band, 1.0)
        fun hz(m: Int) = 440.0 * Math.pow(2.0, (m - 69) / 12.0)
        val g2 = Audio.power(x, hz(43), rate)   // bass root
        val b3 = Audio.power(x, hz(59), rate)   // comp third
        val fSharp = Audio.power(x, hz(54), rate) // not in the chord
        assertTrue("bass G present: $g2", g2 > 0.01)
        assertTrue("comp B present: $b3", b3 > 0.003)
        assertTrue("F# absent: $fSharp vs $b3", fSharp < b3 * 0.2)
        // Change the chord: within a few blocks the comp plays the new third.
        band.setChord(BandChord(0, BandChord.Quality.MAJOR))
        run(band, 0.1)
        val y = run(band, 0.5)
        assertTrue("E (third of C) now present", Audio.power(y, hz(52), rate) > Audio.power(y, hz(59), rate))
    }

    @Test
    fun stopSilencesWithinASecondAndSixEightTimesCorrectly() {
        val band = Accompaniment(rate)
        band.style = Style.WORSHIP_68
        band.bpm = 60
        band.setChord(BandChord(2, BandChord.Quality.MINOR))
        band.playing = true
        var bars = 0L
        run(band, 2.05) { bars = band.barCount }
        assertEquals("one bar of 6/8 at 60 dotted quarters lasts two seconds", 1L, bars)
        band.playing = false
        run(band, 1.0)
        val tail = run(band, 0.2)
        assertTrue("silent after stop: ${Audio.peak(tail)}", Audio.peak(tail) < 1e-3f)
    }

    @Test
    fun powerRockHasNoThirdAndHitsHarderThanRock() {
        val c = BandChord(4, BandChord.Quality.MAJOR) // E
        assertEquals(listOf(52, 59, 64), Accompaniment.voicing(c, Voicing.POWER))
        fun loudness(style: Style): Double {
            val band = Accompaniment(rate); band.style = style; band.bpm = style.defaultBpm
            band.setChord(c); band.playing = true
            run(band, 0.5)
            return Audio.rms(run(band, 2.0))
        }
        assertTrue("power rock louder", loudness(Style.POWER_ROCK) > loudness(Style.ROCK_8) * 1.15)
        // The comp carries no third: G# is far weaker than B in the mix.
        val band = Accompaniment(rate); band.style = Style.POWER_ROCK; band.bpm = 152; band.setChord(c); band.playing = true
        run(band, 0.5)
        val x = run(band, 2.0)
        fun hz(m: Int) = 440.0 * Math.pow(2.0, (m - 69) / 12.0)
        assertTrue(Audio.power(x, hz(56), rate) < Audio.power(x, hz(59), rate) * 0.3)
    }

    @Test
    fun everyStyleRendersCleanly() {
        for (s in Style.ALL) {
            val band = Accompaniment(rate)
            band.style = s
            band.bpm = s.defaultBpm
            band.setChord(BandChord(5, BandChord.Quality.MAJOR7))
            band.playing = true
            val x = run(band, 3.0)
            assertTrue("${s.name} has sound", Audio.rms(x) > 0.01)
            assertTrue("${s.name} within range: ${Audio.peak(x)}", Audio.peak(x) < 1.5f)
            assertTrue("${s.name} no NaN", x.none { it.isNaN() })
        }
    }
}
