package com.donfluids.music.dsp

import kotlin.math.PI

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
) : PitchEstimator {
    private val lagMin = (sampleRate / maxHz).toInt().coerceAtLeast(2)
    private val lagMax = (sampleRate / minHz).toInt().coerceAtMost(frameSize / 2)
    private val window = Fft.hannWindow(frameSize)
    private val x = FloatArray(frameSize)

    override var hz: Double = 0.0; private set
    override var clarity: Double = 0.0; private set
    /** Fractional MIDI note of [hz] against A4 = 440 Hz. */
    override val midi: Double get() = 69.0 + 12.0 * log2(hz / 440.0)
    override val samplesNeeded: Int get() = frameSize

    /** Analyses the [frameSize] samples ending at [end] (exclusive) in [buffer]. Returns true if voiced. */
    override fun process(buffer: FloatArray, end: Int): Boolean {
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
        // Interior peaks only: acf[lagMin - 1] is never filled, so the shortest lag would always
        // look like a peak, and a low note (110 Hz and below) read as 1 kHz.
        for (lag in lagMin + 1 until best) {
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

/**
 * A sung note. [pitch] is where the note sat on the grid the app was using for this singer
 * at the time, fractional (60.3 is 30 cents above middle C); [gridCents] is that grid's offset
 * from concert pitch, so [concertPitch] is what was actually sung. [midi] is the nearest
 * semitone on the grid, derived from [pitch] so the two can never disagree.
 */
data class SungNote(val pitch: Double, val startSeconds: Double, val durationSeconds: Double, val gridCents: Double = 0.0) {
    /** A note sung exactly on a semitone at concert pitch. */
    constructor(midi: Int, startSeconds: Double, durationSeconds: Double) : this(midi.toDouble(), startSeconds, durationSeconds, 0.0)

    val midi: Int get() = Math.round(pitch).toInt()

    /** The pitch as sung, against A = 440 Hz. */
    val concertPitch: Double get() = pitch + gridCents / 100.0
}

/**
 * How a pitch splits between the semitones either side of it, given the singer's spread
 * [sigma] in semitones: a note 45 cents above C is mostly C and partly C#, not a full vote
 * for either. With [sigma] near zero this is plain rounding. Returns (pitch class, share) pairs.
 */
fun softShares(pitch: Double, sigma: Double): List<Pair<Int, Double>> {
    if (sigma < 0.05) return listOf(Math.floorMod(Math.round(pitch).toInt(), 12) to 1.0)
    val lo = kotlin.math.floor(pitch).toInt()
    val ws = (lo - 1..lo + 2).map { k -> k to kotlin.math.exp(-(pitch - k) * (pitch - k) / (2 * sigma * sigma)) }
    val total = ws.sumOf { it.second }
    return ws.filter { it.second / total > 0.01 }.map { (k, w) -> Math.floorMod(k, 12) to w / total }
}

/**
 * Turns a stream of pitch readings into notes. A note continues while the pitch stays within
 * [holdSemitones] of the note's running centre, so vibrato and a note sung between two keys
 * do not break it up. Each reading is weighted by how still the pitch is over about one
 * vibrato cycle: held pitch counts, a slide or scoop counts for little. A run that is mostly
 * slide is dropped.
 */
class NoteSegmenter(
    private val minNoteSeconds: Double = 0.12,
    private val gapSeconds: Double = 0.08,
    private val holdSemitones: Double = 0.6,
    private val steadyWindowSeconds: Double = 0.2,
    private val minSteadyShare: Double = 0.25,
) {
    val notes = ArrayList<SungNote>()
    /** Called with each note as it closes, for the singer model. */
    var onNote: ((SungNote) -> Unit)? = null

    private var runStart = -1.0
    private var runEnd = -1.0
    private var runWeightedSum = 0.0
    private var runWeight = 0.0
    private var runFrames = 0
    private var runSteadyFrames = 0
    private var runPlainSum = 0.0
    private var runGridSum = 0.0
    private val history = ArrayDeque<Pair<Double, Double>>()

    fun reset() { notes.clear(); runStart = -1.0; history.clear() }

    private fun runCentre(): Double = if (runWeight > 0.2) runWeightedSum / runWeight else runPlainSum / runFrames

    /**
     * [midi] is fractional, already on the singer's grid, which sits [gridCents] from concert
     * pitch; pass NaN for an unvoiced frame.
     */
    fun push(midi: Double, timeSeconds: Double, gridCents: Double = 0.0) {
        if (midi.isNaN()) {
            history.clear()
            if (runStart >= 0 && timeSeconds - runEnd > gapSeconds) close()
            return
        }
        // Steadiness: how far the pitch has moved over the last vibrato cycle. Vibrato returns
        // to where it was; a slide does not.
        history.addLast(timeSeconds to midi)
        while (history.size > 1 && timeSeconds - history.first().first > steadyWindowSeconds + 1e-6) history.removeFirst()
        val moved = if (history.size > 1 && timeSeconds - history.first().first >= steadyWindowSeconds * 0.75) kotlin.math.abs(midi - history.first().second) else 0.0
        val steadiness = 1.0 / (1.0 + (moved / 0.3) * (moved / 0.3))

        if (runStart >= 0) {
            val away = kotlin.math.abs(midi - runCentre())
            if (away > holdSemitones || timeSeconds - runEnd > gapSeconds) close()
        }
        if (runStart < 0) {
            runStart = timeSeconds; runWeightedSum = 0.0; runWeight = 0.0; runFrames = 0; runSteadyFrames = 0; runPlainSum = 0.0; runGridSum = 0.0
        }
        runEnd = timeSeconds
        runWeightedSum += midi * steadiness; runWeight += steadiness
        runPlainSum += midi; runGridSum += gridCents; runFrames++
        if (steadiness > 0.5) runSteadyFrames++
    }

    private fun openNote(): SungNote? {
        if (runStart < 0 || runEnd - runStart < minNoteSeconds) return null
        if (runSteadyFrames < minSteadyShare * runFrames) return null
        val centre = runCentre()
        return SungNote(centre, runStart, runEnd - runStart, runGridSum / runFrames)
    }

    fun close() {
        openNote()?.let { notes += it; onNote?.invoke(it) }
        runStart = -1.0
    }

    /** The closed notes plus the note still being sung, if it is already long enough to count. */
    fun snapshot(): List<SungNote> {
        val open = openNote() ?: return notes.toList()
        return notes + open
    }
}

/**
 * What the app learns about the singer, one note at a time.
 *
 * Offset: where the singer's "in tune" sits, in cents from A = 440 Hz. Each note's distance
 * from its nearest semitone wraps around every 100 cents, like an angle, so the offset is a
 * circular average: notes consistently 45 cents sharp all point the same way and move the
 * grid to meet them; notes scattered at random point every way and average to nothing.
 * Older notes fade over [memorySeconds], so a slow drift flat or sharp is followed. How
 * strongly the grid moves depends on how consistent the notes are (the mean resultant
 * length, 0 to 1), counting only what exceeds the consistency random notes show by chance.
 *
 * Spread: how far notes scatter around the grid once the offset is removed, in cents. A few
 * cents for a good singer, 25 or more for a poor one. It sets how softly notes are assigned
 * and how much evidence a decision needs.
 */
class SingerModel(private val memorySeconds: Double = 8.0) {
    var offsetCents = 0.0
        private set
    /**
     * Where the singer sits now, in cents from A = 440 Hz: the grid plus the full circular mean
     * of the recent notes' leftover lean. The grid itself moves cautiously, by a share of that
     * lean that depends on how consistent the notes are; the display must not be damped the
     * same way, or a scattered singer 40 cents sharp would read as 15. The grid stays within
     * half a semitone of concert pitch, so the key is named after the nearest concert key and
     * this number says how far from it the singers sit; it may pass 50 while they drift.
     */
    var currentCents = 0.0
        private set
    /** The leftover lean: the notes' circular mean distance from the grid, within half a semitone. */
    private var leanCents = 0.0
    private var sumCos = 0.0
    private var sumSin = 0.0
    private var sumWeight = 0.0
    private var sumWeightSq = 0.0
    private var lastUpdate = Double.NaN
    private val residuals = ArrayList<Pair<Double, Double>>() // |cents from grid|, weight
    var notesSeen = 0
        private set
    /**
     * Semitones the note labels have moved since the start: +1 each time the grid steps down a
     * semitone to stay within half a semitone of concert pitch (every note then reads one higher),
     * -1 for a step up. Whatever names keys from those labels must move with it.
     */
    var relabelSemitones = 0
        private set

    /**
     * Spread in cents: the weighted median distance from the grid, scaled to match a standard
     * deviation (x1.48). The median ignores the odd glitch that would inflate a mean. A middling
     * 20 until a few notes are in.
     */
    val spreadCents: Double
        get() {
            if (residuals.size < 4) return 20.0
            val sorted = residuals.sortedBy { it.first }
            val half = sorted.sumOf { it.second } / 2
            var acc = 0.0
            for ((r, w) in sorted) { acc += w; if (acc >= half) return 1.4826 * r }
            return 1.4826 * sorted.last().first
        }

    /** The soft-assignment width in semitones for this singer. */
    val sigmaSemitones: Double get() = (spreadCents / 100.0).coerceIn(0.1, 0.35)

    /**
     * [note] was segmented on the grid shifted by the offset at the time; its leftover distance
     * from the nearest semitone is what the offset (and the spread) learn from.
     */
    fun observe(note: SungNote) {
        val residual = (note.pitch - Math.round(note.pitch)) * 100.0
        val t = note.startSeconds + note.durationSeconds
        if (!lastUpdate.isNaN()) {
            val fade = kotlin.math.exp(-(t - lastUpdate).coerceAtLeast(0.0) / memorySeconds)
            sumCos *= fade; sumSin *= fade; sumWeight *= fade; sumWeightSq *= fade * fade
        }
        lastUpdate = t
        val w = note.durationSeconds.coerceIn(0.1, 1.5)
        val angle = 2 * PI * residual / 100.0
        sumCos += w * kotlin.math.cos(angle); sumSin += w * kotlin.math.sin(angle); sumWeight += w; sumWeightSq += w * w
        residuals += kotlin.math.abs(residual) to note.durationSeconds.coerceAtMost(1.5)
        notesSeen++
        val resultant = kotlin.math.sqrt(sumCos * sumCos + sumSin * sumSin) / sumWeight
        val meanCents = kotlin.math.atan2(sumSin, sumCos) * 100.0 / (2 * PI)
        // Even random notes line up a little by chance, about 1/sqrt(n) for n notes; only the
        // consistency beyond that counts, so a scattered singer does not walk the grid away.
        val chance = if (sumWeight > 0) kotlin.math.sqrt(sumWeightSq) / sumWeight else 1.0
        val excess = if (chance < 1.0) ((resultant - chance) / (1 - chance)).coerceIn(0.0, 1.0) else 0.0
        // The shown value is the whole lean, grid plus leftover. The leftover is read within half
        // a semitone of the grid, the same rounding that names each note, so the number always
        // describes the notes as labelled. Holding a reading past 50 so it would not flip sign
        // was tried: a singer sitting near 50 then read a semitone off the notes' own names.
        leanCents = meanCents
        currentCents = offsetCents + leanCents
        // A few notes' worth of evidence before the grid moves at all.
        if (sumWeight < 1.0) return
        // Consistent notes move the grid most of the way; scattered ones barely at all.
        val step = 0.8 * excess * excess * meanCents
        offsetCents += step
        leanCents -= step
        // The remembered notes are now that much nearer the grid: turn their average to match.
        val turn = -2 * PI * step / 100.0
        val c = kotlin.math.cos(turn); val s = kotlin.math.sin(turn)
        val nc = sumCos * c - sumSin * s
        sumSin = sumCos * s + sumSin * c
        sumCos = nc
        // Singers more than half a semitone from concert pitch are nearer the next key: name
        // that one. Notes are read against the current grid (see MusicAnalyzer.keyNotes), so
        // every note changes label together, and the shown lean moves a semitone with them.
        if (offsetCents > 50.0) { offsetCents -= 100.0; relabelSemitones++ }
        else if (offsetCents <= -50.0) { offsetCents += 100.0; relabelSemitones-- }
        currentCents = offsetCents + leanCents
    }

    fun reset() {
        offsetCents = 0.0; currentCents = 0.0; leanCents = 0.0; relabelSemitones = 0; sumCos = 0.0; sumSin = 0.0; sumWeight = 0.0; sumWeightSq = 0.0; lastUpdate = Double.NaN
        residuals.clear(); notesSeen = 0
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

    /** [sigma] is the singer's spread in semitones: notes between two keys share their weight. */
    fun rank(notes: List<SungNote>, phraseGapSeconds: Double = 0.4, sigma: Double = 0.0): List<Candidate> {
        if (notes.isEmpty()) return emptyList()
        val weight = DoubleArray(12)
        val longest = notes.maxByOrNull { it.durationSeconds }!!
        for ((i, n) in notes.withIndex()) {
            var w = n.durationSeconds
            val next = notes.getOrNull(i + 1)
            val phraseEnd = next == null || next.startSeconds - (n.startSeconds + n.durationSeconds) > phraseGapSeconds
            if (phraseEnd) w += 0.6
            if (n === longest) w += 0.4
            for ((pc, share) in softShares(n.pitch, sigma)) weight[pc] += w * share
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
