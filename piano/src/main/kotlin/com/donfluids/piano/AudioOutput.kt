package com.donfluids.piano

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process

/** Streams the synthesiser to the speaker on a dedicated thread with the smallest safe buffer. */
class AudioOutput(context: Context, private val onError: (String) -> Unit) {
    val sampleRate: Int = (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    val synth = PianoSynth(sampleRate)
    val metronome = Metronome(sampleRate)
    val reverb = Reverb(sampleRate)

    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "MiniPiano-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        synth.allNotesOff()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBytes, 4 * 256 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            running = false
            onError(e.message ?: "AudioTrack failed")
            return
        }
        val block = 256
        val bufferFrames = track.bufferSizeInFrames
        val buffer = FloatArray(block)
        try {
            track.play()
            while (running) {
                synth.render(buffer, block)
                reverb.process(buffer, block)
                // The device plays this block roughly one buffer after we hand it over.
                metronome.mix(buffer, block, System.nanoTime() + (bufferFrames.toDouble() / sampleRate * 1e9).toLong())
                val written = track.write(buffer, 0, block, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("Audio write failed ($written)")
            }
        } catch (t: Throwable) {
            if (running) { running = false; onError(t.message ?: "Audio error") }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
