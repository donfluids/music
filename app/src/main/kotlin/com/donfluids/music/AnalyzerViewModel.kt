package com.donfluids.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.donfluids.music.audio.AudioCapture
import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.dsp.MusicAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UiState(
    val listening: Boolean = false,
    val error: String? = null,
    /** Display-only multiplier for the tempo (0.5, 1 or 2) when the tracker picks the wrong pulse. */
    val tempoMultiplier: Float = 1f,
    val analysis: AnalysisResult = AnalysisResult(),
)

class AnalyzerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var analyzer: MusicAnalyzer? = null
    private var capture: AudioCapture? = null

    fun start() {
        if (_state.value.listening) return
        val cap = AudioCapture(getApplication()) { samples, count, now ->
            val a = analyzer ?: return@AudioCapture
            if (a.process(samples, count, now)) {
                _state.update { it.copy(analysis = a.result) }
            }
        }
        try {
            cap.start()
        } catch (e: IllegalStateException) {
            _state.update { it.copy(error = e.message ?: "Could not open the microphone") }
            return
        }
        analyzer = MusicAnalyzer(cap.sampleRate)
        capture = cap
        _state.update { it.copy(listening = true, error = null, analysis = AnalysisResult()) }
    }

    fun stop() {
        capture?.stop()
        capture = null
        _state.update { it.copy(listening = false) }
    }

    /** Forget everything heard so far: use when the next song starts. */
    fun reset() {
        analyzer?.reset()
        _state.update { it.copy(analysis = AnalysisResult(), tempoMultiplier = 1f) }
    }

    fun cycleTempoMultiplier() {
        _state.update {
            val next = when (it.tempoMultiplier) { 1f -> 2f; 2f -> 0.5f; else -> 1f }
            it.copy(tempoMultiplier = next)
        }
    }

    fun reportPermissionDenied(message: String) {
        _state.update { it.copy(error = message) }
    }

    override fun onCleared() {
        stop()
    }
}
