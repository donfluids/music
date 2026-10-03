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
    private var cancelling = false

    /**
     * [withBand]: the band is playing through the speaker, so open the microphone with echo
     * cancellation so the analyser hears the singers rather than the band.
     */
    fun start(withBand: Boolean) {
        if (capture != null) return
        _error.value = null
        val cap = AudioCapture(
            context,
            listener = { samples, count, now -> analyzer?.let { if (it.process(samples, count, now)) _result.value = it.result } },
            onError = { message -> _error.value = message; capture = null },
            echoCancel = withBand,
        )
        try { cap.start() } catch (e: Exception) { _error.value = e.message ?: "Could not open the microphone"; return }
        // Keep what was already heard when only the microphone path changes.
        val kept = analyzer?.takeIf { keepAnalysis && it.sampleRate == cap.sampleRate }
        analyzer = kept ?: MusicAnalyzer(cap.sampleRate, AnalysisPreset.VOICES)
        keepAnalysis = false
        capture = cap
        cancelling = withBand
        if (kept == null) _result.value = AnalysisResult()
    }

    private var keepAnalysis = false

    /** Reopens the microphone with or without echo cancellation; the song heard so far is kept. */
    fun setBandPlaying(playing: Boolean) {
        if (capture == null || playing == cancelling) return
        capture?.stop()
        capture = null
        keepAnalysis = true
        start(withBand = playing)
    }

    fun stop() {
        capture?.stop()
        capture = null
        analyzer = null
    }

    /** Forget the previous song. */
    fun reset() {
        val cap = capture ?: return
        analyzer = MusicAnalyzer(cap.sampleRate, AnalysisPreset.VOICES)
        _result.value = AnalysisResult()
    }
}
