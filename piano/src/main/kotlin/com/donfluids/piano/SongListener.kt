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

    /** [withBand] picks the preset: a band on the speaker gives drums to lock to; voices alone do not. */
    fun start(withBand: Boolean) {
        if (capture != null) return
        _error.value = null
        val cap = AudioCapture(
            context,
            listener = { samples, count, now -> analyzer?.let { if (it.process(samples, count, now)) _result.value = it.result } },
            onError = { message -> _error.value = message; capture = null },
        )
        try { cap.start() } catch (e: Exception) { _error.value = e.message ?: "Could not open the microphone"; return }
        analyzer = MusicAnalyzer(cap.sampleRate, if (withBand) AnalysisPreset.BAND else AnalysisPreset.VOICES)
        capture = cap
        _result.value = AnalysisResult()
    }

    fun stop() {
        capture?.stop()
        capture = null
    }

    /** Forget the previous song. */
    fun reset() {
        val cap = capture ?: return
        analyzer = MusicAnalyzer(cap.sampleRate, AnalysisPreset.VOICES)
        _result.value = AnalysisResult()
    }
}
