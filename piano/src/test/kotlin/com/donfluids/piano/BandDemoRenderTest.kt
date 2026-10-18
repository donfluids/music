package com.donfluids.piano

import org.junit.Test
import java.io.File

/** Renders each style playing a short I–V–vi–IV progression into build/sound-demos. */
class BandDemoRenderTest {
    @Test
    fun renderBandDemos() {
        val rate = 44100
        val dir = File("build/sound-demos")
        val progression = listOf(
            BandChord(7, BandChord.Quality.MAJOR), BandChord(2, BandChord.Quality.MAJOR),
            BandChord(4, BandChord.Quality.MINOR), BandChord(0, BandChord.Quality.MAJOR),
        )
        for (s in Style.ALL) {
            val band = Accompaniment(rate)
            band.style = s; band.bpm = s.defaultBpm; band.playing = true
            val reverb = Reverb(rate).also { it.enabled = true }
            val barSeconds = 60.0 / s.defaultBpm * s.beatsPerBar
            val out = FloatArray((barSeconds * 8 * rate).toInt() + rate)
            var i = 0
            var bar = -1
            while (i < out.size) {
                val currentBar = (i / rate.toDouble() / barSeconds).toInt()
                if (currentBar != bar) { bar = currentBar; band.setChord(progression[(bar / 2) % 4]) }
                val n = minOf(256, out.size - i)
                val b = FloatArray(n)
                band.mix(b, n, 0L)
                reverb.process(b, n)
                System.arraycopy(b, 0, out, i, n)
                i += n
            }
            MasterBus(rate).also { it.speakerTuning = false }.process(out, out.size)
            Audio.writeWav(File(dir, "band-${s.id}.wav"), out, rate)
        }
    }

    /**
     * For each style with a recorded groove: four bars with the written drum pattern, then four
     * bars with the recorded groove, same chords and tempo, through the master stage.
     */
    @Test
    fun renderGrooveComparisons() {
        val rate = 44100
        val dir = File("build/sound-demos")
        val chords = listOf(BandChord(7, BandChord.Quality.MAJOR), BandChord(4, BandChord.Quality.MINOR), BandChord(0, BandChord.Quality.MAJOR), BandChord(2, BandChord.Quality.MAJOR))
        for (s in Style.ALL.filter { it.groove != null }) {
            val written = Style(s.id, s.name, s.beatsPerBar, s.stepsPerBeat, s.defaultBpm, s.label, s.swing, s.compTone, s.drums, s.bass, s.comp, groove = null)
            val parts = listOf(written, s).map { style ->
                val band = Accompaniment(rate)
                band.style = style; band.bpm = s.defaultBpm; band.fillsOnChordChange = false; band.playing = true
                val reverb = Reverb(rate).also { it.enabled = true }
                val barSeconds = 60.0 / s.defaultBpm * s.beatsPerBar
                val out = FloatArray((barSeconds * 4 * rate).toInt())
                var i = 0
                var bar = -1
                while (i < out.size) {
                    val currentBar = (i / rate.toDouble() / barSeconds).toInt()
                    if (currentBar != bar) { bar = currentBar; band.setChord(chords[bar % 4]) }
                    val n = minOf(256, out.size - i)
                    val b = FloatArray(n)
                    band.mix(b, n, 0L)
                    reverb.process(b, n)
                    System.arraycopy(b, 0, out, i, n)
                    i += n
                }
                out
            }
            val gap = FloatArray(rate / 2)
            val both = parts[0] + gap + parts[1]
            MasterBus(rate).also { it.speakerTuning = false }.process(both, both.size)
            Audio.writeWav(File(dir, "groove-${s.id}.wav"), both, rate)
        }
    }
}
