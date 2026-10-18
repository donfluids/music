package com.donfluids.piano

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Streams the synthesiser to the speaker on a dedicated thread with the smallest safe buffer. */
class AudioOutput(context: Context, private val onError: (String) -> Unit) {
    val sampleRate: Int = (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    val synth = PianoSynth(sampleRate)
    val metronome = Metronome(sampleRate)
    val reverb = Reverb(sampleRate)
    val band = Accompaniment(sampleRate)
    /** What this app is sounding, for Listen to leave out. */
    val ownSound = OwnSound()
    /** Gain, speaker tuning and limiting for the whole mix. */
    val master = MasterBus(sampleRate)

    /**
     * Extra loudness from Android's loudness enhancer on top of the master stage. Off by default:
     * on some phones it pushes the speaker into distortion.
     */
    @Volatile var boost: Boolean = false
        set(value) { field = value; enhancer?.let { e -> runCatching { e.enabled = value } } }
    @Volatile private var enhancer: LoudnessEnhancer? = null

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
        // Speaker tuning only while the phone's own speaker plays; follow the route as it changes.
        fun isSpeaker(d: AudioDeviceInfo?) = d == null || d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val routing = AudioRouting.OnRoutingChangedListener { r -> master.speakerTuning = isSpeaker(r.routedDevice) }
        runCatching { track.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper())) }
        enhancer = runCatching { LoudnessEnhancer(track.audioSessionId).also { it.setTargetGain(BOOST_MILLIBELS); it.enabled = boost } }.getOrNull()
        val block = 256
        val bufferFrames = track.bufferSizeInFrames
        val buffer = FloatArray(block)
        try {
            track.play()
            while (running) {
                synth.render(buffer, block)
                // The device plays this block roughly one buffer after we hand it over.
                val at = System.nanoTime() + (bufferFrames.toDouble() / sampleRate * 1e9).toLong()
                band.mix(buffer, block, at)
                ownSound.sounded(synth.soundingMask or band.soundingMask, at)
                reverb.process(buffer, block)
                metronome.mix(buffer, block, at)
                master.process(buffer, block)
                val written = track.write(buffer, 0, block, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("Audio write failed ($written)")
            }
        } catch (t: Throwable) {
            if (running) { running = false; onError(t.message ?: "Audio error") }
        } finally {
            runCatching { track.removeOnRoutingChangedListener(routing) }
            enhancer?.let { e -> runCatching { e.release() } }
            enhancer = null
            runCatching { track.stop() }
            track.release()
        }
    }

    private companion object {
        /** Boost in millibels (hundredths of a decibel): +6 dB. */
        const val BOOST_MILLIBELS = 600
    }
}
