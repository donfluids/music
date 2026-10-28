package com.donfluids.music.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Basic Pitch (Bittner et al., ICASSP 2022; Spotify, Apache 2.0): a small network that hears
 * every note being played, several at once, on any instrument. Only its note path is run here:
 * a constant-Q transform of 2 seconds of 22,050 Hz audio, a log scale normalised over the
 * window, harmonic stacking, then four convolutions to 88 piano-key probabilities per frame
 * of 256 samples (11.6 ms). The onset head is left out; chords and keys need only the notes.
 *
 * Plain Kotlin, checked against the released ONNX model. The layers are run only for the
 * frames asked for, plus the nine on each side their kernels reach.
 */
class BasicPitch(private val w: Weights) {

    /**
     * Note probabilities for frames [from] until [to] of the window [x] ([WINDOW] samples at
     * [SAMPLE_RATE]), written to [out] as rows of [NOTES], lowest key (A0) first.
     */
    fun notes(x: FloatArray, from: Int, to: Int, out: Array<FloatArray>) {
        require(x.size >= WINDOW && from in 0..to && to <= FRAMES)
        cqt(x)
        normalise()
        val lo = from; val hi = to
        stack(lo - 9, hi + 9)
        contour1(lo - 8, hi + 8)
        contour2(lo - 6, hi + 6)
        note1(lo - 3, hi + 3)
        note2(lo, hi, out)
    }

    // ---- constant-Q transform: nine octaves of 36 bins, each octave half the rate of the last --

    private val levels = Array(OCTAVES) { o -> FloatArray(if (o == 0) 0 else LEVEL_LENGTH[o]) }
    private val frame = FloatArray(KERNEL)
    /** Log-scaled, normalised, batch-normed CQT: [frame][bin]. */
    private val h = Array(FRAMES) { FloatArray(BINS) }

    private fun cqt(x: FloatArray) {
        var sig = x
        var len = WINDOW
        var hop = HOP
        for (o in 0 until OCTAVES) {
            if (o > 0) {
                val out = levels[o]
                downsample(sig, len, out)
                sig = out; len = out.size; hop /= 2
            }
            val firstBin = (OCTAVES - 1 - o) * BINS_PER_OCTAVE - DROPPED_BINS
            for (t in 0 until FRAMES) {
                val start = t * hop - KERNEL / 2
                for (j in 0 until KERNEL) {
                    var i = start + j
                    if (i < 0) i = -i else if (i >= len) i = 2 * len - 2 - i
                    frame[j] = sig[i]
                }
                val row = h[t]
                for (k in 0 until BINS_PER_OCTAVE) {
                    val b = firstBin + k
                    if (b < 0) continue
                    val off = k * KERNEL
                    var re = 0f; var im = 0f
                    for (j in 0 until KERNEL) { val v = frame[j]; re += w.kr[off + j] * v; im += w.ki[off + j] * v }
                    row[b] = sqrt(re * re + im * im) * w.sqrtLength[b]
                }
            }
        }
    }

    /** Low-pass and halve the rate, zero beyond the ends, as the reference does. */
    private fun downsample(sig: FloatArray, len: Int, out: FloatArray) {
        val pad = (KERNEL - 1) / 2
        for (i in out.indices) {
            val base = 2 * i - pad
            var s = 0f
            val j0 = maxOf(0, -base); val j1 = minOf(KERNEL, len - base)
            for (j in j0 until j1) s += sig[base + j] * w.lowpass[j]
            out[i] = s
        }
    }

    /** Power in dB, scaled 0 to 1 over the whole window, then the input batch norm. */
    private fun normalise() {
        var min = Float.POSITIVE_INFINITY; var max = Float.NEGATIVE_INFINITY
        for (row in h) for (b in 0 until BINS) {
            val m = row[b]
            val db = (10.0 * log10(m.toDouble() * m + 1e-10)).toFloat()
            row[b] = db
            if (db < min) min = db
            if (db > max) max = db
        }
        val range = max - min
        val scale = if (range != 0f) 1f / range else 0f
        for (row in h) for (b in 0 until BINS) row[b] = (row[b] - min) * scale * w.bnMul + w.bnAdd
    }

