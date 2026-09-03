package com.donfluids.music.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process

/**
 * Pulls mono PCM from the microphone on a dedicated thread and hands it to [listener]
 * as floats in -1..1, together with the System.nanoTime() of the newest sample.
 */
class AudioCapture(
    private val context: Context,
    private val listener: (samples: FloatArray, count: Int, nowNanos: Long) -> Unit,
) {
    var sampleRate: Int = 0
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Starts capture. Throws [IllegalStateException] if no usable microphone configuration exists. */
    @SuppressLint("MissingPermission") // The caller checks RECORD_AUDIO before calling.
    fun start() {
        if (running) return
        val source = pickSource()
        var opened: AudioRecord? = null
        var rate = 0
        for (candidate in intArrayOf(44100, 48000, 22050, 16000)) {
            val minBuffer = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            val bufferBytes = maxOf(minBuffer * 2, candidate / 2 * 2) // at least half a second
            val r = AudioRecord(source, candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                opened = r
                rate = candidate
                break
            }
            r.release()
        }
        val r = opened ?: throw IllegalStateException("Could not open the microphone")
        sampleRate = rate
        record = r
        running = true
        thread = Thread({ loop(r) }, "LiveKey-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    private fun loop(r: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val shorts = ShortArray(1024)
        val floats = FloatArray(shorts.size)
        r.startRecording()
        while (running) {
            val n = r.read(shorts, 0, shorts.size)
            if (n <= 0) continue
            val now = System.nanoTime()
            for (i in 0 until n) floats[i] = shorts[i] / 32768f
            listener(floats, n, now)
        }
    }

    /**
     * Prefer the unprocessed source so automatic gain control and noise suppression do not
     * flatten the dynamics the beat tracker relies on. Fall back to the voice-recognition
     * source, which is also lightly processed on most devices.
     */
    private fun pickSource(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return if (unprocessed == "true") MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }
}
