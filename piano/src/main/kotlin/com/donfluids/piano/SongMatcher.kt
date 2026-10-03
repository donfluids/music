package com.donfluids.piano

import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.music.Mode

/**
 * Chooses the accompaniment that suits what the analyser heard: time signature first, then
 * tempo band, with the key carried over so the chord buttons and the band's first chord match.
 */
object SongMatcher {
    data class Match(val style: Style, val bpm: Int, val scale: Theory.Scale?, val chord: BandChord?, val reason: String)

    /** Null until the analyser has a tempo and a time signature it is reasonably sure of. */
    fun match(a: AnalysisResult): Match? {
        val bpm = a.bpm ?: return null
        val meter = a.meterLabel ?: return null
        if (!a.tempoLocked || a.meterConfidence < 0.3f) return null
        val key = a.displayKey ?: a.key
        val scale = key?.let { Theory.Scale(it.tonic, it.mode == Mode.MINOR) }
        val chord = key?.let { BandChord(it.tonic, if (it.mode == Mode.MINOR) BandChord.Quality.MINOR else BandChord.Quality.MAJOR) }
        val tempo = Math.round(bpm)
        val (style, reason) = when {
            a.meterCompound || meter == "6/8" || meter == "12/8" -> Style.WORSHIP_68 to "compound time"
            meter == "3/4" -> Style.WALTZ to "three in a bar"
            tempo < 88 -> Style.WORSHIP_SLOW to "slow four"
            tempo < 112 -> Style.POP_16 to "medium four"
            tempo < 138 -> Style.ROCK_8 to "driving four"
            else -> Style.POWER_ROCK to "fast four"
        }
        // Styles count their own pulse: 6/8 in dotted quarters, which the analyser reports too.
        return Match(style, tempo.coerceIn(30, 240), scale, chord, reason)
    }
}
