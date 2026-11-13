package com.donfluids.music.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The constants of Beat This! (Foscarin, Schlüter and Widmer, ISMIR 2024; MIT licence), the
 * beat and downbeat model the Band and Guitar presets can follow the beat with ("Accurate" in
 * Settings). On Android the network runs through LiteRT in the app (see [BeatNetwork]); its
 * front end, postprocessing and the reading the screen needs live here, in plain Kotlin. The
 * spec, and the Python reference every part is checked against, are in tools/beatthis (README
 * sections 2 and 5, bt_live.py). A line-by-line port of the iPhone's BeatThis.swift, with the
 * same names; what only Android has is the cadence for slow phones ([BeatCadence]).
 */
object BeatThis {
    const val SAMPLE_RATE = 22050
    const val N_FFT = 1024
    /** 20 ms: 50 frames per second. */
    const val HOP = 441
    const val FPS = 50
    const val MELS = 128
    /** Zero frames on each side of a window, as the official inference pads a short excerpt. */
    const val BORDER = 6
    /** The window the network sees: the newest 8 s of frames. */
    const val FRAMES = 400
    /** The converted models take exactly this many frames. */
    const val INPUT_FRAMES = FRAMES + 2 * BORDER
    /** The first reading comes after this many seconds, then one every second (or every two on a slow phone). */
    const val FIRST_READING_SECONDS = 4
    internal const val F_MIN = 30.0
    internal const val F_MAX = 11000.0
    internal const val LOG_MULTIPLIER = 1000f
    /**
     * The resampler's cutoff. Measured against the official soxr resampling on music: 9.5 kHz
     * changed the logits by up to 10 and moved 5 % of the beats; 10.5 kHz by 0.8, 1 % of beats.
     */
    const val RESAMPLER_CUTOFF_HZ = 10500.0
}

/**
 * The two sizes of Beat This!. The iPhone carries both: small for Band, the full one for Guitar.
 * Android carries only the small one, for both, since the full one costs a phone CPU two to three
 * times as much (tools/beatthis/android/README.md).
 */
enum class BeatThisModel(
    /** For the Details fold. */
    val title: String,
) {
    SMALL("Beat This! small"),
    FULL("Beat This!"),
}

/** One window's answer: [BeatThis.INPUT_FRAMES] beat and downbeat logits. */
class BeatLogits(val beat: FloatArray, val downbeat: FloatArray)

/** The Beat This! network itself: on Android it runs through LiteRT, in the app. */
interface BeatNetwork {
    /**
     * Beat and downbeat logits for [BeatThis.INPUT_FRAMES] frames of [BeatThis.MELS] log-mel
     * values (time-major, oldest first), or null when the network failed.
     */
    fun logits(spect: FloatArray): BeatLogits?
}

/**
 * The Beat This! front end (the official LogMelSpect): 22,050 Hz mono, a centred STFT (n_fft
 * 1024, hop 441, periodic Hann), |X| / sqrt(1024), 128 triangular Slaney mel filters from 30 to
 * 11,000 Hz without area normalisation, then ln(1 + 1000 mel). Frames are made one by one as
 * the audio arrives: frame t is centred on sample 441 t and is ready once sample 441 t + 511
 * is in. Samples before the first are silence (the official code reflects the signal there;
 * only the first two frames see the difference). The newest frames are kept in a ring.
 */
class BeatThisFrontEnd(val capacity: Int = BeatThis.FRAMES + 100) {
    private val fft = Fft(BeatThis.N_FFT)
    private val window = Fft.hannWindow(BeatThis.N_FFT)
    private val audio = FloatArray(BeatThis.N_FFT)
    private var written = 0L
    private val re = FloatArray(BeatThis.N_FFT)
    private val im = FloatArray(BeatThis.N_FFT)
    private val magnitude = FloatArray(BeatThis.N_FFT / 2 + 1)
    private val frames = FloatArray(capacity * BeatThis.MELS)
    /** Frames made so far; frame t is in the ring until [capacity] newer ones are made. */
    var framesMade = 0
        private set

    fun reset() {
        audio.fill(0f)
        written = 0L
        framesMade = 0
    }

    /** Adds one sample; makes a frame when this sample completes one. */
    fun push(v: Float) {
        audio[(written % BeatThis.N_FFT).toInt()] = v
        written++
        if (written == BeatThis.HOP.toLong() * framesMade + BeatThis.N_FFT / 2) makeFrame()
    }