    // ---- network: frames are stored from the first one each layer computes ----------------

    /** Harmonic stack, zero-padded 19 bins each side for the 39-wide contour kernel: [ch][t][f + 19]. */
    private val st = Array(HARMONICS) { Array(FRAMES + 18) { FloatArray(CONTOUR_BINS + 38) } }
    private var stLo = 0; private var stHi = 0
    private val c1 = Array(8) { Array(FRAMES + 16) { FloatArray(CONTOUR_BINS + 4) } }   // padded 2 for the 5x5
    private var c1Lo = 0; private var c1Hi = 0
    private val contour = Array(FRAMES + 12) { FloatArray(CONTOUR_BINS + 4) }          // padded 2 for note1
    private var cLo = 0; private var cHi = 0
    private val n1 = Array(32) { Array(FRAMES + 6) { FloatArray(NOTES + 2) } }          // padded 1 for note2
    private var n1Lo = 0; private var n1Hi = 0
    private val acc = FloatArray(CONTOUR_BINS)

    private fun stack(from: Int, to: Int) {
        stLo = maxOf(0, from); stHi = minOf(FRAMES, to)
        for (c in 0 until HARMONICS) {
            val shift = SHIFTS[c]
            for (t in stLo until stHi) {
                val row = st[c][t - stLo]; val src = h[t]
                for (f in 0 until CONTOUR_BINS) { val b = f + shift; row[f + 19] = if (b in 0 until BINS) src[b] else 0f }
            }
        }
    }

    private fun contour1(from: Int, to: Int) {
        c1Lo = maxOf(0, from); c1Hi = minOf(FRAMES, to)
        val wt = w.contour1W
        for (o in 0 until 8) for (t in c1Lo until c1Hi) {
            acc.fill(w.contour1B[o])
            for (c in 0 until HARMONICS) for (dt in 0 until 3) {
                val tt = t + dt - 1
                if (tt < stLo || tt >= stHi) continue
                val row = st[c][tt - stLo]
                val wBase = ((o * HARMONICS + c) * 3 + dt) * 39
                for (df in 0 until 39) {
                    val k = wt[wBase + df]
                    for (f in 0 until CONTOUR_BINS) acc[f] += k * row[f + df]
                }
            }
            val out = c1[o][t - c1Lo]
            for (f in 0 until CONTOUR_BINS) out[f + 2] = if (acc[f] > 0f) acc[f] else 0f
        }
    }

    private fun contour2(from: Int, to: Int) {
        cLo = maxOf(0, from); cHi = minOf(FRAMES, to)
        val wt = w.contour2W
        for (t in cLo until cHi) {
            acc.fill(w.contour2B[0])
            for (c in 0 until 8) for (dt in 0 until 5) {
                val tt = t + dt - 2
                if (tt < c1Lo || tt >= c1Hi) continue
                val row = c1[c][tt - c1Lo]
                val wBase = (c * 5 + dt) * 5
                for (df in 0 until 5) {
                    val k = wt[wBase + df]
                    for (f in 0 until CONTOUR_BINS) acc[f] += k * row[f + df]
                }
            }
            val out = contour[t - cLo]
            for (f in 0 until CONTOUR_BINS) out[f + 2] = sigmoid(acc[f])
        }
    }

    private fun note1(from: Int, to: Int) {
        n1Lo = maxOf(0, from); n1Hi = minOf(FRAMES, to)
        val wt = w.note1W
        for (o in 0 until 32) for (t in n1Lo until n1Hi) {
            val out = n1[o][t - n1Lo]
            for (g in 0 until NOTES) {
                var s = w.note1B[o]
                for (dt in 0 until 7) {
                    val tt = t + dt - 3
                    if (tt < cLo || tt >= cHi) continue
                    val row = contour[tt - cLo]
                    val wBase = (o * 7 + dt) * 7
                    val f0 = 3 * g
                    for (df in 0 until 7) s += wt[wBase + df] * row[f0 + df]
                }
                out[g + 1] = if (s > 0f) s else 0f
            }
        }
    }

