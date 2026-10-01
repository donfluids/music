package com.donfluids.piano

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A click track mixed into the audio stream. Beat timing is counted in samples, so it stays
 * exact however the UI thread is doing; the UI reads [beatIndex] and [beatAtNanos] to flash.
 */
class Metronome(private val sampleRate: Int) {
    @Volatile var enabled: Boolean = false
        set(value) { if (value && !field) restart = true; field = value }
    @Volatile var bpm: Int = 100
    @Volatile var beatsPerBar: Int = 4
    @Volatile var volume: Float = 0.6f

    /** Beat within the bar (0 = downbeat) of the most recent click, for the display. */
    @Volatile var beatIndex: Int = -1
        private set
    /** System.nanoTime() at which the most recent click was handed to the audio device. */
    @Volatile var beatAtNanos: Long = 0L
        private set
    /** Total clicks since the metronome started. */
    @Volatile var beatCount: Long = 0
        private set

    private var samplesToNextBeat = 0
    private var clickPos = Int.MAX_VALUE
    private var clickAccent = false
    @Volatile private var restart = true

    /**
     * Mixes clicks into [out] for [frames] samples. [nowNanos] is the time the first sample of
     * this block will reach the device, used to timestamp beats for the display.
     */
    fun mix(out: FloatArray, frames: Int, nowNanos: Long) {
        if (!enabled) { clickPos = Int.MAX_VALUE; return }
        if (restart) { restart = false; samplesToNextBeat = 0; beatCount = 0; beatIndex = -1 }
        val period = (sampleRate * 60.0 / bpm.coerceIn(20, 300)).toInt()
        for (i in 0 until frames) {
            if (samplesToNextBeat <= 0) {
                beatIndex = (beatIndex + 1) % beatsPerBar.coerceAtLeast(1)
                beatCount++
                beatAtNanos = nowNanos + (i.toDouble() / sampleRate * 1e9).toLong()
                clickAccent = beatIndex == 0
                clickPos = 0
                samplesToNextBeat = period
            }
            if (clickPos < CLICK_SAMPLES) {
                val t = clickPos.toDouble() / sampleRate
                val freq = if (clickAccent) 1760.0 else 1175.0
                val env = exp(-t / 0.004)
                out[i] += (volume * (if (clickAccent) 1f else 0.7f) * env * sin(2 * PI * freq * t)).toFloat()
                clickPos++
            }
            samplesToNextBeat--
        }
    }

    private companion object {
        const val CLICK_SAMPLES = 1200
    }
}
