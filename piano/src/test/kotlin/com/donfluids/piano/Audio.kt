package com.donfluids.piano

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Measurement helpers for the sound tests. */
object Audio {
    fun render(synth: PianoSynth, seconds: Double, reverb: Reverb? = null, metronome: Metronome? = null): FloatArray {
        val rate = synth.sampleRate
        val out = FloatArray((seconds * rate).toInt())
        val block = FloatArray(256)
        var i = 0
        while (i < out.size) {
            val n = minOf(256, out.size - i)
            synth.render(block, n)
            reverb?.process(block, n)
            metronome?.mix(block, n, (i.toDouble() / rate * 1e9).toLong())
            System.arraycopy(block, 0, out, i, n)
            i += n
        }
        return out
    }

    /** Goertzel magnitude at [hz], over [x]. */
    fun power(x: FloatArray, hz: Double, rate: Int): Double {
        var re = 0.0; var im = 0.0
        for (i in x.indices) { val a = 2 * PI * hz * i / rate; re += x[i] * cos(a); im += x[i] * sin(a) }
        return sqrt(re * re + im * im) / x.size
    }

    /** Frequency of the strongest component within ±[spanCents] of [aroundHz], by fine Goertzel scan. */
    fun peakHz(x: FloatArray, aroundHz: Double, rate: Int, spanCents: Double = 60.0, stepCents: Double = 0.5): Double {
        var best = aroundHz; var bestP = -1.0
        var c = -spanCents
        while (c <= spanCents) {
            val hz = aroundHz * Math.pow(2.0, c / 1200.0)
            val p = power(x, hz, rate)
            if (p > bestP) { bestP = p; best = hz }
            c += stepCents
        }
        return best
    }

    fun cents(actual: Double, expected: Double) = 1200.0 * Math.log(actual / expected) / Math.log(2.0)

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    fun peak(x: FloatArray) = x.maxOf { kotlin.math.abs(it) }

    /** Largest sample-to-sample step in [from, to). */
    fun maxStep(x: FloatArray, from: Int = 1, to: Int = x.size): Float {
        var m = 0f
        for (i in maxOf(1, from) until to) m = maxOf(m, kotlin.math.abs(x[i] - x[i - 1]))
        return m
    }

    /** Writes 16-bit mono WAV, for listening tests. */
    fun writeWav(file: File, x: FloatArray, rate: Int) {
        val data = ByteArray(x.size * 2)
        for (i in x.indices) {
            val v = (x[i] * 32767f).toInt().coerceIn(-32768, 32767)
            data[2 * i] = (v and 0xFF).toByte(); data[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val h = ByteArray(44)
        fun str(o: Int, s: String) = s.forEachIndexed { i, ch -> h[o + i] = ch.code.toByte() }
        fun i32(o: Int, v: Int) { for (k in 0 until 4) h[o + k] = ((v shr (8 * k)) and 0xFF).toByte() }
        fun i16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v shr 8) and 0xFF).toByte() }
        str(0, "RIFF"); i32(4, 36 + data.size); str(8, "WAVE"); str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 1)
        i32(24, rate); i32(28, rate * 2); i16(32, 2); i16(34, 16); str(36, "data"); i32(40, data.size)
        file.parentFile.mkdirs()
        file.outputStream().use { it.write(h); it.write(data) }
    }
}