    private fun note2(from: Int, to: Int, out: Array<FloatArray>) {
        val wt = w.note2W
        for (t in from until to) {
            val dst = out[t - from]
            for (g in 0 until NOTES) {
                var s = w.note2B[0]
                for (c in 0 until 32) for (dt in 0 until 7) {
                    val tt = t + dt - 3
                    if (tt < n1Lo || tt >= n1Hi) continue
                    val row = n1[c][tt - n1Lo]
                    val wBase = (c * 7 + dt) * 3
                    s += wt[wBase] * row[g] + wt[wBase + 1] * row[g + 1] + wt[wBase + 2] * row[g + 2]
                }
                dst[g] = sigmoid(s)
            }
        }
    }

    /** The raw constant-Q magnitudes of one frame, before the log: for tests against the reference. */
    internal fun cqtFrame(x: FloatArray, t: Int): FloatArray {
        cqt(x)
        return h[t].copyOf()
    }

    /** Contour probabilities of one frame: for tests against the reference. */
    internal fun contourFrame(x: FloatArray, t: Int): FloatArray {
        cqt(x); normalise(); stack(t - 3, t + 4); contour1(t - 2, t + 3); contour2(t, t + 1)
        return contour[t - cLo].copyOfRange(2, 2 + CONTOUR_BINS)
    }

    class Weights(
        val kr: FloatArray, val ki: FloatArray, val lowpass: FloatArray, val sqrtLength: FloatArray,
        val bnMul: Float, val bnAdd: Float,
        val contour1W: FloatArray, val contour1B: FloatArray,
        val contour2W: FloatArray, val contour2B: FloatArray,
        val note1W: FloatArray, val note1B: FloatArray,
        val note2W: FloatArray, val note2B: FloatArray,
    ) {
        companion object {
            /** The "BPCH" v1 file: arrays of little-endian float32, each preceded by its length. */
            fun read(bytes: ByteArray): Weights {
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { b.get(it) }
                require(String(magic) == "BPCH" && b.int == 1) { "not a Basic Pitch weights file" }
                val count = b.int
                val a = List(count) { FloatArray(b.int) { b.float } }
                val sizes = listOf(BINS_PER_OCTAVE * KERNEL, BINS_PER_OCTAVE * KERNEL, KERNEL, BINS, 2, 8 * 8 * 3 * 39, 8, 8 * 25, 1, 32 * 49, 32, 32 * 21, 1)
                require(a.map { it.size } == sizes) { "unexpected layer sizes" }
                return Weights(a[0], a[1], a[2], a[3], a[4][0], a[4][1], a[5], a[6], a[7], a[8], a[9], a[10], a[11], a[12])
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 22050
        const val HOP = 256
        /** Two seconds less one hop, as the model was trained. */
        const val WINDOW = 2 * SAMPLE_RATE - HOP
        const val FRAMES = 172
        const val NOTES = 88
        /** MIDI number of the lowest key. */
        const val LOWEST_MIDI = 21
        private const val KERNEL = 256
        private const val OCTAVES = 9
        private const val BINS_PER_OCTAVE = 36
        private const val BINS = 309
        private const val DROPPED_BINS = OCTAVES * BINS_PER_OCTAVE - BINS
        private const val CONTOUR_BINS = 264
        private const val HARMONICS = 8
        /** Bins up (or down, for the half harmonic) to harmonics 0.5, 1, 2 … 7 at three bins a semitone. */
        private val SHIFTS = intArrayOf(-36, 0, 36, 57, 72, 84, 93, 101)
        private val LEVEL_LENGTH = IntArray(OCTAVES).also { l -> l[0] = WINDOW; for (o in 1 until OCTAVES) l[o] = (l[o - 1] - 2) / 2 + 1 }

        private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))
    }
}
