package com.donfluids.music.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A monophonic pitch estimator: the fundamental of the voice at the end of a buffer, or nothing. */
interface PitchEstimator {
    /** Analyses the audio ending at [end] (exclusive) in [buffer]. Returns true if voiced. */
    fun process(buffer: FloatArray, end: Int): Boolean
    val hz: Double
    /** How sure the estimate is, 0..1. */
    val clarity: Double
    /** Fractional MIDI note of [hz] against A4 = 440 Hz. */
    val midi: Double get() = 69.0 + 12.0 * log2(hz / 440.0)
    /** Samples that must precede the analysis point. */
    val samplesNeeded: Int
}

/**
 * CREPE pitch estimation (Kim, Salamon, Li and Bello, ICASSP 2018; MIT licence), "tiny" size,
 * run in plain Kotlin from the authors' weights so the same code runs on the phone and in tests.
 *
 * The newest 64 ms are resampled to 16 kHz, normalised, and passed through six convolutional
 * layers to 360 activations, one per 20 cents from C1 to B7. The pitch is the activation-weighted
 * average of the bins around the strongest, and the strongest activation is the confidence.
 *
 * On 40 solo singers (Vocadito) it found the right note in 95% of sung frames against 94% for
 * the autocorrelation tracker, 93% against 87% with noise at 10 dB SNR, and made a third as many
 * octave errors with a band behind the singer. Its estimate is a few cents coarser, so when
 * [refine] is on, the autocorrelation peak nearest its answer sets the exact frequency; and a
 * frame counts as voiced only when that peak shows real periodicity, which keeps a chord on a
 * keyboard from being read as a tune.
 *
 * The two heavy layers (a 512-tap first layer and a 128-channel second) are computed by FFT
 * convolution; the rest directly. Weights: see [Weights] for the file layout.
 */
