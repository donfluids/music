package com.donfluids.music

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.donfluids.music.audio.AudioCapture
import com.donfluids.music.audio.ClipRecorder
import com.donfluids.music.audio.ClipStore
import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.dsp.MusicAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val listening: Boolean = false,
    val error: String? = null,
    val preset: AnalysisPreset = AnalysisPreset.BAND,
    /** Null follows the system setting; true or false is the user's choice. */
    val darkTheme: Boolean? = null,
    /** Display-only multiplier for the tempo (0.5, 1 or 2) when the tracker picks the wrong pulse. */
    val tempoMultiplier: Float = 1f,
    val analysis: AnalysisResult = AnalysisResult(),
    val recording: Boolean = false,
    val recordingSeconds: Int = 0,
    val clipCount: Int = 0,
    /** A clip that has just finished and is waiting for the user's labels. */
    val clipAwaitingLabels: String? = null,
    /** A zip ready to hand to the share sheet; the screen consumes and clears it. */
    val shareFile: File? = null,
)

class AnalyzerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("livekey", Context.MODE_PRIVATE)
    private val clips = ClipStore(application)

    private val _state = MutableStateFlow(
        UiState(
            preset = AnalysisPreset.byId(prefs.getString(PREF_PRESET, null)),
            darkTheme = if (prefs.contains(PREF_DARK)) prefs.getBoolean(PREF_DARK, false) else null,
            clipCount = clips.count(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Read on the audio thread, replaced on the main thread when the preset changes. */
    @Volatile
    private var analyzer: MusicAnalyzer? = null
    @Volatile
    private var recorder: ClipRecorder? = null
    private var capture: AudioCapture? = null
    private var lastReportedSecond = -1

    fun start() {
        if (_state.value.listening) return
        val cap = AudioCapture(getApplication()) { samples, count, now -> onAudio(samples, count, now) }
        try {
            cap.start()
        } catch (e: IllegalStateException) {
            _state.update { it.copy(error = e.message ?: "Could not open the microphone") }
            return
        }
        analyzer = MusicAnalyzer(cap.sampleRate, _state.value.preset)
        capture = cap
        _state.update { it.copy(listening = true, error = null, analysis = AnalysisResult()) }
    }

    /** Runs on the audio thread. */
    private fun onAudio(samples: FloatArray, count: Int, now: Long) {
        val a = analyzer ?: return
        val changed = a.process(samples, count, now)
        val r = recorder
        if (r != null) {
            r.write(samples, count)
            if (changed) r.noteResult(a.result)
            val sec = r.seconds.toInt()
            if (sec != lastReportedSecond) {
                lastReportedSecond = sec
                _state.update { it.copy(recordingSeconds = sec) }
            }
            if (r.full) finishClip(r)
        }
        if (changed) _state.update { it.copy(analysis = a.result) }
    }

    fun stop() {
        recorder?.let { finishClip(it) }
        capture?.stop()
        capture = null
        _state.update { it.copy(listening = false) }
    }

    /** Forget everything heard so far: use when the next song starts. */
    fun reset() {
        analyzer?.reset()
        _state.update { it.copy(analysis = AnalysisResult(), tempoMultiplier = 1f) }
    }

    /** Switch presets. While listening, analysis starts over with the new settings. */
    fun setPreset(preset: AnalysisPreset) {
        if (preset == _state.value.preset) return
        prefs.edit().putString(PREF_PRESET, preset.id).apply()
        val cap = capture
        if (cap != null) analyzer = MusicAnalyzer(cap.sampleRate, preset)
        _state.update { it.copy(preset = preset, analysis = AnalysisResult(), tempoMultiplier = 1f) }
    }

    fun setTempoMultiplier(multiplier: Float) {
        _state.update { it.copy(tempoMultiplier = multiplier) }
    }

    /** Switches between light and dark, starting from whatever is currently shown. */
    fun setDarkTheme(dark: Boolean) {
        prefs.edit().putBoolean(PREF_DARK, dark).apply()
        _state.update { it.copy(darkTheme = dark) }
    }

    fun reportPermissionDenied(message: String) {
        _state.update { it.copy(error = message) }
    }

    // ---- Clips ----

    fun startClip() {
        val cap = capture ?: return
        if (recorder != null) return
        lastReportedSecond = -1
        recorder = ClipRecorder(clips.dir, cap.sampleRate, _state.value.preset)
        _state.update { it.copy(recording = true, recordingSeconds = 0) }
    }

    fun stopClip() {
        recorder?.let { finishClip(it) }
    }

    private fun finishClip(r: ClipRecorder) {
        if (recorder !== r) return
        recorder = null
        r.finish(analyzer?.result ?: AnalysisResult())
        _state.update {
            it.copy(recording = false, recordingSeconds = 0, clipCount = clips.count(), clipAwaitingLabels = r.name)
        }
    }

    fun saveLabels(key: String, tempo: String, meter: String, notes: String) {
        val name = _state.value.clipAwaitingLabels ?: return
        clips.saveLabels(name, key, tempo, meter, notes)
        _state.update { it.copy(clipAwaitingLabels = null) }
    }

    fun skipLabels() {
        _state.update { it.copy(clipAwaitingLabels = null) }
    }

    fun shareClips() {
        viewModelScope.launch {
            val zip = withContext(Dispatchers.IO) { clips.zipAll() }
            _state.update { it.copy(shareFile = zip) }
        }
    }

    fun shareConsumed() {
        _state.update { it.copy(shareFile = null) }
    }

    fun deleteClips() {
        clips.deleteAll()
        _state.update { it.copy(clipCount = 0) }
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val PREF_PRESET = "preset"
        const val PREF_DARK = "dark"
    }
}
