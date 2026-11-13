// Which plain-Kotlin loop shapes does the JIT vectorize? Rows x (in -> out) matmul, GMAC/s.
import kotlin.math.min

const val ROWS = 412
const val IN = 512
const val OUT = 512

fun flatAxpy(src: FloatArray, w: FloatArray, dst: FloatArray) {
    for (r in 0 until ROWS) {
        val d = r * OUT
        for (k in 0 until IN) {
            val a = src[r * IN + k]; val wk = k * OUT
            for (o in 0 until OUT) dst[d + o] += a * w[wk + o]
        }
    }
}

fun rowAxpy(src: FloatArray, w: Array<FloatArray>, dst: Array<FloatArray>) {
    for (r in 0 until ROWS) {
        val y = dst[r]
        for (k in 0 until IN) {
            val a = src[r * IN + k]; val wr = w[k]
            for (o in 0 until OUT) y[o] += a * wr[o]
        }
    }
}

fun rowK4(src: FloatArray, w: Array<FloatArray>, dst: Array<FloatArray>) {
    for (r in 0 until ROWS) {
        val y = dst[r]
        var k = 0
        while (k < IN) {
            val a0 = src[r * IN + k]; val a1 = src[r * IN + k + 1]; val a2 = src[r * IN + k + 2]; val a3 = src[r * IN + k + 3]
            val w0 = w[k]; val w1 = w[k + 1]; val w2 = w[k + 2]; val w3 = w[k + 3]
            for (o in 0 until OUT) y[o] += a0 * w0[o] + a1 * w1[o] + a2 * w2[o] + a3 * w3[o]
            k += 4
        }
    }
}

fun flatK4(src: FloatArray, w: FloatArray, dst: FloatArray) {
    for (r in 0 until ROWS) {
        val d = r * OUT
        var k = 0
        while (k < IN) {
            val a0 = src[r * IN + k]; val a1 = src[r * IN + k + 1]; val a2 = src[r * IN + k + 2]; val a3 = src[r * IN + k + 3]
            val w0 = k * OUT; val w1 = w0 + OUT; val w2 = w1 + OUT; val w3 = w2 + OUT
            for (o in 0 until OUT) dst[d + o] += a0 * w[w0 + o] + a1 * w[w1 + o] + a2 * w[w2 + o] + a3 * w[w3 + o]
            k += 4
        }
    }
}

/** Local accumulator row: copy dst row into acc, accumulate, copy back. */
fun accK4(src: FloatArray, w: FloatArray, dst: FloatArray) {
    val acc = FloatArray(OUT)
    for (r in 0 until ROWS) {
        val d = r * OUT
        System.arraycopy(dst, d, acc, 0, OUT)
        var k = 0
        while (k < IN) {
            val a0 = src[r * IN + k]; val a1 = src[r * IN + k + 1]; val a2 = src[r * IN + k + 2]; val a3 = src[r * IN + k + 3]
            val w0 = k * OUT; val w1 = w0 + OUT; val w2 = w1 + OUT; val w3 = w2 + OUT
            for (o in 0 until OUT) acc[o] += a0 * w[w0 + o] + a1 * w[w1 + o] + a2 * w[w2 + o] + a3 * w[w3 + o]
            k += 4
        }
        System.arraycopy(acc, 0, dst, d, OUT)
    }
}

/** Same with the range written as an explicit int while-loop and offsets folded into the base. */
fun accK4b(src: FloatArray, w: FloatArray, dst: FloatArray) {
    val acc = FloatArray(OUT)
    for (r in 0 until ROWS) {
        val d = r * OUT
        System.arraycopy(dst, d, acc, 0, OUT)
        var k = 0
        while (k < IN) {
            val a0 = src[r * IN + k]; val a1 = src[r * IN + k + 1]; val a2 = src[r * IN + k + 2]; val a3 = src[r * IN + k + 3]
            val w0 = k * OUT
            var o = 0
            while (o < OUT) {
                acc[o] = acc[o] + a0 * w[w0 + o] + a1 * w[w0 + OUT + o] + a2 * w[w0 + 2 * OUT + o] + a3 * w[w0 + 3 * OUT + o]
                o++
            }
            k += 4
        }
        System.arraycopy(acc, 0, dst, d, OUT)
    }
}

fun accRowK4(src: FloatArray, w: Array<FloatArray>, dst: FloatArray) {
    val acc = FloatArray(OUT)
    for (r in 0 until ROWS) {
        val d = r * OUT
        System.arraycopy(dst, d, acc, 0, OUT)
        var k = 0
        while (k < IN) {
            val s = r * IN + k
            val a0 = src[s]; val a1 = src[s + 1]; val a2 = src[s + 2]; val a3 = src[s + 3]
            val w0 = w[k]; val w1 = w[k + 1]; val w2 = w[k + 2]; val w3 = w[k + 3]
            for (o in 0 until OUT) acc[o] += a0 * w0[o] + a1 * w1[o] + a2 * w2[o] + a3 * w3[o]
            k += 4
        }
        System.arraycopy(acc, 0, dst, d, OUT)
    }
}

