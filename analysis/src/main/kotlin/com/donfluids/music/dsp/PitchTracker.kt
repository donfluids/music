package com.donfluids.music.dsp

import kotlin.math.log2
import kotlin.math.sqrt

/**
 * A monophonic pitch tracker for a single voice: normalised autocorrelation over the vocal
 * range with parabolic refinement. Returns the fundamental and a 0..1 clarity, or clarity 0
 * when the frame is unvoiced, too quiet, or polyphonic enough to have no clear period.
 */
class PitchTracker(
    private val sampleRate: Int,
    val frameSize: Int = 2048,
    minHz: Double = 70.0,
    maxHz: Double = 1000.0,
    private val minLevel: Double = 0.004,
    private val minClarity: Double = 0.55,
) {
    private val lagMin = (sampleRate / maxHz).toInt().coerceAtLeast(2)
    private val lagMax = (sampleRate / minHz).toInt().coerceAtMost(frameSize / 2)
    private val window = Fft.hannWindow(frameSize)
    private val x = FloatArray(frameSize)

    var hz: Double = 0.0; private set
    var clarity: Double = 0.0; private set
    /** Fractional MIDI note of [hz] against A4 = 440 Hz. */
    val midi: Double get() = 69.0 + 12.0 * log2(hz / 440.0)

    /** Analyses the [frameSize] samples ending at [end] (exclusive) in [buffer]. Returns true if voiced. */
    fun process(buffer: FloatArray, end: Int): Boolean {
        val start = end - frameSize
        var energy = 0.0
        for (i in 0 until frameSize) { x[i] = buffer[start + i] * window[i]; energy += x[i].toDouble() * x[i] }
        val level = sqrt(energy / frameSize)
        clarity = 0.0
        if (level < minLevel) return false
        // Normalised autocorrelation, then the first strong peak above lagMin: the fundamental,
        // not an octave below it. A peak is accepted when within 0.85 of the global maximum.
        var best = -1; var bestVal = 0.0
        val acf = DoubleArray(lagMax + 1)
        for (lag in lagMin..lagMax) {
            var s = 0.0; var n0 = 0.0; var n1 = 0.0
            for (i in 0 until frameSize - lag) { val a = x[i].toDouble(); val b = x[i + lag].toDouble(); s += a * b; n0 += a * a; n1 += b * b }
            acf[lag] = if (n0 > 0 && n1 > 0) s / sqrt(n0 * n1) else 0.0
            if (acf[lag] > bestVal) { bestVal = acf[lag]; best = lag }
        }
        if (best < 0 || bestVal < minClarity) return false
        var chosen = best
        for (lag in lagMin until best) {
            if (acf[lag] >= 0.85 * bestVal && acf[lag] >= acf[lag - 1] && acf[lag] >= acf[lag + 1]) { chosen = lag; break }
        }
        val a = acf[(chosen - 1).coerceAtLeast(lagMin)]; val b = acf[chosen]; val c = acf[(chosen + 1).coerceAtMost(lagMax)]
        val denom = a - 2 * b + c
        val delta = if (denom != 0.0) (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) else 0.0
        hz = sampleRate / (chosen + delta)
        clarity = acf[chosen]
        return true
    }
}

/** A sung note: MIDI number on the singer's own grid, start time and duration. */
data class SungNote(val midi: Int, val startSeconds: Double, val durationSeconds: Double)

/**
 * Turns a stream of pitch readings into notes: a run of frames within half a semitone of
 * each other that lasts at least [minNoteSeconds]. Slides between notes are dropped.
 */
class NoteSegmenter(private val minNoteSeconds: Double = 0.12, private val gapSeconds: Double = 0.08) {
    val notes = ArrayList<SungNote>()
    private var runMidi = 0
    private var runStart = -1.0
    private var runEnd = -1.0
    private var runSum = 0.0
    private var runCount = 0

    fun reset() { notes.clear(); runStart = -1.0; runCount = 0 }

    /** [midi] is fractional, already on the singer's grid; pass NaN for an unvoiced frame. */
    fun push(midi: Double, timeSeconds: Double) {
        if (midi.isNaN()) { if (runStart >= 0 && timeSeconds - runEnd > gapSeconds) close(); return }
        val rounded = Math.round(midi).toInt()
        if (runStart >= 0 && (rounded != runMidi || timeSeconds - runEnd > gapSeconds)) close()
        if (runStart < 0) { runMidi = rounded; runStart = timeSeconds; runSum = 0.0; runCount = 0 }
        runEnd = timeSeconds; runSum += midi; runCount++
    }

    fun close() {
        if (runStart >= 0 && runEnd - runStart >= minNoteSeconds) notes += SungNote(runMidi, runStart, runEnd - runStart)
        runStart = -1.0
    }

    /** The closed notes plus the note still being sung, if it is already long enough to count. */
    fun snapshot(): List<SungNote> {
        if (runStart < 0 || runEnd - runStart < minNoteSeconds) return notes.toList()
        return notes + SungNote(runMidi, runStart, runEnd - runStart)
    }
}

/**
 * Key finding for a lone melody: duration-weighted scale-degree histogram against Temperley's
 * melodic key profiles, with extra weight on phrase-final notes and the longest note, which
 * for a sung tune are the strongest tonic cues.
 */
object MelodicKeyFinder {
    private val MAJOR = doubleArrayOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0)
    private val MINOR = doubleArrayOf(5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0)

    data class Candidate(val key: com.donfluids.music.music.Key, val score: Double)

    fun rank(notes: List<SungNote>, phraseGapSeconds: Double = 0.4): List<Candidate> {
        if (notes.isEmpty()) return emptyList()
        val weight = DoubleArray(12)
        val longest = notes.maxByOrNull { it.durationSeconds }!!
        for ((i, n) in notes.withIndex()) {
            var w = n.durationSeconds
            val next = notes.getOrNull(i + 1)
            val phraseEnd = next == null || next.startSeconds - (n.startSeconds + n.durationSeconds) > phraseGapSeconds
            if (phraseEnd) w += 0.6
            if (n === longest) w += 0.4
            weight[Math.floorMod(n.midi, 12)] += w
        }
        val mean = weight.average()
        var vx = 0.0; for (v in weight) vx += (v - mean) * (v - mean)
        if (vx < 1e-9) return emptyList()
        val out = ArrayList<Candidate>()
        for (mode in com.donfluids.music.music.Mode.entries) {
            val p = if (mode == com.donfluids.music.music.Mode.MAJOR) MAJOR else MINOR
            val pm = p.average(); var vp = 0.0; for (v in p) vp += (v - pm) * (v - pm)
            for (tonic in 0 until 12) {
                var cov = 0.0
                for (i in 0 until 12) cov += (weight[i] - mean) * (p[Math.floorMod(i - tonic, 12)] - pm)
                out += Candidate(com.donfluids.music.music.Key(tonic, mode), cov / sqrt(vx * vp))
            }
        }
        out.sortByDescending { it.score }
        return out
    }
}
