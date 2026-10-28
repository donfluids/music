package com.donfluids.music.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The notes being played, heard by [BasicPitch], turned into pitch-class energy for the chord
 * and key finders in place of the spectral chroma.
 *
 * Audio is resampled to 22,050 Hz into a 2-second window. Every [runEverySeconds] the model
 * runs on that window and the frames from just before its last 0.17 s are kept (the model's
 * kernels need that much of what follows; the official inference trims the same amount).
 * A frame for time t is therefore ready by t + [delaySeconds]; the analyser reads that far
 * behind and dates what it finds to when it was played.
 *
 * With a [worker], the model runs there instead of on the caller's (audio) thread, and the
 * delay grows to cover how long a run takes on this phone.
 */
class HeardNotes(
    weights: BasicPitch.Weights,
    val inputRate: Int,
    val runEverySeconds: Double = 0.5,
    /** Window RMS below which the model is not run: silence normalised up is all noise. */
    private val minLevel: Double = 0.002,
    /** Note probability that counts as nothing; above it, the excess counts. */
    private val floor: Float = 0.2f,
    /**
     * Extra weight for the lowest note sounding below middle C, which usually names the root.
     * On 180 GuitarSet accompaniments, 1 gave the most right chords and keys (0, 1 and 2 tried).
     */
    private val bassBoost: Float = 1f,
    /**
     * Seconds added to each frame's date. The notes are heard about a tenth of a second early
     * (the long low-frequency kernels reach ahead); on GuitarSet the chords lined up best at 0.1.
     */
    private val dateShift: Double = 0.1,
    /** Shift the audio onto concert pitch by [tuningCents] before the model hears it. */
    private val followTuning: Boolean = true,
    private val worker: java.util.concurrent.Executor? = null,
) {
    private val model = BasicPitch(weights)
    private val resampler = StreamResampler(inputRate, BasicPitch.SAMPLE_RATE)
    private val ring = FloatArray(BasicPitch.WINDOW)
    /** Input time of each sample in [ring], in seconds. */
    private val ringTime = DoubleArray(BasicPitch.WINDOW)
    private var written = 0L
    private val runFrames = (runEverySeconds * BasicPitch.SAMPLE_RATE / BasicPitch.HOP).toInt().coerceIn(1, KEEP_END)
    private var nextRun = BasicPitch.WINDOW.toLong()
    private val notes = Array(runFrames) { FloatArray(BasicPitch.NOTES) }

    /** A window waiting for the model, with the input time of each frame it will give. */
    private class Job(val window: FloatArray, val times: DoubleArray) { var generation = 0 }
    private val free = java.util.concurrent.ArrayBlockingQueue<Job>(3).also { q ->
        repeat(3) { q.add(Job(FloatArray(BasicPitch.WINDOW), DoubleArray(runFrames))) }
    }
    @Volatile private var generation = 0

    private val baseDelay = (runFrames + BasicPitch.FRAMES - KEEP_END) * BasicPitch.HOP.toDouble() / BasicPitch.SAMPLE_RATE * 1.03 + 0.01

    /** Audio time, in seconds, after which the frames for a moment are known. Only grows. */
    @Volatile var delaySeconds: Double = baseDelay + if (worker != null) 0.25 else 0.0
        private set

    /**
     * How far the music sits from concert pitch, in cents (the analyser's tuning grid). The
     * model was trained on music at concert pitch; a band 40 cents sharp would fall between
     * its keys, so the audio is shifted back before it hears it.
     */
    var tuningCents: Double
        get() = resampler.shiftCents
        set(v) { if (followTuning) resampler.shiftCents = v }

    /** Running average cost, in milliseconds per second of audio, for the on-screen diagnostics. */
    @Volatile var millisPerSecond = 0.0; private set
    /** Windows the worker could not keep up with and skipped. */
    @Volatile var skipped = 0; private set

    // Emitted frames: time (seconds of input audio) and pitch-class energy, in a ring.
    private val lock = Any()
    private val times = DoubleArray(HISTORY) { Double.NEGATIVE_INFINITY }
    private val chroma = Array(HISTORY) { FloatArray(12) }
    private var head = 0

    fun reset() {
        resampler.reset()
        ring.fill(0f)
        ringTime.fill(0.0)
        written = 0L
        nextRun = BasicPitch.WINDOW.toLong()
        synchronized(lock) {
            generation++
            times.fill(Double.NEGATIVE_INFINITY)
            head = 0
        }
    }

    fun push(samples: FloatArray, offset: Int, count: Int) {
        resampler.push(samples, offset, count) { v ->
            val at = (written % ring.size).toInt()
            ring[at] = v
            ringTime[at] = resampler.lastTime
            written++
            if (written >= nextRun) { schedule(); nextRun += runFrames * BasicPitch.HOP }
        }
    }

    private fun schedule() {
        val job = free.poll() ?: run { skipped++; return }
        // Oldest first: the ring's write position is the oldest sample.
        val pos = (written % ring.size).toInt()
        System.arraycopy(ring, pos, job.window, 0, ring.size - pos)
        System.arraycopy(ring, 0, job.window, ring.size - pos, pos)
        val from = KEEP_END - runFrames
        // Each frame's centre, in input time (the resampler may be stretching time to shift pitch).
        for (k in 0 until runFrames) job.times[k] = ringTime[((pos + (from + k) * BasicPitch.HOP) % ring.size)] + dateShift
        job.generation = generation
        if (worker == null) compute(job) else worker.execute { compute(job) }
    }

    private fun compute(job: Job) {
        val started = System.nanoTime()
        val window = job.window
        val from = KEEP_END - runFrames
        var sq = 0.0
        val tail = (BasicPitch.FRAMES - from) * BasicPitch.HOP
        for (i in window.size - tail until window.size) sq += window[i].toDouble() * window[i]
        val loud = sqrt(sq / tail) >= minLevel
        if (loud) model.notes(window, from, KEEP_END, notes) else for (row in notes) row.fill(0f)
        synchronized(lock) {
            if (job.generation == generation) for (k in 0 until runFrames) {
                val c = chroma[head]
                c.fill(0f)
                val row = notes[k]
                for (n in 0 until BasicPitch.NOTES) {
                    val p = row[n] - floor
                    if (p > 0f) c[(n + BasicPitch.LOWEST_MIDI) % 12] += p / (1f - floor)
                }
                if (bassBoost > 0f) {
                    for (n in 0 until 60 - BasicPitch.LOWEST_MIDI) if (row[n] > 0.5f) { c[(n + BasicPitch.LOWEST_MIDI) % 12] += bassBoost * row[n]; break }
                }
                times[head] = job.times[k]
                head = (head + 1) % HISTORY
            }
        }
        free.offer(job)
        val seconds = (System.nanoTime() - started) / 1e9
        if (loud) {
            millisPerSecond = if (millisPerSecond == 0.0) seconds * 1000 / runEverySeconds else 0.8 * millisPerSecond + 0.2 * seconds * 1000 / runEverySeconds
            // On a worker, results arrive a run's time late: read further behind if needed.
            if (worker != null) delaySeconds = maxOf(delaySeconds, minOf(baseDelay + 1.5 * seconds + 0.05, baseDelay + 1.5))
        }
    }

    /**
     * Adds the pitch-class energy of the frames played between [fromSeconds] and [toSeconds]
     * to [out], averaged per frame. Returns how many frames there were.
     */
    fun pitchClasses(fromSeconds: Double, toSeconds: Double, out: FloatArray): Int {
        var n = 0
        val sum = FloatArray(12)
        synchronized(lock) {
            for (i in 0 until HISTORY) {
                val t = times[i]
                if (t >= fromSeconds && t < toSeconds) { n++; val c = chroma[i]; for (pc in 0 until 12) sum[pc] += c[pc] }
            }
        }
        if (n > 0) for (pc in 0 until 12) out[pc] += sum[pc] / n
        return n
    }

    companion object {
        /** Frames at the end of each window that are not used: the kernels' reach, as the official inference trims. */
        const val KEEP_END = BasicPitch.FRAMES - 15
        private const val HISTORY = 512
    }
}