class CrepePitch(
    private val weights: Weights,
    private val sampleRate: Int,
    private val minConfidence: Double = 0.5,
    private val minLevel: Double = 0.004,
    private val refine: Boolean = true,
    /** Voiced only when the sound is also this periodic at CREPE's pitch (0 turns the check off). */
    private val minPeriodicity: Double = 0.5,
) : PitchEstimator {

    override var hz: Double = 0.0; private set
    override var clarity: Double = 0.0; private set

    /** The 360 activations of the last voiced or unvoiced frame that passed the level gate. */
    val activations = FloatArray(BINS)

    /** Milliseconds the last [process] call took, for the on-screen diagnostics. */
    var lastMillis: Double = 0.0; private set

    // ---- input: the level gate and the resampler -------------------------------------------

    private val levelFrame = 2048
    private val levelWindow = Fft.hannWindow(levelFrame)
    private val ratio = sampleRate / 16000.0
    private val cutoff = 7400.0 / sampleRate                 // cycles per input sample
    private val halfTaps = (16 * ratio).toInt() + 1
    // Output sample j sits (FRAME - 1 - j) * ratio input samples before the newest one, plus a
    // delay of halfTaps + 1 so its filter never needs a sample that has not arrived. Its taps
    // (offsets from the newest sample, all <= 0) and their weights never change.
    private val tapStart = IntArray(FRAME)
    private val tapWeights = Array(FRAME) { j ->
        val back = (FRAME - 1 - j) * ratio + halfTaps + 1
        val first = floor(-back - halfTaps).toInt()
        tapStart[j] = first
        FloatArray(2 * halfTaps + 2) { k ->
            val t = (first + k) + back                       // input offset from the ideal point
            val u = t / (halfTaps + 1)
            if (kotlin.math.abs(u) >= 1.0) 0f else {
                val window = 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u)
                val x = 2.0 * cutoff * t
                val sinc = if (kotlin.math.abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                (2.0 * cutoff * sinc * window).toFloat()
            }
        }
    }
    override val samplesNeeded: Int = maxOf(levelFrame, ((FRAME - 1) * ratio).toInt() + 2 * halfTaps + 6, 2048 + 1500)

    private val frame = FloatArray(FRAME)

    // ---- network buffers ------------------------------------------------------------------

    private val l1 = weights.layers[0]
    private val l2 = weights.layers[1]
    private val fft1 = Fft(N1)
    private val re1 = FloatArray(N1); private val im1 = FloatArray(N1)
    private val xRe1 = FloatArray(N1); private val xIm1 = FloatArray(N1)
    // conj(FFT(kernel)) of every first-layer filter, so a product is a correlation.
    private val k1Re = Array(l1.cout) { FloatArray(N1) }
    private val k1Im = Array(l1.cout) { FloatArray(N1) }
    private val fft2 = Fft(N2)
    private val half2 = N2 / 2 + 1
    private val x2Re = Array(l2.cin) { FloatArray(half2) }
    private val x2Im = Array(l2.cin) { FloatArray(half2) }
    private val k2Re = Array(l2.cout) { Array(l2.cin) { FloatArray(half2) } }
    private val k2Im = Array(l2.cout) { Array(l2.cin) { FloatArray(half2) } }
    private val re2 = FloatArray(N2); private val im2 = FloatArray(N2)
    private val yRe = FloatArray(half2); private val yIm = FloatArray(half2)
    private val yRe2 = FloatArray(half2); private val yIm2 = FloatArray(half2)

    // Activations, channel-major: [channel][position].
    private val a1 = Array(l1.cout) { FloatArray(LEN1 / 2) }
    private val acts = weights.layers.drop(1).fold(ArrayList<Array<FloatArray>>()) { list, l ->
        val len = (if (list.isEmpty()) LEN1 / 2 else list.last()[0].size) / 2
        list.also { it += Array(l.cout) { FloatArray(len) } }
    }
    private val flat = FloatArray(weights.classifierInputs)

    init {
        require(sampleRate >= 16000) { "CREPE needs at least 16 kHz audio" }
        require(l1.cin == 1 && l1.stride == 4 && l1.width <= N1 / 2) { "unexpected first layer" }
        require(l2.stride == 1 && l2.width <= N2 / 2) { "unexpected second layer" }
        val r = FloatArray(N1); val i = FloatArray(N1)
        for (c in 0 until l1.cout) {
            r.fill(0f); i.fill(0f)
            for (w in 0 until l1.width) r[w] = l1.kernel(c, 0, w)
            fft1.transform(r, i)
            for (k in 0 until N1) { k1Re[c][k] = r[k]; k1Im[c][k] = -i[k] }
        }
        val r2 = FloatArray(N2); val i2 = FloatArray(N2)
        for (d in 0 until l2.cout) for (c in 0 until l2.cin) {
            r2.fill(0f); i2.fill(0f)
            for (w in 0 until l2.width) r2[w] = l2.kernel(d, c, w)
            fft2.transform(r2, i2)
            for (k in 0 until half2) { k2Re[d][c][k] = r2[k]; k2Im[d][c][k] = -i2[k] }
        }
    }

    override fun process(buffer: FloatArray, end: Int): Boolean {
        val started = System.nanoTime()
        hz = 0.0
        clarity = 0.0
        // The same loudness gate as the autocorrelation tracker, so silence costs nothing.
        var energy = 0.0
        val gateStart = end - levelFrame
        for (k in 0 until levelFrame) { val v = buffer[gateStart + k] * levelWindow[k]; energy += v.toDouble() * v }
        if (sqrt(energy / levelFrame) < minLevel) { lastMillis = (System.nanoTime() - started) / 1e6; return false }

        resample(buffer, end)
        normalise()
        forward()
        decode()
        var voiced = clarity >= minConfidence
        if (voiced && (refine || minPeriodicity > 0.0)) {
            val refined = refineWithAutocorrelation(buffer, end, hz)
            if (refine) hz = refined
            if (periodicity < minPeriodicity) voiced = false
        }
        lastMillis = (System.nanoTime() - started) / 1e6
        return voiced
    }

    private fun resample(buffer: FloatArray, end: Int) {
        val newest = end - 1
        for (j in 0 until FRAME) {
            val w = tapWeights[j]
            val base = newest + tapStart[j]
            var s = 0f
            for (k in w.indices) {
                val idx = base + k
                if (idx in 0..newest) s += buffer[idx] * w[k]
            }
            frame[j] = s
        }
    }

    /** The network alone, on a 16 kHz frame: for tests against the reference implementation. */
    internal fun activationsOf(frame16k: FloatArray): FloatArray {
        System.arraycopy(frame16k, 0, frame, 0, FRAME)
        normalise()
        forward()
        decode()
        return activations.copyOf()
    }

    private fun normalise() {
        var mean = 0.0
        for (v in frame) mean += v
        mean /= FRAME
        var variance = 0.0
        for (v in frame) variance += (v - mean) * (v - mean)
        val sd = max(sqrt(variance / FRAME), 1e-8)
        for (k in 0 until FRAME) frame[k] = ((frame[k] - mean) / sd).toFloat()
    }

    private fun forward() {
        layer1()
        layer2()
        var input = acts[0]
        for (li in 2 until weights.layers.size) {
            val out = acts[li - 1]
            direct(weights.layers[li], input, out)
            input = out
        }
        // Time-major flattening, as in the Keras model: index = position * channels + channel.
        val channels = input.size
        val positions = input[0].size
        for (p in 0 until positions) for (c in 0 until channels) flat[p * channels + c] = input[c][p]
        val inputs = weights.classifierInputs
        for (o in 0 until BINS) {
            var z = weights.classifierBias[o]
            val row = o * inputs
            for (k in 0 until inputs) z += weights.classifier[row + k] * flat[k]
            activations[o] = (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()
        }
    }

    /** 512-tap, stride-4 "same" convolution by FFT correlation; two filters per inverse FFT. */
    private fun layer1() {
        val padBefore = ((LEN1 - 1) * l1.stride + l1.width - FRAME) / 2
        xRe1.fill(0f); xIm1.fill(0f)
        for (k in 0 until FRAME) xRe1[padBefore + k] = frame[k]
        fft1.transform(xRe1, xIm1)
        var c = 0
        while (c < l1.cout) {
            val c2 = c + 1
            val pair = c2 < l1.cout
            // Z = X·K1 + i·X·K2, so the real part of its inverse is filter 1 and the imaginary filter 2.
            for (k in 0 until N1) {
                val xr = xRe1[k]; val xi = xIm1[k]
                val ar = xr * k1Re[c][k] - xi * k1Im[c][k]
                val ai = xr * k1Im[c][k] + xi * k1Re[c][k]
                var br = 0f; var bi = 0f
                if (pair) { br = xr * k1Re[c2][k] - xi * k1Im[c2][k]; bi = xr * k1Im[c2][k] + xi * k1Re[c2][k] }
                // conj(Z) for the inverse-by-forward trick.
                re1[k] = ar - bi
                im1[k] = -(ai + br)
            }
            fft1.transform(re1, im1)
            val scale = 1f / N1
            emitPooled(l1, c, LEN1) { o -> re1[o * l1.stride] * scale }
            if (pair) emitPooled(l1, c2, LEN1) { o -> -im1[o * l1.stride] * scale }
            c += 2
        }
    }

    /** relu(conv + bias) * scale + shift, then max-pool by two, into [a1] (first layer only). */
    private inline fun emitPooled(l: Layer, channel: Int, length: Int, value: (Int) -> Float) {
        val out = a1[channel]
        val bias = l.bias[channel]; val scale = l.scale[channel]; val shift = l.shift[channel]
        for (p in 0 until length / 2) {
            val a = max(value(2 * p) + bias, 0f) * scale + shift
            val b = max(value(2 * p + 1) + bias, 0f) * scale + shift
            out[p] = max(a, b)
        }
    }

    /** 128-channel, 64-tap "same" convolution: each input channel transformed once. */
    private fun layer2() {
        val len = a1[0].size
        val total = l2.width - 1
        val before = total / 2
        for (c in 0 until l2.cin) {
            re2.fill(0f); im2.fill(0f)
            for (p in 0 until len) re2[before + p] = a1[c][p]
            fft2.transform(re2, im2)
            System.arraycopy(re2, 0, x2Re[c], 0, half2)
            System.arraycopy(im2, 0, x2Im[c], 0, half2)
        }
        val out = acts[0]
        val scaleInv = 1f / N2
        var d = 0
        while (d < l2.cout) {
            accumulate(d, yRe, yIm)
            val d2 = d + 1
            val pair = d2 < l2.cout
            if (pair) accumulate(d2, yRe2, yIm2) else { yRe2.fill(0f); yIm2.fill(0f) }
            // Full Hermitian spectra of both outputs, packed as Y1 + i·Y2, conjugated for the inverse.
            for (k in 0 until N2) {
                val m = if (k < half2) k else N2 - k
                val sign = if (k < half2) 1f else -1f
                val ar = yRe[m]; val ai = sign * yIm[m]
                val br = yRe2[m]; val bi = sign * yIm2[m]
                re2[k] = ar - bi
                im2[k] = -(ai + br)
            }
            fft2.transform(re2, im2)
            pool(l2, d, len) { o -> re2[o] * scaleInv }
            if (pair) pool(l2, d2, len) { o -> -im2[o] * scaleInv }
            d += 2
        }
    }

    private fun accumulate(d: Int, outRe: FloatArray, outIm: FloatArray) {
        outRe.fill(0f); outIm.fill(0f)
        for (c in 0 until l2.cin) {
            val xr = x2Re[c]; val xi = x2Im[c]; val kr = k2Re[d][c]; val ki = k2Im[d][c]
            for (k in 0 until half2) {
                outRe[k] += xr[k] * kr[k] - xi[k] * ki[k]
                outIm[k] += xr[k] * ki[k] + xi[k] * kr[k]
            }
        }
    }

    private inline fun pool(l: Layer, channel: Int, length: Int, value: (Int) -> Float) {
        val out = acts[0][channel]
        val bias = l.bias[channel]; val scale = l.scale[channel]; val shift = l.shift[channel]
        for (p in 0 until length / 2) {
            val a = max(value(2 * p) + bias, 0f) * scale + shift
            val b = max(value(2 * p + 1) + bias, 0f) * scale + shift
            out[p] = max(a, b)
        }
    }

    /** A small "same" convolution done directly, then the activation and pooling. */
    private fun direct(l: Layer, input: Array<FloatArray>, out: Array<FloatArray>) {
        val len = input[0].size
        val before = (l.width - 1) / 2
        val conv = FloatArray(len)
        for (d in 0 until l.cout) {
            conv.fill(0f)
            for (c in 0 until l.cin) {
                val x = input[c]
                val base = (d * l.cin + c) * l.width
                for (o in 0 until len) {
                    var s = 0f
                    val from = max(0, before - o)
                    val to = min(l.width, len + before - o)
                    for (w in from until to) s += x[o + w - before] * l.weights[base + w]
                    conv[o] += s
                }
            }
            val bias = l.bias[d]; val scale = l.scale[d]; val shift = l.shift[d]
            val dst = out[d]
            for (p in 0 until len / 2) {
                val a = max(conv[2 * p] + bias, 0f) * scale + shift
                val b = max(conv[2 * p + 1] + bias, 0f) * scale + shift
                dst[p] = max(a, b)
            }
        }
    }

    private fun decode() {
        var best = 0
        for (k in 1 until BINS) if (activations[k] > activations[best]) best = k
        val from = max(0, best - 4); val to = min(BINS, best + 5)
        var sum = 0.0; var weight = 0.0
        for (k in from until to) { sum += activations[k] * centsOf(k); weight += activations[k] }
        val cents = sum / weight
        hz = 10.0 * 2.0.pow(cents / 1200.0)
        clarity = activations[best].toDouble()
    }

    // ---- refinement and the periodicity check ---------------------------------------------

    private val refineFrame = 2048
    private val refineWindow = Fft.hannWindow(refineFrame)
    private val rx = DoubleArray(refineFrame)
    /** The Hann window's own autocorrelation, normalised: what a taper alone does to each lag. */
    private val windowAcf = DoubleArray(refineFrame / 2 + 2).also { rw ->
        for (lag in rw.indices) { var s = 0.0; for (i in 0 until refineFrame - lag) s += refineWindow[i].toDouble() * refineWindow[i + lag]; rw[lag] = s }
        val zero = rw[0]
        for (lag in rw.indices) rw[lag] /= zero
    }
    /** Input samples from the newest one back to the centre of CREPE's window. */
    private val crepeCentreBack = ((FRAME - 1) / 2.0 * ratio + halfTaps + 1).toInt()

    /** How periodic the audio is at the last voiced pitch, 0..1 (the autocorrelation peak). */
    var periodicity: Double = 0.0; private set

    /**
     * CREPE picks the note; the autocorrelation peak within 60 cents of its period sets the
     * exact frequency and says how periodic the sound really is there. A chord has no single
     * period, so it scores low even where CREPE confidently names one of its notes.
     *
     * The frame is Hann-windowed and centred where CREPE's window is centred, and its
     * autocorrelation is divided by the window's own (Boersma 1993): without that, the taper
     * pulls long lags sharp, 15 cents at 87 Hz.
     */
    private fun refineWithAutocorrelation(buffer: FloatArray, end: Int, estimate: Double): Double {
        periodicity = 0.0
        val start = end - crepeCentreBack - refineFrame / 2
        if (start < 0) return estimate
        var mean = 0.0
        for (i in 0 until refineFrame) mean += buffer[start + i]
        mean /= refineFrame
        for (i in 0 until refineFrame) rx[i] = (buffer[start + i] - mean) * refineWindow[i]
        var r0 = 0.0
        for (v in rx) r0 += v * v
        if (r0 <= 0.0) return estimate
        val target = sampleRate / estimate
        val lo = max(2, floor(target * 2.0.pow(-60.0 / 1200)).toInt())
        val hi = min(refineFrame / 2 - 1, kotlin.math.ceil(target * 2.0.pow(60.0 / 1200)).toInt())
        if (hi <= lo) return estimate
        fun acf(lag: Int): Double {
            var s = 0.0
            for (i in 0 until refineFrame - lag) s += rx[i] * rx[i + lag]
            return s / r0 / windowAcf[lag]
        }
        val values = DoubleArray(hi - lo + 3) { acf(lo - 1 + it) }   // lags lo-1 .. hi+1
        var best = 1
        for (k in 2..(hi - lo + 1)) if (values[k] > values[best]) best = k
        val b = values[best]
        periodicity = b.coerceIn(0.0, 1.0)
        if (b < 0.5 || b < values[best - 1] || b < values[best + 1]) return estimate
        val a = values[best - 1]; val c = values[best + 1]
        val den = a - 2 * b + c
        val delta = if (den != 0.0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
        return sampleRate / (lo - 1 + best + delta)
    }

    // ---- weights ----------------------------------------------------------------------------

    class Layer(val width: Int, val cin: Int, val cout: Int, val stride: Int, val weights: FloatArray, val bias: FloatArray, val scale: FloatArray, val shift: FloatArray) {
        fun kernel(out: Int, inp: Int, w: Int): Float = weights[(out * cin + inp) * width + w]
    }

    /**
     * The weights file: "CRPE", u16 version, u16 layer count; per layer u16 width, cin, cout,
     * stride, then half-precision kernel[cout][cin][width], bias, scale, shift (batch
     * normalisation folded: relu(conv + bias) * scale + shift); then u16 inputs, outputs and the
     * classifier kernel[outputs][inputs] and bias. Little-endian throughout.
     */
    class Weights(val layers: List<Layer>, val classifierInputs: Int, val classifier: FloatArray, val classifierBias: FloatArray) {
        companion object {
            fun read(bytes: ByteArray): Weights {
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { b.get(it) }
                require(String(magic, Charsets.US_ASCII) == "CRPE") { "not a CREPE weights file" }
                require(b.short.toInt() == 1) { "unsupported CREPE weights version" }
                val count = b.short.toInt()
                fun halves(n: Int) = FloatArray(n) { halfToFloat(b.short) }
                val layers = List(count) {
                    val width = b.short.toInt(); val cin = b.short.toInt(); val cout = b.short.toInt(); val stride = b.short.toInt()
                    Layer(width, cin, cout, stride, halves(cout * cin * width), halves(cout), halves(cout), halves(cout))
                }
                val inputs = b.short.toInt(); val outputs = b.short.toInt()
                require(outputs == BINS) { "expected $BINS pitch bins, got $outputs" }
                return Weights(layers, inputs, halves(outputs * inputs), halves(outputs))
            }

            /** IEEE 754 half to float (Android's Half is not available in JVM tests). */
            fun halfToFloat(h: Short): Float {
                val bits = h.toInt() and 0xFFFF
                val sign = bits ushr 15
                val exponent = (bits ushr 10) and 0x1F
                val mantissa = bits and 0x3FF
                val out = when {
                    exponent == 0 -> if (mantissa == 0) sign shl 31 else {
                        // Subnormal: normalise it.
                        var e = -1; var m = mantissa
                        do { e++; m = m shl 1 } while (m and 0x400 == 0)
                        (sign shl 31) or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
                    }
                    exponent == 31 -> (sign shl 31) or (0xFF shl 23) or (mantissa shl 13)
                    else -> (sign shl 31) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
                }
                return Float.fromBits(out)
            }
        }
    }

    companion object {
        const val FRAME = 1024
        const val BINS = 360
        private const val LEN1 = 256          // first-layer outputs: 1024 / stride 4
        private const val N1 = 2048           // FFT size for the first layer
        private const val N2 = 256            // FFT size for the second layer
        fun centsOf(bin: Int): Double = 1997.3794084376191 + 7180.0 * bin / (BINS - 1)
    }
}
