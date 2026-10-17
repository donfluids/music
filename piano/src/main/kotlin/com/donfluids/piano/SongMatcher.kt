package com.donfluids.piano

import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.music.Mode

/**
 * Chooses the accompaniment that suits what the analyser heard: time signature first, then
 * tempo band, with the key carried over so the chord buttons and the band's first chord match.
 */
object SongMatcher {
    /**
     * When the singers' next downbeat falls, from the analyser's beat grid, so the band can
     * enter on it. Null when the analyser has no beat. Returns a System.nanoTime() instant at
     * least [minLeadNanos] ahead of [nowNanos], stepping forward by whole bars if needed.
     */
    fun nextDownbeatNanos(a: AnalysisResult, nowNanos: Long, minLeadNanos: Long = 150_000_000L): Long? {
        if (a.lastBeatAtNanos == 0L || a.beatPeriodMillis <= 0f) return null
        val beatsPerBar = if (a.beatsPerBar > 0) a.beatsPerBar else 4
        val period = (a.beatPeriodMillis * 1e6).toLong()
        val beatsToDownbeat = Math.floorMod(beatsPerBar - a.lastBeatIndexInBar, beatsPerBar).let { if (it == 0) beatsPerBar else it }
        var at = a.lastBeatAtNanos + beatsToDownbeat * period
        val bar = beatsPerBar * period
        while (at < nowNanos + minLeadNanos) at += bar
        return at
    }

    /** The band only follows a heard chord that belongs to the song's key; stray readings are ignored. */
    fun followable(chord: BandChord, scale: Theory.Scale?): Boolean {
        val pcs = scale?.pitchClasses?.toSet() ?: return true
        return chord.pitchClasses.all { it in pcs }
    }

    data class Match(val style: Style, val bpm: Int, val scale: Theory.Scale?, val chord: BandChord?, val reason: String)

    /**
     * The heard key has stopped moving: shown unchanged for [settleSeconds] with some confidence,
     * and the singing has shown whether the major or its relative minor is home.
     */
    fun keySettled(a: AnalysisResult, settleSeconds: Int): Boolean =
        (a.displayKey ?: a.key) != null && a.modeDecided && a.keyHeldSeconds >= settleSeconds && a.keyConfidence >= 0.25f

    /**
     * A sung song with no steady pulse: the key is in but the tempo never locks. After
     * [afterSeconds] of that, the player taps the beat instead of waiting.
     */
    fun needsTap(a: AnalysisResult, afterSeconds: Float = 8f): Boolean =
        a.secondsHeard >= afterSeconds && !a.tempoLocked && (a.displayKey ?: a.key) != null

    /** The match for a tapped tempo: style from the taps and the chosen bar length, key from the ear. */
    fun matchTapped(a: AnalysisResult, bpm: Int, beatsPerBar: Int): Match {
        val key = a.displayKey ?: a.key
        val scale = key?.let { Theory.Scale(it.tonic, it.mode == Mode.MINOR) }
        val chord = key?.let { BandChord(it.tonic, if (it.mode == Mode.MINOR) BandChord.Quality.MINOR else BandChord.Quality.MAJOR) }
        val (style, reason) = when {
            beatsPerBar == 6 -> Style.WORSHIP_68 to "tapped six"
            beatsPerBar == 3 -> Style.WALTZ to "tapped three"
            bpm < 88 -> Style.WORSHIP_SLOW to "tapped slow four"
            bpm < 112 -> Style.POP_16 to "tapped medium four"
            bpm < 138 -> Style.ROCK_8 to "tapped driving four"
            else -> Style.POWER_ROCK to "tapped fast four"
        }
        return Match(style, bpm.coerceIn(30, 240), scale, chord, reason)
    }

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
