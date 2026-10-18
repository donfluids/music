package com.donfluids.piano

import android.content.Context
import com.donfluids.music.audio.AudioCapture
import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.dsp.MusicAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Runs LiveKey's analyser on the microphone and publishes what it hears. */
class SongListener(private val context: Context) {
    private val _result = MutableStateFlow(AnalysisResult())
    val result: StateFlow<AnalysisResult> = _result
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Volatile private var analyzer: MusicAnalyzer? = null
    private var capture: AudioCapture? = null
    val running: Boolean get() = capture != null
    /** True while the microphone path is cancelling the phone's own playback. */
    val echoCancelling: Boolean get() = capture?.echoCancelling == true

    /**
     * The pitch classes the app is sounding in audio captured at a given time, so the analysis
     * leaves the app's own piano and band out. Set before [start].
     */
    var ownSound: ((Long) -> Int)? = null

    /**
     * Opens the microphone with the phone's echo cancelling on whenever it has one: the app
     * plays through the same speaker the microphone hears, whether the band or your own keys.
     */
    fun start() {
        if (capture != null) return
        _error.value = null
        val cap = AudioCapture(
            context,
            listener = { samples, count, now -> analyzer?.let { if (it.process(samples, count, now)) _result.value = it.result } },
            onError = { message -> _error.value = message; capture = null },
            echoCancel = true,
        )
        try { cap.start() } catch (e: Exception) { _error.value = e.message ?: "Could not open the microphone"; return }
        analyzer = newAnalyzer(cap.sampleRate)
        capture = cap
        _result.value = AnalysisResult()
    }

    private fun newAnalyzer(rate: Int) = MusicAnalyzer(rate, AnalysisPreset.VOICES).also { a -> a.ownSound = { t -> ownSound?.invoke(t) ?: 0 } }

    fun stop() {
        capture?.stop()
        capture = null
        analyzer = null
    }

    /** Forget the previous song. */
    fun reset() {
        val cap = capture ?: return
        analyzer = newAnalyzer(cap.sampleRate)
        _result.value = AnalysisResult()
    }
}