/** Blocks of RB rows: the four weight rows are reused from L1 by every row of the block. */
fun accRowK4B(src: FloatArray, w: Array<FloatArray>, dst: FloatArray, rb: Int) {
    val acc = Array(rb) { FloatArray(OUT) }
    var r0 = 0
    while (r0 < ROWS) {
        val r1 = min(ROWS, r0 + rb)
        for (r in r0 until r1) System.arraycopy(dst, r * OUT, acc[r - r0], 0, OUT)
        var k = 0
        while (k < IN) {
            val w0 = w[k]; val w1 = w[k + 1]; val w2 = w[k + 2]; val w3 = w[k + 3]
            for (r in r0 until r1) {
                val s = r * IN + k
                val a0 = src[s]; val a1 = src[s + 1]; val a2 = src[s + 2]; val a3 = src[s + 3]
                val y = acc[r - r0]
                for (o in 0 until OUT) y[o] += a0 * w0[o] + a1 * w1[o] + a2 * w2[o] + a3 * w3[o]
            }
            k += 4
        }
        for (r in r0 until r1) System.arraycopy(acc[r - r0], 0, dst, r * OUT, OUT)
        r0 = r1
    }
}

/** Four rows at once, each its own zero-based accumulator, sharing each weight load. */
fun accR4(src: FloatArray, w: Array<FloatArray>, dst: FloatArray) {
    val y0 = FloatArray(OUT); val y1 = FloatArray(OUT); val y2 = FloatArray(OUT); val y3 = FloatArray(OUT)
    var r = 0
    while (r < ROWS) {
        System.arraycopy(dst, r * OUT, y0, 0, OUT); System.arraycopy(dst, (r + 1) * OUT, y1, 0, OUT)
        System.arraycopy(dst, (r + 2) * OUT, y2, 0, OUT); System.arraycopy(dst, (r + 3) * OUT, y3, 0, OUT)
        for (k in 0 until IN) {
            val a0 = src[r * IN + k]; val a1 = src[(r + 1) * IN + k]; val a2 = src[(r + 2) * IN + k]; val a3 = src[(r + 3) * IN + k]
            val wk = w[k]
            for (o in 0 until OUT) { val v = wk[o]; y0[o] += a0 * v; y1[o] += a1 * v; y2[o] += a2 * v; y3[o] += a3 * v }
        }
        System.arraycopy(y0, 0, dst, r * OUT, OUT); System.arraycopy(y1, 0, dst, (r + 1) * OUT, OUT)
        System.arraycopy(y2, 0, dst, (r + 2) * OUT, OUT); System.arraycopy(y3, 0, dst, (r + 3) * OUT, OUT)
        r += 4
    }
}

fun expBench() {
    val x = FloatArray(412) { -it * 0.03f }
    val y = FloatArray(412)
    for (mode in 0..1) {
        repeat(20000) { for (j in 0 until 412) y[j] = if (mode == 0) kotlin.math.exp(x[j]) else fexp(x[j]) }
        val t0 = System.nanoTime()
        repeat(20000) { for (j in 0 until 412) y[j] = if (mode == 0) kotlin.math.exp(x[j]) else fexp(x[j]) }
        println("exp mode $mode: %.2f ns per exp (%f)".format((System.nanoTime() - t0).toDouble() / 20000 / 412, y[100]))
    }
}

fun fexp(x: Float): Float {
    val z = kotlin.math.max(x * 1.442695041f, -126f)
    val fi = kotlin.math.floor(z)
    val f = z - fi
    val p = 1f + f * (0.6931472f + f * (0.2402265f + f * (0.05550411f + f * (0.009618129f + f * (0.0013333558f + f * 0.00015403530f)))))
    return Float.fromBits(((fi.toInt() + 127) shl 23)) * p
}

fun main() {
    expBench()
    val src = FloatArray(ROWS * IN) { (it % 7) * 0.1f }
    val wf = FloatArray(IN * OUT) { (it % 11) * 0.01f }
    val wr = Array(IN) { k -> FloatArray(OUT) { o -> wf[k * OUT + o] } }
    val df = FloatArray(ROWS * OUT)
    val dr = Array(ROWS) { FloatArray(OUT) }
    val macs = ROWS.toDouble() * IN * OUT
    val cases = listOf<Pair<String, () -> Unit>>(
        "flatAxpy" to { flatAxpy(src, wf, df) },
        "rowAxpy" to { rowAxpy(src, wr, dr) },
        "rowK4" to { rowK4(src, wr, dr) },
        "flatK4" to { flatK4(src, wf, df) },
        "accK4" to { accK4(src, wf, df) },
        "accK4b" to { accK4b(src, wf, df) },
        "accRowK4" to { accRowK4(src, wr, df) },
        "accRowK4B8" to { accRowK4B(src, wr, df, 8) },
        "accRowK4B32" to { accRowK4B(src, wr, df, 32) },
        "accR4" to { accR4(src, wr, df) },
    )
    for ((name, f) in cases) {
        repeat(30) { f() }
        val ts = DoubleArray(30) { val t0 = System.nanoTime(); f(); (System.nanoTime() - t0) / 1e9 }
        ts.sort()
        println("%-10s %.2f GMAC/s (median %.1f ms)".format(name, macs / ts[15] / 1e9, ts[15] * 1e3))
    }
}
