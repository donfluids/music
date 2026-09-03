package com.donfluids.music.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Synthetic audio for tests: voice-like tones and click tracks. */
object Synth {
    const val SAMPLE_RATE = 44100

    fun midiToHz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** A sustained chord made of harmonic-rich tones. */
    fun chord(midiNotes: List<Int>, seconds: Double, amplitude: Float = 0.2f): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val harmonics = doubleArrayOf(1.0, 0.5, 0.3, 0.2, 0.1)
        for (midi in midiNotes) {
            val f = midiToHz(midi)
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                var v = 0.0
                for ((h, a) in harmonics.withIndex()) v += a * sin(2 * PI * f * (h + 1) * t)
                out[i] += (v * amplitude / midiNotes.size).toFloat()
            }
        }
        return out
    }

    /** Several chords back to back. */
    fun progression(chords: List<List<Int>>, secondsEach: Double): FloatArray =
        chords.map { chord(it, secondsEach) }.fold(FloatArray(0)) { acc, c -> acc + c }

    /**
     * Clicks at [bpm]. [accents] gives the amplitude of each beat in the bar, cycled.
     * [subdivisions] adds quieter clicks between beats (2 = eighths, 3 = triplets).
     */
    fun clickTrack(
        bpm: Double,
        seconds: Double,
        accents: List<Float> = listOf(1f),
        subdivisions: Int = 0,
        subdivisionAmplitude: Float = 0.3f,
    ): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val period = 60.0 / bpm * SAMPLE_RATE
        var beat = 0
        var t = 0.0
        while (t < n) {
            addClick(out, t.toInt(), accents[beat % accents.size])
            if (subdivisions > 1) {
                for (s in 1 until subdivisions) addClick(out, (t + period * s / subdivisions).toInt(), subdivisionAmplitude)
            }
            beat++
            t += period
        }
        return out
    }

    private fun addClick(out: FloatArray, start: Int, amplitude: Float) {
        val length = (0.03 * SAMPLE_RATE).toInt()
        for (i in 0 until length) {
            val idx = start + i
            if (idx >= out.size) break
            val env = exp(-i / (0.005 * SAMPLE_RATE))
            out[idx] += (amplitude * env * sin(2 * PI * 1500.0 * i / SAMPLE_RATE)).toFloat()
        }
    }

    fun mix(a: FloatArray, b: FloatArray): FloatArray {
        val n = maxOf(a.size, b.size)
        return FloatArray(n) { (if (it < a.size) a[it] else 0f) + (if (it < b.size) b[it] else 0f) }
    }

    /** Feed audio through an analyser in realistic chunks and return the final result. */
    fun analyze(audio: FloatArray, analyzer: MusicAnalyzer = MusicAnalyzer(SAMPLE_RATE)): AnalysisResult {
        val chunk = 1024
        var i = 0
        while (i < audio.size) {
            val n = minOf(chunk, audio.size - i)
            val buf = audio.copyOfRange(i, i + n)
            val nowNanos = ((i + n).toDouble() / SAMPLE_RATE * 1e9).toLong()
            analyzer.process(buf, n, nowNanos)
            i += n
        }
        return analyzer.result
    }
}
