package com.donfluids.music.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Beat This! (Foscarin, Schlüter, Widmer, ISMIR 2024; CPJKU, MIT): beat and downbeat logits for
 * every 20 ms frame of a fixed window of log-mel frames. Plain Kotlin, FP32, no ML library: the
 * same forward pass as tools/beatthis/bt_export.py, with weights from a flat "BTHS" file.
 *
 * Activations of the front end are stored token-major as [band][frame][channel], so a
 * time-attention sequence is contiguous and a frequency-attention sequence is strided by
 * [frames]. Every linear layer, the convolutions and the projection to the transformer use one
 * kernel ([mm]): rows of a source matrix times a weight matrix stored as one FloatArray per
 * input row, added to rows of a destination.
 *
 * Speed notes (HotSpot C2; ART's vectoriser is assumed to be similar or weaker): inner loops
 * index every array from zero with the loop variable only (`y[o] += a * w[o]`), because an
 * invariant offset inside the index (`y[d + o]`) stops C2 from vectorising them. Hence weight
 * rows as separate arrays, a zero-based accumulator block that is copied in and out, scores
 * stored transposed (keys x queries) so softmax and both attention products run along the
 * long query axis, and exp/GELU written as branch-free polynomials that vectorise.
 *
 * [threads] > 1 splits rows (linear layers) or sequences x heads (attention) over a pool.
 * This file is a measurement prototype (scratchpad), not app code.
 */
class BeatThis(private val w: Weights, val frames: Int = 412, threads: Int = 1) {

    private val par = Par(threads)
    private val t = frames
    private val big = 1024 * t                     // tokens x channels of every front-end stage
    private var x = FloatArray(big)
    private var y = FloatArray(big)
    private val xn = FloatArray(big)
    private val qkv = FloatArray(3 * big)
    private val hid = FloatArray(4 * big)
    private val att = FloatArray(big)
    private val gates = FloatArray(32 * t)
    private val ropeCos = HashMap<Int, FloatArray>()
    private val ropeSin = HashMap<Int, FloatArray>()
    private val scratch = Array(max(1, threads)) { Scratch(t) }

    init {
        for (n in intArrayOf(t, 32, 16, 8)) {
            val c = FloatArray(n * HEAD / 2); val s = FloatArray(n * HEAD / 2)
            for (p in 0 until n) for (i in 0 until HEAD / 2) {
                val f = (1.0 / 10000.0.pow(2.0 * i / HEAD)).toFloat()
                val a = (p.toFloat() * f).toDouble()   // torch: float32 positions x float32 freqs
                c[p * HEAD / 2 + i] = kotlin.math.cos(a).toFloat(); s[p * HEAD / 2 + i] = kotlin.math.sin(a).toFloat()
            }
            ropeCos[n] = c; ropeSin[n] = s
        }
    }

    /** [spect]: [frames] x 128 log-mel, time-major. Writes [frames] logits to each output. */
    fun logits(spect: FloatArray, beat: FloatArray, downbeat: FloatArray) {
        require(spect.size >= t * 128 && beat.size >= t && downbeat.size >= t)
        timed(5) { stem(spect) }
        var c = 32; var f = 32
        for (b in w.blocks) {
            val tokens = f * t
            attention(b.attnF, tokens, c, f, t, 1, t)      // per frame, across f bands (stride t)
            feedForward(b.ffF, tokens, c)
            attention(b.attnT, tokens, c, t, 1, t, f)      // per band, across t frames
            feedForward(b.ffT, tokens, c)
            conv(b, c, f)
            c *= 2; f /= 2
        }
        // concat "b c f t -> b t (c f)" and the projection to the transformer width
        val d = w.dim
        par.rows(t) { lo, hi -> for (r in lo until hi) System.arraycopy(w.linB, 0, y, r * d, d) }
        for (b in 0 until 4) mm(x, b * t * 256, 256, 256, w.lin, b * 256, y, 0, d, t)
        swap()
        for (l in w.layers) {
            attention(l.first, t, d, t, 1, 0, 1)
            feedForward(l.second, t, d)
        }
        rmsNorm(w.normGamma, x, xn, t, d)
        for (r in 0 until t) {
            var s0 = w.headB[0]; var s1 = w.headB[1]
            for (k in 0 until d) { val v = xn[r * d + k]; s0 += v * w.headW[2 * k]; s1 += v * w.headW[2 * k + 1] }
            downbeat[r] = s1; beat[r] = s0 + s1
        }
    }

    private fun swap() { val z = x; x = y; y = z }

    // ---- stem: BatchNorm1d over bands, 4x3 conv (stride 4 in frequency), BatchNorm2d, GELU --

    private fun stem(spect: FloatArray) {
        val bn = xn // [frame][band]
        for (r in 0 until t) for (b in 0 until 128) bn[r * 128 + b] = spect[r * 128 + b] * w.bn1Scale[b] + w.bn1Shift[b]
        par.rows(32) { lo, hi ->
            for (fo in lo until hi) for (r in 0 until t) {
                val o = (fo * t + r) * 32
                for (k in 0 until 32) x[o + k] = 0f
                for (df in 0 until 4) for (dt in 0 until 3) {
                    val tt = r + dt - 1
                    if (tt < 0 || tt >= t) continue
                    val a = bn[tt * 128 + 4 * fo + df]
                    val wo = (df * 3 + dt) * 32
                    for (k in 0 until 32) x[o + k] += a * w.stemW[wo + k]
                }
                for (k in 0 until 32) x[o + k] = gelu(x[o + k] * w.stemScale[k] + w.stemShift[k])
            }
        }
    }

    // ---- (2x3, stride 2x1) conv + BatchNorm2d (folded) + GELU: [f][t][cin] -> [f/2][t][2 cin] -

    private fun conv(b: Block, cin: Int, f: Int) {
        val cout = 2 * cin
        val fo = f / 2
        val n = fo * t
        par.rows(n) { lo, hi -> for (r in lo until hi) System.arraycopy(b.convShift, 0, y, r * cout, cout) }
        for (o in 0 until fo) for (df in 0 until 2) for (dt in 0 until 3) {
            // output rows r in [lo, hi) read input frame r + dt - 1
            val lo = if (dt == 0) 1 else 0
            val hi = if (dt == 2) t - 1 else t
            val src = ((2 * o + df) * t + lo + dt - 1) * cin
            mm(x, src, cin, cin, b.conv, (df * 3 + dt) * cin, y, (o * t + lo) * cout, cout, hi - lo)
        }
        timed(2) { par.rows(n) { lo, hi -> geluRange(y, lo * cout, hi * cout) } }
        swap()
    }

    // ---- transformer pieces ----------------------------------------------------------------

    private fun rmsNorm(g: FloatArray, src: FloatArray, dst: FloatArray, tokens: Int, c: Int) = timed(3) {
        par.rows(tokens) { lo, hi ->
            for (r in lo until hi) {
                val o = r * c
                var ss = 0f
                for (k in 0 until c) { val v = src[o + k]; ss += v * v }
                val inv = 1f / max(sqrt(ss), 1e-12f)
                for (k in 0 until c) dst[o + k] = src[o + k] * inv * g[k]
            }
        }
    }

    private fun feedForward(ff: Ff, tokens: Int, c: Int) {
        val h = 4 * c
        rmsNorm(ff.gamma, x, xn, tokens, c)
        par.rows(tokens) { lo, hi -> for (r in lo until hi) System.arraycopy(ff.b1, 0, hid, r * h, h) }
        mm(xn, 0, c, c, ff.w1, 0, hid, 0, h, tokens)
        timed(2) { par.rows(tokens) { lo, hi -> geluRange(hid, lo * h, hi * h) } }
        par.rows(tokens) { lo, hi -> for (r in lo until hi) { val o = r * c; for (k in 0 until c) x[o + k] += ff.b2[k] } }
        mm(hid, 0, h, h, ff.w2, 0, x, 0, c, tokens)
    }

    /**
     * Gated multi-head self-attention with rotary positions, added to x. Sequence s, position i
     * is token s * [seqStride] + i * [posStride]; there are [seqs] sequences of length [n].
     */
    private fun attention(a: Attn, tokens: Int, c: Int, n: Int, posStride: Int, seqStride: Int, seqs: Int) {
        val heads = c / HEAD
        rmsNorm(a.gamma, x, xn, tokens, c)
        java.util.Arrays.fill(qkv, 0, tokens * 3 * c, 0f)
        mm(xn, 0, c, c, a.qkv, 0, qkv, 0, 3 * c, tokens)
        timed(4) {
            par.rows(tokens) { lo, hi ->
                for (r in lo until hi) for (hh in 0 until heads) {
                    var s = a.gateB[hh]
                    for (k in 0 until c) s += xn[r * c + k] * a.gateW[k * heads + hh]
                    gates[r * heads + hh] = 1f / (1f + exp(-s))
                }
            }
        }
        val cosT = ropeCos.getValue(n); val sinT = ropeSin.getValue(n)
        timed(1) {
            par.items(seqs * heads) { worker, lo, hi ->
                val sc = scratch[worker]
                for (item in lo until hi) attendOne(sc, item / heads, item % heads, heads, c, n, posStride, seqStride, cosT, sinT)
            }
        }
        mm(att, 0, c, c, a.out, 0, x, 0, c, tokens)
    }

    private fun attendOne(
        sc: Scratch, s: Int, hh: Int, heads: Int, c: Int, n: Int,
        posStride: Int, seqStride: Int, cosT: FloatArray, sinT: FloatArray,
    ) {
        val qT = sc.qT; val kr = sc.kr; val vr = sc.vr; val sT = sc.sT; val m = sc.m; val l = sc.l; val oT = sc.oT
        val scale = 1f / sqrt(HEAD.toFloat())
        val c3 = 3 * c
        // gather this head's q (transposed), k and v, with the rotary rotation on q and k
        for (i in 0 until n) {
            val tok = s * seqStride + i * posStride
            val qo = tok * c3 + hh * HEAD; val ko = qo + c; val vo = qo + 2 * c
            for (j in 0 until HEAD / 2) {
                val cs = cosT[i * HEAD / 2 + j]; val sn = sinT[i * HEAD / 2 + j]
                val q0 = qkv[qo + 2 * j]; val q1 = qkv[qo + 2 * j + 1]
                qT[2 * j][i] = (q0 * cs - q1 * sn) * scale
                qT[2 * j + 1][i] = (q1 * cs + q0 * sn) * scale
                val k0 = qkv[ko + 2 * j]; val k1 = qkv[ko + 2 * j + 1]
                kr[i * HEAD + 2 * j] = k0 * cs - k1 * sn
                kr[i * HEAD + 2 * j + 1] = k1 * cs + k0 * sn
            }
            System.arraycopy(qkv, vo, vr, i * HEAD, HEAD)
        }
        // scores, transposed: sT[key][query]
        for (j in 0 until n) {
            val row = sT[j]; val kj = j * HEAD
            run {
                val a0 = kr[kj]; val a1 = kr[kj + 1]; val a2 = kr[kj + 2]; val a3 = kr[kj + 3]
                val q0 = qT[0]; val q1 = qT[1]; val q2 = qT[2]; val q3 = qT[3]
                for (i in 0 until n) row[i] = a0 * q0[i] + a1 * q1[i] + a2 * q2[i] + a3 * q3[i]
            }
            var d = 4
            while (d < HEAD) {
                val a0 = kr[kj + d]; val a1 = kr[kj + d + 1]; val a2 = kr[kj + d + 2]; val a3 = kr[kj + d + 3]
                val q0 = qT[d]; val q1 = qT[d + 1]; val q2 = qT[d + 2]; val q3 = qT[d + 3]
                for (i in 0 until n) row[i] += a0 * q0[i] + a1 * q1[i] + a2 * q2[i] + a3 * q3[i]
                d += 4
            }
        }
        // softmax over keys, for every query at once
        java.util.Arrays.fill(m, 0, n, Float.NEGATIVE_INFINITY)
        for (j in 0 until n) { val row = sT[j]; for (i in 0 until n) m[i] = max(m[i], row[i]) }
        java.util.Arrays.fill(l, 0, n, 0f)
        for (j in 0 until n) {
            val row = sT[j]
            for (i in 0 until n) { val e = expNeg(row[i] - m[i]); row[i] = e; l[i] += e }
        }
        // out^T[d][query] = sum over keys of v[key][d] * p[key][query]
        for (d in 0 until HEAD) java.util.Arrays.fill(oT[d], 0, n, 0f)
        var j = 0
        while (j + 4 <= n) {
            val e0 = sT[j]; val e1 = sT[j + 1]; val e2 = sT[j + 2]; val e3 = sT[j + 3]
            val v0 = j * HEAD; val v1 = v0 + HEAD; val v2 = v1 + HEAD; val v3 = v2 + HEAD
            for (d in 0 until HEAD) {
                val b0 = vr[v0 + d]; val b1 = vr[v1 + d]; val b2 = vr[v2 + d]; val b3 = vr[v3 + d]
                val od = oT[d]
                for (i in 0 until n) od[i] += b0 * e0[i] + b1 * e1[i] + b2 * e2[i] + b3 * e3[i]
            }
            j += 4
        }
        while (j < n) {
            val e0 = sT[j]; val v0 = j * HEAD
            for (d in 0 until HEAD) { val b0 = vr[v0 + d]; val od = oT[d]; for (i in 0 until n) od[i] += b0 * e0[i] }
            j++
        }
        for (i in 0 until n) {
            val tok = s * seqStride + i * posStride
            val g = gates[tok * heads + hh] / l[i]
            val dst = tok * c + hh * HEAD
            for (d in 0 until HEAD) att[dst + d] = oT[d][i] * g
        }
    }

    // ---- the matrix kernel ---------------------------------------------------------------------

    /**
     * dst[dOff + r dStride + o] += sum_k src[sOff + r sStride + k] * w[wRow0 + k][o]
     * for rows r < [rows], k < [inDim], o < w.cols. Rows go in blocks of [RB] through a
     * zero-based accumulator, four inputs per pass, outputs in the matrix's column tiles.
     */
    private fun mm(src: FloatArray, sOff: Int, sStride: Int, inDim: Int, w: Mat, wRow0: Int, dst: FloatArray, dOff: Int, dStride: Int, rows: Int) = timed(0) {
        par.items(rows, RB) { worker, r0, r1 ->
            val acc = scratch[worker].acc
            val k4 = inDim and 3.inv()
            for (ti in w.tiles.indices) {
                val tile = w.tiles[ti]; val c0 = w.tileStart[ti]; val tc = tile[0].size
                var rb = r0
                while (rb < r1) {
                    val rbEnd = min(r1, rb + RB)
                    for (r in rb until rbEnd) System.arraycopy(dst, dOff + r * dStride + c0, acc[r - rb], 0, tc)
                    var k = 0
                    while (k < k4) {
                        val w0 = tile[wRow0 + k]; val w1 = tile[wRow0 + k + 1]; val w2 = tile[wRow0 + k + 2]; val w3 = tile[wRow0 + k + 3]
                        for (r in rb until rbEnd) {
                            val s = sOff + r * sStride + k
                            val a0 = src[s]; val a1 = src[s + 1]; val a2 = src[s + 2]; val a3 = src[s + 3]
                            val yr = acc[r - rb]
                            for (o in 0 until tc) yr[o] += a0 * w0[o] + a1 * w1[o] + a2 * w2[o] + a3 * w3[o]
                        }
                        k += 4
                    }
                    while (k < inDim) {
                        val w0 = tile[wRow0 + k]
                        for (r in rb until rbEnd) {
                            val a0 = src[sOff + r * sStride + k]; val yr = acc[r - rb]
                            for (o in 0 until tc) yr[o] += a0 * w0[o]
                        }
                        k++
                    }
                    for (r in rb until rbEnd) System.arraycopy(acc[r - rb], 0, dst, dOff + r * dStride + c0, tc)
                    rb = rbEnd
                }
            }
        }
    }

    fun close() = par.close()

    private class Scratch(n: Int) {
        val qT = Array(HEAD) { FloatArray(n) }; val oT = Array(HEAD) { FloatArray(n) }
        val kr = FloatArray(n * HEAD); val vr = FloatArray(n * HEAD)
        val sT = Array(n) { FloatArray(n) }
        val m = FloatArray(n); val l = FloatArray(n)
        val acc = Array(RB) { FloatArray(TILE) }
    }

    /** A fixed pool; work is split into equal contiguous parts, the caller runs the first. */
    class Par(val threads: Int) {
        private val pool: ExecutorService? = if (threads > 1) Executors.newFixedThreadPool(threads - 1) { r ->
            Thread(r).also { it.isDaemon = true }
        } else null

        inline fun rows(n: Int, align: Int = 1, crossinline body: (Int, Int) -> Unit) = items(n, align) { _, lo, hi -> body(lo, hi) }

        fun items(n: Int, align: Int = 1, body: (Int, Int, Int) -> Unit) {
            if (pool == null || n < 2 * align) { body(0, 0, n); return }
            val chunks = (n + align - 1) / align
            val parts = min(threads, chunks)
            val latch = CountDownLatch(parts - 1)
            fun lo(p: Int) = min(n, (chunks * p / parts) * align)
            for (p in 1 until parts) pool.execute { try { body(p, lo(p), lo(p + 1)) } finally { latch.countDown() } }
            body(0, 0, lo(1))
            latch.await()
        }

        fun close() { pool?.shutdown() }
    }

    /** A weight matrix [rows][cols], stored as column tiles of at most [TILE], one FloatArray per row. */
    class Mat(val rows: Int, val cols: Int, flat: FloatArray, colScale: FloatArray? = null) {
        val tileStart = IntArray((cols + TILE - 1) / TILE) { it * TILE }
        val tiles = Array(tileStart.size) { ti ->
            val c0 = tileStart[ti]; val tc = min(TILE, cols - c0)
            Array(rows) { r -> FloatArray(tc) { o -> flat[r * cols + c0 + o] * (colScale?.get(c0 + o) ?: 1f) } }
        }
    }

    class Attn(val gamma: FloatArray, val qkv: Mat, val gateW: FloatArray, val gateB: FloatArray, val out: Mat)
    class Ff(val gamma: FloatArray, val w1: Mat, val b1: FloatArray, val w2: Mat, val b2: FloatArray)
    class Block(val attnF: Attn, val ffF: Ff, val attnT: Attn, val ffT: Ff, val conv: Mat, val convShift: FloatArray)

    class Weights(
        val dim: Int, val bn1Scale: FloatArray, val bn1Shift: FloatArray,
        val stemW: FloatArray, val stemScale: FloatArray, val stemShift: FloatArray,
        val blocks: List<Block>, val lin: Mat, val linB: FloatArray,
        val layers: List<Pair<Attn, Ff>>, val normGamma: FloatArray, val headW: FloatArray, val headB: FloatArray,
    ) {
        companion object {
            /** "BTHS" v1: transformer width, then arrays of little-endian float32, each preceded by its length. */
            fun read(bytes: ByteArray): Weights {
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { b.get(it) }
                require(String(magic) == "BTHS" && b.int == 1) { "not a Beat This! weights file" }
                val d = b.int
                val count = b.int
                var next = 0
                fun arr(size: Int): FloatArray {
                    require(next++ < count)
                    val n = b.int
                    require(n == size) { "array ${next - 1}: $n floats, expected $size" }
                    val a = FloatArray(n)
                    b.asFloatBuffer().get(a); b.position(b.position() + 4 * n)
                    return a
                }
                fun mat(r: Int, c: Int) = Mat(r, c, arr(r * c))
                fun attn(c: Int) = Attn(arr(c), mat(c, 3 * c), arr(c * (c / HEAD)), arr(c / HEAD), mat(c, c))
                fun ff(c: Int) = Ff(arr(c), mat(c, 4 * c), arr(4 * c), mat(4 * c, c), arr(c))
                val bn1s = arr(128); val bn1b = arr(128)
                val stemW = arr(12 * 32); val stemS = arr(32); val stemB = arr(32)
                val blocks = listOf(32, 64, 128).map { c ->
                    val aF = attn(c); val fF = ff(c); val aT = attn(c); val fT = ff(c)
                    val convW = arr(6 * c * 2 * c); val scale = arr(2 * c); val shift = arr(2 * c)
                    Block(aF, fF, aT, fT, Mat(6 * c, 2 * c, convW, scale), shift)   // BatchNorm scale folded into the conv
                }
                val lin = mat(1024, d); val linB = arr(d)
                val layers = List(6) { attn(d) to ff(d) }
                val w = Weights(d, bn1s, bn1b, stemW, stemS, stemB, blocks, lin, linB, layers, arr(d), arr(2 * d), arr(2))
                require(next == count && !b.hasRemaining()) { "unexpected file length" }
                return w
            }
        }
    }

    companion object {
        const val HEAD = 32
        /** Rows per accumulator block and the column tile of the weight matrices. */
        @JvmField val RB: Int = Integer.getInteger("bt.rb", 8)
        @JvmField val TILE: Int = Integer.getInteger("bt.tile", 512)

        var PROFILE = false
        val prof = LongArray(8)
        val profNames = arrayOf("matmul", "attention core", "gelu", "rmsnorm", "gates", "stem", "", "")
        inline fun <T> timed(slot: Int, body: () -> T): T {
            if (!PROFILE) return body()
            val t0 = System.nanoTime(); val r = body(); prof[slot] += System.nanoTime() - t0; return r
        }
    }
}

/**
 * e^x for x <= 0, branch-free so that loops over it vectorise: x is clamped to -20 (e^-20 = 2e-9
 * is nothing next to the softmax maximum's 1, or next to 1 in erf), divided by 32, a degree-8
 * Taylor polynomial, squared five times. Relative error about 2e-6.
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun expNeg(x: Float): Float {
    val v = max(x, -20f) * 0.03125f
    var p = 1f + v * (1f + v * (0.5f + v * (0.16666667f + v * (0.041666668f + v * (0.008333334f + v * (0.0013888889f + v * (1.984127e-4f + v * 2.4801588e-5f)))))))
    p *= p; p *= p; p *= p; p *= p; p *= p
    return p
}

/** Exact (erf) GELU, erf by Abramowitz-Stegun 7.1.26 (absolute error < 1.5e-7), branch-free. */
@Suppress("NOTHING_TO_INLINE")
private inline fun gelu(x: Float): Float {
    val ax = abs(x)
    val z = ax * 0.70710678f
    val t = 1f / (1f + 0.3275911f * z)
    val poly = ((((1.0614054f * t - 1.4531521f) * t + 1.4214138f) * t - 0.28449672f) * t + 0.2548296f) * t
    val erf = 1f - poly * expNeg(-z * z)
    return 0.5f * (x + ax * erf)
}

private fun geluRange(a: FloatArray, from: Int, to: Int) {
    for (i in from until to) a[i] = gelu(a[i])
}
