package com.donfluids.piano

import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.MusicAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The headset scenario, in software: a sung melody reaches the microphone with no band in it,
 * and the band must follow the chords the melody implies. A second run leaks the band's own
 * output into the microphone, as a phone speaker does, to measure the damage.
 */
class FollowEndToEndTest {
    private val rate = 44100

    /** A voice-like tone: fundamental plus harmonics, slow vibrato, soft attack. */
    private fun voice(out: FloatArray, start: Int, length: Int, midi: Int, amp: Float = 0.25f) {
        val f = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
        var phase = 0.0
        for (i in 0 until length) {
            val idx = start + i
            if (idx >= out.size) break
            val t = i.toDouble() / rate
            val vib = 1.0 + 0.006 * sin(2 * PI * 5.5 * t)
            phase += 2 * PI * f * vib / rate
            val env = minOf(1.0, t / 0.03) * minOf(1.0, (length - i).toDouble() / rate / 0.05)
            val s = sin(phase) + 0.5 * sin(2 * phase) + 0.25 * sin(3 * phase) + 0.12 * sin(4 * phase)
            out[idx] += (amp * env * s / 1.9).toFloat()
        }
    }

    /** Two bars each of G, C, D, Em at 100 BPM, as a melody that spells each chord. */
    private val bpm = 100
    private val barSeconds = 60.0 / bpm * 4
    private val progression = listOf(
        BandChord(7, BandChord.Quality.MAJOR) to listOf(67, 71, 74, 71, 67, 74, 71, 67),   // G: G B D
        BandChord(0, BandChord.Quality.MAJOR) to listOf(72, 76, 79, 76, 72, 79, 76, 72),   // C: C E G
        BandChord(2, BandChord.Quality.MAJOR) to listOf(74, 78, 81, 78, 74, 81, 78, 74),   // D: D F# A
        BandChord(4, BandChord.Quality.MINOR) to listOf(76, 79, 83, 79, 76, 83, 79, 76),   // Em: E G B
    )

    private fun melody(): FloatArray {
        val out = FloatArray((barSeconds * 8 * rate).toInt())
        val beat = (barSeconds / 4 * rate).toInt()
        progression.forEachIndexed { ci, (_, notes) ->
            notes.forEachIndexed { k, midi -> voice(out, ((ci * 2 * 4 + k) * beat), (beat * 0.9).toInt(), midi) }
        }
        return out
    }

    /** Runs the follower over [mic], returning the chord the band was playing at the end of each bar. */
    /**
     * [ownSound]: the app's current path, where the analyser is told which notes the band is
     * sounding and turns them down, as Listen does.
     */
    private fun follow(mic: FloatArray, leakBand: Boolean, ownSound: Boolean = false): List<String> {
        val analyzer = MusicAnalyzer(rate, AnalysisPreset.VOICES)
        val own = OwnSound()
        if (ownSound) analyzer.ownSound = { t -> own.mask(t) }
        val band = Accompaniment(rate)
        band.style = Style.POP_16; band.bpm = bpm; band.playing = true
        val scale = Theory.Scale(7, minor = false)
        val harmonizer = MelodyHarmonizer(scale)
        var chord = BandChord(7, BandChord.Quality.MAJOR)
        band.setChord(chord)
        val chordsAtBarEnd = ArrayList<String>()
        var lastHalfBar = -1L
        var lastBar = -1L
        val block = 256
        val bandOut = FloatArray(block)
        var i = 0
        while (i < mic.size) {
            val n = minOf(block, mic.size - i)
            java.util.Arrays.fill(bandOut, 0f)
            band.mix(bandOut, n, 0L)
            own.sounded(band.soundingMask, (i.toDouble() / rate * 1e9).toLong())
            val input = FloatArray(n) { k -> mic[i + k] + if (leakBand) bandOut[k] * 1.2f else 0f }
            analyzer.process(input, n, ((i + n).toDouble() / rate * 1e9).toLong())
            val halfBar = band.barCount * 2 + (if (band.beatIndex * 2 >= 4) 1 else 0)
            if (halfBar != lastHalfBar) {
                lastHalfBar = halfBar
                val next = harmonizer.next(analyzer.result.recentChroma, chord)
                if (next != chord) { chord = next; band.setChord(next) }
            }
            if (band.barCount != lastBar) { if (lastBar >= 0) chordsAtBarEnd += chord.name(); lastBar = band.barCount }
            i += n
        }
        return chordsAtBarEnd
    }

    @Test
    fun withAHeadsetTheBandFollowsTheSungChords() {
        val got = follow(melody(), leakBand = false)
        val expected = listOf("G", "G", "C", "C", "D", "D", "Em", "Em")
        // Allow the first bar of each new chord to be the previous one (half-bar latency).
        val matches = got.indices.count { got[it] == expected[it] }
        println("headset: $got")
        assertTrue("followed ${matches}/8 bars: $got", matches >= 6)
        assertEquals("ends on Em", "Em", got.last())
    }

    @Test
    fun speakerLeakageWithoutCancellationHurts() {
        val clean = follow(melody(), leakBand = false)
        val leaked = follow(melody(), leakBand = true)
        val expected = listOf("G", "G", "C", "C", "D", "D", "Em", "Em")
        val cleanScore = clean.indices.count { clean[it] == expected[it] }
        val leakedScore = leaked.indices.count { leaked[it] == expected[it] }
        println("clean $clean = $cleanScore/8; leaked $leaked = $leakedScore/8")
        assertTrue("leakage should not do better than a clean signal", leakedScore <= cleanScore)
    }


    @Test
    fun withTheAppsOwnNotesLeftOutTheSpeakerCaseFollows() {
        // How Listen works now: the analyser turns down the band's own notes.
        val expected = listOf("G", "G", "C", "C", "D", "D", "Em", "Em")
        val got = follow(melody(), leakBand = true, ownSound = true)
        val score = got.indices.count { got[it] == expected[it] }
        println("own-sound leaked $got = $score/8")
        assertTrue("own-sound leaked: $score/8", score >= 6)
    }
}