/**
 * Streaming windowed-sinc resampler (44.1 or 48 kHz down to 22,050 Hz) that can also shift
 * pitch a little: with [shiftCents] set, input time advances 2^(-cents/1200) as fast as output
 * time, so music that many cents sharp comes out at concert pitch. The filter is tabulated for
 * [PHASES] fractional positions, so nothing is computed per tap but the products.
 */
class StreamResampler(private val inRate: Int, private val outRate: Int, cutoffHz: Double = 9500.0) {
    init { require(inRate >= outRate) { "only downsampling" } }
    private val step = inRate.toDouble() / outRate
    private val halfTaps = (32 * step).toInt()
    private val taps = 2 * halfTaps + 1
    private val table = Array(PHASES + 1) { p ->
        val frac = p.toDouble() / PHASES
        val fc = cutoffHz / inRate
        FloatArray(taps) { k ->
            val t = (k - halfTaps) - frac
            val u = t / (halfTaps + 1)
            val window = 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u)
            val x = 2.0 * fc * t
            val sinc = if (kotlin.math.abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
            (2.0 * fc * sinc * window).toFloat()
        }
    }
    /** Cents to lower the pitch by; positive for music that sits sharp. */
    var shiftCents: Double = 0.0
        set(v) { field = v; advance = step * Math.pow(2.0, -v / 1200.0) }
    private var advance = step
    private val history = FloatArray(taps + 8192)
    private var historyStart = 0L   // input index of history[0]
    private var historyLength = 0
    /** Input position (in input samples) of the next output sample. */
    private var position = 0.0
    /** Input time, in seconds, of the sample [next] last returned. */
    var lastTime = 0.0; private set

    fun reset() { historyStart = 0L; historyLength = 0; position = 0.0; lastTime = 0.0; history.fill(0f) }

    inline fun push(samples: FloatArray, offset: Int, count: Int, emit: (Float) -> Unit) {
        var i = 0
        while (i < count) {
            val n = append(samples, offset + i, count - i)
            i += n
            while (true) {
                val v = next()
                if (v.isNaN()) break
                emit(v)
            }
        }
    }

    /** Appends as many samples as fit; returns how many. */
    fun append(samples: FloatArray, offset: Int, count: Int): Int {
        // Drop what no future output can reach.
        val keepFrom = kotlin.math.floor(position).toLong() - halfTaps
        val drop = (keepFrom - historyStart).coerceIn(0L, historyLength.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(history, drop, history, 0, historyLength - drop)
            historyLength -= drop; historyStart += drop
        }
        val n = minOf(count, history.size - historyLength)
        System.arraycopy(samples, offset, history, historyLength, n)
        historyLength += n
        return n
    }

    /** The next output sample, or NaN when more input is needed. */
    fun next(): Float {
        val base = kotlin.math.floor(position).toLong()
        val first = base - halfTaps
        if (first + taps > historyStart + historyLength) return Float.NaN
        val w = table[((position - base) * PHASES).roundToInt()]
        var s = 0f
        for (k in 0 until taps) {
            val idx = first + k - historyStart
            if (idx >= 0) s += history[idx.toInt()] * w[k]
        }
        lastTime = position / inRate
        position += advance
        return s
    }

    companion object { const val PHASES = 256 }
}