    private fun makeFrame() {
        val n = BeatThis.N_FFT
        // The ring's write position is the oldest of the last 1024 samples (zeros before the start).
        val pos = (written % n).toInt()
        for (i in 0 until n) {
            re[i] = audio[(pos + i) % n] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        val scale = 1f / sqrt(n.toFloat())
        for (k in 0..n / 2) magnitude[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
        val base = (framesMade % capacity) * BeatThis.MELS
        val bands = filterbank
        for (i in bands.indices) {
            var s = 0f
            val (first, weights) = bands[i]
            for (j in weights.indices) s += weights[j] * magnitude[first + j]
            frames[base + i] = ln1p(BeatThis.LOG_MULTIPLIER * s)
        }
        framesMade++
    }

    /** Frame [t] (0 = the first), or zeros when it is not in the ring. */
    fun frame(t: Int): FloatArray {
        if (t < 0 || t >= framesMade || t < framesMade - capacity) return FloatArray(BeatThis.MELS)
        val base = (t % capacity) * BeatThis.MELS
        return frames.copyOfRange(base, base + BeatThis.MELS)
    }

    /**
     * The network's input for the [BeatThis.FRAMES] frames ending at [last]: border zeros, the
     * frames (zeros for those before the first), border zeros. [out] holds INPUT_FRAMES x MELS.
     */
    fun window(last: Int, out: FloatArray) {
        val m = BeatThis.MELS
        out.fill(0f)
        val first = last - BeatThis.FRAMES + 1
        for (r in 0 until BeatThis.FRAMES) {
            val t = first + r
            if (t < 0 || t >= framesMade || t < framesMade - capacity) continue
            val src = (t % capacity) * m
            val dst = (BeatThis.BORDER + r) * m
            System.arraycopy(frames, src, out, dst, m)
        }
    }

    companion object {
        /** Each mel band's first FFT bin and its weights from there on. */
        val filterbank: List<Pair<Int, FloatArray>> by lazy {
            fun hzToMel(f: Double) = if (f >= 1000.0) 15.0 + ln(f / 1000.0) / (ln(6.4) / 27.0) else 3.0 * f / 200.0
            fun melToHz(m: Double) = if (m >= 15.0) 1000.0 * exp(ln(6.4) / 27.0 * (m - 15.0)) else 200.0 * m / 3.0
            val bins = BeatThis.N_FFT / 2 + 1
            // As torchaudio: bin k sits at k * (sr / 2) / 512 Hz with the integer Nyquist, 11,025 Hz.
            val step = (BeatThis.SAMPLE_RATE / 2).toDouble() / (bins - 1)
            val lo = hzToMel(BeatThis.F_MIN)
            val hi = hzToMel(BeatThis.F_MAX)
            val points = BeatThis.MELS + 2
            // As numpy's linspace: i * step + start, the last point exactly the end.
            val melStep = (hi - lo) / (points - 1)
            val edges = DoubleArray(points) { i -> melToHz(if (i == points - 1) hi else i * melStep + lo) }
            List(BeatThis.MELS) { i ->
                var first = -1
                val weights = ArrayList<Float>()
                for (k in 0 until bins) {
                    val f = k * step
                    val down = (f - edges[i]) / (edges[i + 1] - edges[i])
                    val up = (edges[i + 2] - f) / (edges[i + 2] - edges[i + 1])
                    val w = maxOf(0.0, minOf(down, up))
                    if (w > 0) {
                        if (first < 0) first = k
                        weights.add(w.toFloat())
                    } else if (first >= 0) {
                        break
                    }
                }
                Pair(maxOf(first, 0), weights.toFloatArray())
            }
        }
    }
}

/** The official "minimal" postprocessing (beat_this/model/postprocessor.py), for one window. */
object BeatPostprocessing {
    /**
     * Frames picked from one logit sequence: a frame equal to the maximum of the 7 frames around
     * it (beyond the ends counts as nothing) with a logit above 0; runs of picked frames that
     * are at most one frame from their running mean become that mean (so it can be fractional).
     */
    fun peakFrames(x: FloatArray): List<Double> {
        val n = x.size
        val picked = ArrayList<Int>()
        for (t in 0 until n) {
            if (!(x[t] > 0f)) continue
            var m = Float.NEGATIVE_INFINITY
            for (j in maxOf(0, t - 3) until minOf(n, t + 4)) if (x[j] > m) m = x[j]
            if (x[t] == m) picked.add(t)
        }
        val out = ArrayList<Double>()
        if (picked.isEmpty()) return out
        var p = picked[0].toDouble()
        var c = 1
        for (idx in 1 until picked.size) {
            val p2 = picked[idx]
            if (p2 - p <= 1) {
                c++
                p += (p2 - p) / c
            } else {
                out.add(p)
                p = p2.toDouble()
                c = 1
            }
        }
        out.add(p)
        return out
    }

    /**
     * Beat and downbeat times, in seconds from the window's first frame. Each downbeat is moved
     * to the nearest beat (the first of two equally near), and duplicates are dropped.
     */
    fun beatsAndDownbeats(beat: FloatArray, downbeat: FloatArray): Pair<List<Double>, List<Double>> {
        val fps = BeatThis.FPS.toDouble()
        val beats = peakFrames(beat).map { it / fps }
        val downs = peakFrames(downbeat).map { it / fps }.toMutableList()
        if (beats.isNotEmpty()) {
            for (i in downs.indices) {
                var best = 0
                for (j in beats.indices) if (abs(beats[j] - downs[i]) < abs(beats[best] - downs[i])) best = j
                downs[i] = beats[best]
            }
        }
        val unique = ArrayList<Double>()
        for (d in downs.sorted()) if (unique.lastOrNull() != d) unique.add(d)
        return Pair(beats, unique)
    }
}

/** What one window says, as the screen reads it. */
data class BeatReading(
    /** Seconds of input audio at the window's end. */
    var moment: Double = 0.0,
    /** Beats and downbeats in the window, in seconds of input audio. */
    var beats: List<Double> = emptyList(),
    var downbeats: List<Double> = emptyList(),
    var bpm: Double? = null,
    /** The median of the last 8 beat intervals. */
    var periodSeconds: Double? = null,
    /** The newest beat; the next ones are extrapolated from it by whole periods. */
    var lastBeatSeconds: Double? = null,
    /** 2, 3 or 4, from the downbeat comb pooled over readings. */
    var beatsPerBar: Int? = null,
    /** Bar position (0 = downbeat) of the beat at [lastBeatSeconds]. */
    var lastBeatIndexInBar: Int = 0,
    /** The readings of the last two seconds (three at one a second) agree within 4 %. */
    var locked: Boolean = false,
    /** Mean beat probability at the picked beats, 0..1. */
    var strength: Double = 0.0,
    /** The comb's pooled score per bar length. */
    var barScores: Map<Int, Double> = emptyMap(),
    /** Each beat divides in three (6/8, 9/8, 12/8), from the slow decision of [BeatFeel]. */
    var compound: Boolean = false,
) {
    /** The time signature to show, or null without one. */
    val meterLabel: String?
        get() {
            val b = beatsPerBar ?: return null
            if (compound) return if (b == 2) "6/8" else if (b == 3) "9/8" else "12/8"
            return "$b/4"
        }

    /** How far the winning bar length leads the next, 0..1. */
    val meterConfidence: Double
        get() {
            val b = beatsPerBar ?: return 0.0
            val best = barScores[b] ?: return 0.0
            if (best <= 0) return 0.0
            val next = barScores.filter { it.key != b }.values.maxOrNull() ?: 0.0
            return ((best - next) / best).coerceIn(0.0, 1.0)
        }
}

/**
 * Turns each window's logits into a reading (tools/beatthis/bt_live.py `Reader`, after
 * live_eval.py `bt_readouts`): tempo from the median of the last 8 beat intervals (3 beats
 * needed), the newest beat, and beats per bar by a comb on the downbeat probability. For 2, 3
 * and 4 beats per bar and each phase, the mean downbeat probability on the bar grid minus the
 * mean on the other beats; each bar length keeps its best phase's score, the scores halve every
 * second so a few windows are pooled, and the highest wins.
 *
 * Readings normally come once a second. [read] takes the seconds since the previous reading, so
 * at one every two seconds the pooling, and the time "locked" looks back over, stay the same per
 * second of music.
 */
class BeatReader(private val decay: Double = 0.5, private val intervals: Int = 8) {
    private val scores = HashMap<Int, Double>()
    private val recent = ArrayList<Double?>()
    private var previous: Previous? = null

    private class Previous(val last: Double, val index: Int, val period: Double)

    init { reset() }

    fun reset() {
        scores.clear()
        for (bb in BARS) scores[bb] = 0.0
        recent.clear()
        previous = null
    }

    /**
     * @param now seconds of input audio at the window's end
     * @param firstFrame frame number (50 per second of input) of the window's first frame
     * @param beat the window's [BeatThis.FRAMES] beat logits, borders removed
     * @param downbeat the same for downbeats
     * @param interval seconds since the previous reading: 1, or 2 on a slow phone
     */
    fun read(now: Double, firstFrame: Int, beat: FloatArray, downbeat: FloatArray, interval: Double = 1.0): BeatReading {
        val fps = BeatThis.FPS.toDouble()
        val (local, localDowns) = BeatPostprocessing.beatsAndDownbeats(beat, downbeat)
        val r = BeatReading()
        r.moment = now
        val offset = firstFrame / fps
        r.beats = local.map { it + offset }
        r.downbeats = localDowns.map { it + offset }
        val heard = r.beats.filter { it <= now + 1e-9 }
        if (heard.size >= 3) {
            val tail = heard.takeLast(intervals + 1)
            val ibis = DoubleArray(tail.size - 1) { tail[it + 1] - tail[it] }
            ibis.sort()
            val n = ibis.size
            val period = if (n % 2 == 1) ibis[n / 2] else (ibis[n / 2 - 1] + ibis[n / 2]) / 2
            r.periodSeconds = period
            if (period > 0) r.bpm = 60.0 / period
            r.lastBeatSeconds = heard.last()
        }
        // Downbeat probability at each picked beat: the largest logit within one frame of it.
        val q = local.map { v ->
            val c = Math.rint(v * fps).toInt()
            var m = Float.NEGATIVE_INFINITY
            for (j in maxOf(0, c - 1) until minOf(downbeat.size, c + 2)) if (downbeat[j] > m) m = downbeat[j]
            1.0 / (1.0 + exp(-m.toDouble()))
        }
        // Pooled per second of music: the scores keep decay^interval, and a reading adds the
        // evidence of interval seconds' worth of readings at one a second (exactly 1 at 1 s).
        val keep = if (interval == 1.0) decay else decay.pow(interval)
        val gain = if (interval == 1.0 || decay >= 1.0) interval else (1.0 - keep) / (1.0 - decay)
        val phases = HashMap<Int, Int>()
        for (bb in BARS) {
            scores[bb] = scores[bb]!! * keep
            if (q.size >= 2 * bb) {
                var best = -1.0
                for (ph in 0 until bb) {
                    var on = 0.0
                    var onCount = 0
                    var off = 0.0
                    var offCount = 0
                    for (i in q.indices) {
                        if (Math.floorMod(i - ph, bb) == 0) { on += q[i]; onCount++ } else { off += q[i]; offCount++ }
                    }
                    val v = on / onCount - off / offCount
                    if (v > best) { best = v; phases[bb] = ph }
                }
                scores[bb] = scores[bb]!! + gain * best
            }
        }
        r.barScores = scores.toMap()
        if (scores.values.any { it != 0.0 }) {
            // The first of equal scores, in the order 2, 3, 4.
            var bpb = BARS[0]
            for (bb in BARS) if (scores[bb]!! > scores[bpb]!!) bpb = bb
            r.beatsPerBar = bpb
        }
        // Where the newest beat sits in the bar: this window's best comb phase for the chosen
        // bar length, or without one the previous reading's position moved on by whole beats.
        val bpb = r.beatsPerBar
        val last = r.lastBeatSeconds
        if (bpb != null && last != null) {
            val ph = phases[bpb]
            val p = previous
            if (ph != null) {
                r.lastBeatIndexInBar = Math.floorMod(q.size - 1 - ph, bpb)
            } else if (p != null && p.period > 0) {
                r.lastBeatIndexInBar = Math.floorMod(p.index + floor((last - p.last) / p.period + 0.5).toInt(), bpb)
            }
        }
        // Locked: the readings of the last LOCK_SECONDS agree (three at one a second, two at one every two).
        val lockReadings = maxOf(2, (LOCK_SECONDS / interval).roundToInt() + 1)
        recent.add(r.bpm)
        while (recent.size > lockReadings) recent.removeAt(0)
        val known = recent.filterNotNull()
        r.locked = recent.size == lockReadings && known.size == lockReadings && known.max() / known.min() < 1.04
        if (last != null) r.periodSeconds?.let { previous = Previous(last, r.lastBeatIndexInBar, it) }
        if (local.isNotEmpty()) {
            var s = 0.0
            for (v in local) {
                val c = Math.rint(v * fps).toInt().coerceIn(0, beat.size - 1)
                s += 1.0 / (1.0 + exp(-beat[c].toDouble()))
            }
            r.strength = s / local.size
        }
        return r
    }

    companion object {
        val BARS = listOf(2, 3, 4)
        /** How far back "locked" looks: three readings at one a second. */
        const val LOCK_SECONDS = 2.0
    }
}

/**
 * Simple or compound, decided slowly on the Beat This! beat grid (tools/beatthis README section
 * 5; bt_live.py `Feel`). The network does not say whether a beat divides in two or three, so the
 * onset signal the app already has is read at the beat, halfway, and a third and two thirds of
 * the way to the next beat, as MeterDetector does. That evidence is weak (12/8 looks much like
 * 4/4 with swing), so it is pooled over about 20 s and compound is shown only when it is strong:
 * on 289 GTZAN songs in simple time it showed compound at under 1 % of moments.
 *
 * The pooling and the holds are per second of music: [update] takes the seconds since the
 * previous reading (1, or 2 on a slow phone).
 */
class BeatFeel {
    private val pooled = DoubleArray(4)
    private var weight = 0.0
    private var period = 0.0
    var compound = false
        private set
    /** Seconds of strong (on) or of weak (off) evidence in a row. */
    private var on = 0.0
    private var off = 0.0

    fun reset() {
        pooled.fill(0.0)
        weight = 0.0
        period = 0.0
        compound = false
        on = 0.0
        off = 0.0
    }

    /**
     * Adds one reading's per-beat evidence (beat, half, first third, second third; see
     * [energies]) for a beat period of [periodFrames] onset frames. Returns the decision.
     */
    fun update(energies: DoubleArray, periodFrames: Double, interval: Double = 1.0): Boolean {
        // A new beat level means new thirds: start over, and show simple until they are clear.
        if (period > 0 && abs(periodFrames / period - 1.0) > PERIOD_TOLERANCE) {
            reset()
            period = periodFrames
        }
        val follow = if (interval == 1.0) 0.8 else 0.8.pow(interval)
        period = if (period == 0.0) periodFrames else follow * period + (if (interval == 1.0) 0.2 else 1.0 - follow) * periodFrames
        // POOL per second; a reading counts for interval seconds of readings at one a second.
        val pool = if (interval == 1.0) POOL else POOL.pow(interval)
        val gain = if (interval == 1.0) 1.0 else (1.0 - pool) / (1.0 - POOL)
        for (i in 0 until 4) pooled[i] = pool * pooled[i] + gain * energies[i]
        weight = pool * weight + gain
        val beat = pooled[0]
        val half = pooled[1]
        val first = pooled[2]
        val second = pooled[3]
        val third = 0.5 * (first + second)
        val both = minOf(first, second) > BALANCE * maxOf(first, second)
        val strong = weight >= MIN_WEIGHT && both && third > ON_RATIO * half && third > BEAT_SHARE * beat
        val keep = both && third > OFF_RATIO * half
        on = if (strong) on + interval else 0.0
        off = if (keep) 0.0 else off + interval
        if (!compound && on >= HOLD) compound = true
        if (compound && off >= HOLD) compound = false
        return compound
    }

    companion object {
        const val POOL = 0.95
        const val MIN_WEIGHT = 10.0
        const val ON_RATIO = 2.0
        const val OFF_RATIO = 1.1
        const val BALANCE = 0.5
        const val BEAT_SHARE = 0.25
        /** Seconds (readings, at one a second) of strong or weak evidence in a row to switch. */
        const val HOLD = 5
        const val PERIOD_TOLERANCE = 0.08

        /**
         * One reading's evidence from the raw onset signal (one value per hop, oldest first, the
         * first at hop [firstHop]): the history is smoothed and detrended as TempoTracker does, then
         * MeterDetector's four sums over every whole beat back from the newest are divided by the
         * number of beats. Returns null when the history is too short, and the period in hops.
         */
        fun energies(onsets: FloatArray, firstHop: Int, framesPerSecond: Double, lastBeatSeconds: Double, periodSeconds: Double): Pair<DoubleArray?, Double> {
            val fps = framesPerSecond
            val n = onsets.size
            val pf = periodSeconds * fps
            val newest = lastBeatSeconds * fps - 1 - firstHop
            if (pf < 4.0 || n < 3 * fps || newest < 0) return Pair(null, pf)
            // TempoTracker.estimate(): 1-2-1 smoothing, then minus a moving average of +-0.75 s.
            val lin = DoubleArray(n) { i ->
                0.25 * onsets[maxOf(0, i - 1)] + 0.5 * onsets[i] + 0.25 * onsets[minOf(n - 1, i + 1)]
            }
            val trend = maxOf(1, floor(0.75 * fps + 0.5).toInt())
            val cumulative = DoubleArray(n + 1)
            for (i in 0 until n) cumulative[i + 1] = cumulative[i] + lin[i]
            val x = DoubleArray(n)
            for (i in 0 until n) {
                val lo = maxOf(0, i - trend)
                val hi = minOf(n - 1, i + trend)
                x[i] = lin[i] - (cumulative[hi + 1] - cumulative[lo]) / (hi - lo + 1)
            }
            fun windowMax(center: Double, half: Double): Double {
                val lo = maxOf(0, floor(center - half + 0.5).toInt())
                val hi = minOf(n - 1, floor(center + half + 0.5).toInt())
                if (lo > hi) return 0.0
                var m = Double.NEGATIVE_INFINITY
                for (i in lo..hi) if (x[i] > m) m = x[i]
                return maxOf(0.0, m)
            }
            val sub = pf / 20.0
            val e = DoubleArray(4)
            var t = newest
            while (t - pf >= 0) {
                val start = t - pf
                e[0] += windowMax(start, pf / 8.0)
                e[1] += windowMax(start + pf / 2.0, sub)
                e[2] += windowMax(start + pf / 3.0, sub)
                e[3] += windowMax(start + 2.0 * pf / 3.0, sub)
                t -= pf
            }
            val beats = maxOf(1.0, floor(newest / pf))
            return Pair(DoubleArray(4) { e[it] / beats }, pf)
        }
    }
}

/**
 * How often Beat This! can run on this phone, learnt from how long its windows take, and shared
 * by the trackers made one after another (each song, each preset) so it is learnt once per launch.
 * It starts at one window a second. If the first [BeatTracker.TIMED_WINDOWS] windows take more
 * than [BeatTracker.SLOW_MILLIS] each (their median), it goes to one window every two seconds;
 * if the next ones still take more than [BeatTracker.TOO_SLOW_MILLIS], the phone is too slow and
 * the analyser goes back to the standard tracker. It never steps back up.
 */
class BeatCadence {
    /** Seconds between windows: 1, or 2 on a slow phone. */
    @Volatile var everySeconds = 1
        private set
    /** True once this phone has proved too slow even at one window every two seconds. */
    @Volatile var tooSlow = false
        private set

    @Synchronized internal fun slowDown() { if (everySeconds == 1) everySeconds = SLOW_EVERY_SECONDS else tooSlow = true }

    companion object {
        const val SLOW_EVERY_SECONDS = 2
    }
}

/**
 * Follows the beat with Beat This! (through [BeatNetwork]) as the audio comes in: the audio is
 * resampled to 22,050 Hz into the log-mel front end, and from 4 s on, once a second (once every
 * two seconds on a slow phone, see [BeatCadence]), the newest 8 s of frames go through the network
 * and the reader. The onset signal the analyser computes anyway is kept alongside, for the
 * simple-or-compound decision.
 *
 * With a [worker], the network runs there instead of on the caller's (audio) thread. Every time
 * in a reading is in seconds of input audio, so a late answer still puts the beats where they
 * were played. [clock] (nanoseconds) times the windows; tests pass their own.
 */
class BeatTracker(
    private val network: BeatNetwork,
    val inputRate: Int,
    val hopSize: Int = 512,
    /** For the Details fold. */
    val name: String = "Beat This!",
    private val worker: java.util.concurrent.Executor? = null,
    val cadence: BeatCadence = BeatCadence(),
    private val clock: () -> Long = System::nanoTime,
) {
    private val resampler = StreamResampler(inputRate, BeatThis.SAMPLE_RATE, BeatThis.RESAMPLER_CUTOFF_HZ)
    private val frontEnd = BeatThisFrontEnd()
    private var resampled = 0L
    private var nextRun = BeatThis.FIRST_READING_SECONDS.toLong() * BeatThis.SAMPLE_RATE

    // The raw onset signal, one value per hop, in a ring (8 s and some).
    private val onsetFps = inputRate.toDouble() / hopSize
    private val onsetHistory = floor(8.0 * onsetFps + 0.5).toInt()
    private val onsets = FloatArray(onsetHistory + 64)
    private var onsetCount = 0

    /** A window waiting for the network. */
    private class Job {
        val input = FloatArray(BeatThis.INPUT_FRAMES * BeatThis.MELS)
        var onsets = FloatArray(0)
        var onsetLength = 0
        var firstHop = 0
        var moment = 0.0
        var firstFrame = 0
        var everySeconds = 1
        var generation = 0
    }
    /** The free jobs (at most two windows in flight). */
    private val free = java.util.concurrent.ArrayBlockingQueue<Job>(2).also { q -> repeat(2) { q.add(Job()) } }

    // Only touched by whoever runs the jobs (the worker, or the caller without one).
    private val reader = BeatReader()
    private val feel = BeatFeel()
    private var readerGeneration = 0
    private var lastMoment = -1.0
    private val logitsBeat = FloatArray(BeatThis.FRAMES)
    private val logitsDownbeat = FloatArray(BeatThis.FRAMES)
    /** Milliseconds of the windows timed at the current cadence. */
    private val timed = ArrayList<Double>()
    private var timedAt = 0

    // [lock] guards these.
    private val lock = Any()
    private var generation = 0
    private var latestValue: BeatReading? = null
    private var failedValue = false
    private var millisValue = 0.0
    private var skippedValue = 0
    private var readingsValue = 0

    /** The newest reading, or null before the first. */
    val latest: BeatReading? get() = synchronized(lock) { latestValue }
    /** True once the network has failed; the analyser then goes back to its own tracker. */
    val failed: Boolean get() = synchronized(lock) { failedValue }
    /** True when this phone is too slow for the network; the analyser goes back to its own tracker and says so. */
    val tooSlow: Boolean get() = cadence.tooSlow
    /** Seconds between windows now: 1, or 2 on a slow phone. */
    val everySeconds: Int get() = cadence.everySeconds
    /** Running average cost, in milliseconds per second of audio, for the on-screen diagnostics. */
    val millisPerSecond: Double get() = synchronized(lock) { millisValue }
    /** Windows the worker could not keep up with and skipped. */
    val skipped: Int get() = synchronized(lock) { skippedValue }
    /** Readings made since the start (or the last reset). */
    val readings: Int get() = synchronized(lock) { readingsValue }

    fun reset() {
        resampler.reset()
        frontEnd.reset()
        resampled = 0L
        nextRun = BeatThis.FIRST_READING_SECONDS.toLong() * BeatThis.SAMPLE_RATE
        onsetCount = 0
        synchronized(lock) {
            generation++
            latestValue = null
            readingsValue = 0
        }
    }

    /** The analyser's onset strength for the hop just heard. */
    fun pushOnset(v: Float) {
        onsets[onsetCount % onsets.size] = v
        onsetCount++
    }

    fun push(samples: FloatArray, offset: Int, count: Int) {
        // A failed network is not asked again, nor one too slow for this phone; the analyser
        // has gone back to its own tracker.
        if (failed || tooSlow) return
        resampler.push(samples, offset, count) { v ->
            frontEnd.push(v)
            resampled++
            if (resampled == nextRun) { schedule(); nextRun += cadence.everySeconds.toLong() * BeatThis.SAMPLE_RATE }
        }
    }

    private fun schedule() {
        val job = free.poll() ?: run { synchronized(lock) { skippedValue++ }; return }
        val moment = resampled.toDouble() / BeatThis.SAMPLE_RATE
        // The newest frame whose 1024 samples are all in.
        val last = Math.floorDiv(resampled - BeatThis.N_FFT / 2, BeatThis.HOP.toLong()).toInt()
        frontEnd.window(last, job.input)
        job.moment = moment
        job.firstFrame = last - BeatThis.FRAMES + 1
        job.everySeconds = cadence.everySeconds
        // The onset hops complete by this moment, the newest 8 s of them.
        val hopEnd = minOf(onsetCount - 1, floor(moment * onsetFps).toInt() - 1)
        val hopStart = maxOf(0, hopEnd - onsetHistory + 1, onsetCount - onsets.size)
        job.firstHop = hopStart
        val length = maxOf(0, hopEnd - hopStart + 1)
        if (job.onsets.size < length) job.onsets = FloatArray(onsets.size)
        for (k in 0 until length) job.onsets[k] = onsets[(hopStart + k) % onsets.size]
        job.onsetLength = length
        synchronized(lock) { job.generation = generation }
        if (worker == null) compute(job) else worker.execute { compute(job) }
    }

    private fun compute(job: Job) {
        val started = clock()
        // Silence goes no further: the network finds no beat in it.
        var loudest = 0f
        for (v in job.input) if (v > loudest) loudest = v
        var ok = true
        if (loudest < 1e-4f) {
            logitsBeat.fill(-10f)
            logitsDownbeat.fill(-10f)
        } else {
            val out = network.logits(job.input)
            if (out != null && out.beat.size == BeatThis.INPUT_FRAMES && out.downbeat.size == BeatThis.INPUT_FRAMES) {
                System.arraycopy(out.beat, BeatThis.BORDER, logitsBeat, 0, BeatThis.FRAMES)
                System.arraycopy(out.downbeat, BeatThis.BORDER, logitsDownbeat, 0, BeatThis.FRAMES)
            } else {
                ok = false
            }
        }
        if (ok) {
            if (job.generation != readerGeneration) { reader.reset(); feel.reset(); readerGeneration = job.generation; lastMoment = -1.0 }
            // The time since the previous reading: one cadence step, more after a skipped window.
            val interval = if (lastMoment >= 0.0 && job.moment > lastMoment) job.moment - lastMoment else job.everySeconds.toDouble()
            lastMoment = job.moment
            val r = reader.read(job.moment, job.firstFrame, logitsBeat, logitsDownbeat, interval)
            val last = r.lastBeatSeconds
            val period = r.periodSeconds
            if (r.bpm != null && last != null && period != null) {
                val (e, pf) = BeatFeel.energies(job.onsets.copyOf(job.onsetLength), job.firstHop, onsetFps, last, period)
                if (e != null) feel.update(e, pf, interval)
            }
            r.compound = feel.compound
            synchronized(lock) {
                if (job.generation == generation) { latestValue = r; readingsValue++ }
            }
        }
        free.offer(job)
        val ms = (clock() - started) / 1e6
        synchronized(lock) {
            if (!ok) failedValue = true
            // The cost per run spread over the seconds until the next run: the cost per second.
            if (loudest >= 1e-4f && ok) {
                val perSecond = ms / job.everySeconds
                millisValue = if (millisValue == 0.0) perSecond else 0.8 * millisValue + 0.2 * perSecond
            }
        }
        if (loudest >= 1e-4f && ok) judge(ms, job.everySeconds)
    }

    /**
     * Times the first windows at each cadence (the median, so the first run's one-off start-up
     * does not count) and slows down when they take too long. Runs where the jobs run.
     */
    private fun judge(ms: Double, at: Int) {
        if (cadence.tooSlow || at != cadence.everySeconds) return
        if (timedAt != at) { timed.clear(); timedAt = at }
        if (timed.size >= TIMED_WINDOWS) return
        timed.add(ms)
        if (timed.size < TIMED_WINDOWS) return
        val median = timed.sorted()[TIMED_WINDOWS / 2]
        val limit = if (at == 1) SLOW_MILLIS else TOO_SLOW_MILLIS
        if (median > limit) cadence.slowDown()
    }

    companion object {
        /** Windows timed at each cadence before deciding. */
        const val TIMED_WINDOWS = 3
        /**
         * At one window a second, a median above this goes to one every two seconds: half a core
         * all the time is more than a listening app should take (tools/beatthis/android/README.md
         * section 4), and it leaves room for Basic Pitch and the rest of the analysis.
         */
        const val SLOW_MILLIS = 500.0
        /**
         * At one every two seconds, a median above this means the phone is falling behind (3/4 of
         * the time, where Details says "Falling behind"): back to the standard tracker.
         */
        const val TOO_SLOW_MILLIS = 1500.0
    }
}
