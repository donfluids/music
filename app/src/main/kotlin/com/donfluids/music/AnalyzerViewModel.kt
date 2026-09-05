package com.donfluids.music

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.donfluids.music.audio.AudioCapture
import com.donfluids.music.audio.ClipRecorder
import com.donfluids.music.audio.ClipStore
import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.dsp.MusicAnalyzer
import com.donfluids.music.music.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

data class UiState(
    val listening: Boolean = false,
    val error: String? = null,
    val preset: AnalysisPreset = AnalysisPreset.BAND,
    /** Null follows the system setting; true or false is the user's choice. */
    val darkTheme: Boolean? = null,
    /** Display-only multiplier for the tempo (0.5, 1 or 2) when the tracker picks the wrong pulse. */
    val tempoMultiplier: Float = 1f,
    /** The key frozen by Hold, or null when the display follows the analysis. */
    val heldKey: Key? = null,
    /** Semitones added to every displayed key and chord: what to play, not what sounds. */
    val transpose: Int = 0,
    /** Show Nashville numbers instead of chord letters. */
    val nashville: Boolean = false,
    /** True while the first-clip notice is waiting to be accepted. */
    val clipNoticePending: Boolean = false,
    val analysis: AnalysisResult = AnalysisResult(),
    val recording: Boolean = false,
    val recordingSeconds: Int = 0,
    val clipCount: Int = 0,
    /** A clip that has just finished and is waiting for the user's labels. */
    val clipAwaitingLabels: String? = null,
    /** A zip ready to hand to the share sheet; the screen consumes and clears it. */
    val shareFile: File? = null,
)

/**
 * Owns the microphone session. Threading rules: the audio thread reads [analyzer] and
 * [recorder] and never blocks on files; the main thread never mutates an analyser in use
 * (it swaps in a new one instead); clip hand-over is a compare-and-set so only one side
 * finishes a clip.
 */
class AnalyzerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("livekey", Context.MODE_PRIVATE)
    private val clips = ClipStore(application)

    private val _state = MutableStateFlow(
        UiState(
            preset = AnalysisPreset.byId(prefs.getString(PREF_PRESET, null)),
            darkTheme = if (prefs.contains(PREF_DARK)) prefs.getBoolean(PREF_DARK, false) else null,
            nashville = prefs.getBoolean(PREF_NASHVILLE, false),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One microphone session: its capture thread only ever sees its own analyser. */
    private class Session(val capture: AudioCapture, @Volatile var analyzer: MusicAnalyzer)

    @Volatile
    private var session: Session? = null
    private val analyzer: MusicAnalyzer? get() = session?.analyzer
    private val recorder = AtomicReference<ClipRecorder?>(null)
    @Volatile private var lastReportedSecond = -1

    /** The microphone is released whenever the app leaves the foreground. */
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) stop()
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        viewModelScope.launch(Dispatchers.IO) {
            clips.cleanUp()
            val count = clips.count()
            _state.update { it.copy(clipCount = count) }
        }
    }

    fun start() {
        if (_state.value.listening || session != null) return
        // The capture thread may deliver audio before the session object exists; the holder
        // is an AtomicReference so the assignment is visible to it as soon as it is made.
        val holder = AtomicReference<Session?>(null)
        val cap = AudioCapture(
            getApplication(),
            listener = { samples, count, now -> holder.get()?.let { onAudio(it, samples, count, now) } },
            onError = { message -> holder.get()?.let { onCaptureError(it, message) } },
        )
        try {
            cap.start()
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Could not open the microphone") }
            return
        }
        val newSession = Session(cap, MusicAnalyzer(cap.sampleRate, _state.value.preset))
        holder.set(newSession)
        session = newSession
        _state.update { it.copy(listening = true, error = null, analysis = AnalysisResult(), heldKey = null, transpose = 0) }
    }

    /** Runs on the audio thread of [s]; a stale thread only ever touches its own session. */
    private fun onAudio(s: Session, samples: FloatArray, count: Int, now: Long) {
        val a = s.analyzer
        val changed = a.process(samples, count, now)
        val r = recorder.get()
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

    /** Runs on the audio thread, once, after which that thread exits on its own. */
    private fun onCaptureError(s: Session, message: String) {
        if (session !== s) return
        session = null
        recorder.get()?.let { finishClip(it) }
        _state.update { it.copy(listening = false, error = message) }
    }

    fun stop() {
        val s = session ?: return
        session = null
        s.capture.stop() // the audio thread is done before the clip is finished
        recorder.get()?.let { finishClip(it) }
        _state.update { it.copy(listening = false, heldKey = null) }
    }

    /** Forget everything heard so far: a fresh analyser replaces the old one, never mutated in place. */
    fun reset() {
        session?.let { it.analyzer = MusicAnalyzer(it.capture.sampleRate, _state.value.preset) }
        recorder.get()?.analysisStartSeconds = 0f // the clip's chord filter follows the new clock
        _state.update { it.copy(analysis = AnalysisResult(), tempoMultiplier = 1f, heldKey = null, transpose = 0) }
    }

    /** Freeze the shown key so nothing changes under the player's hands; tap again to release. */
    fun toggleHold() {
        _state.update {
            if (it.heldKey != null) it.copy(heldKey = null)
            else it.copy(heldKey = it.analysis.displayKey ?: it.analysis.key)
        }
    }

    fun setTranspose(semitones: Int) {
        _state.update { it.copy(transpose = semitones.coerceIn(-6, 6)) }
    }

    fun setNashville(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_NASHVILLE, enabled).apply()
        _state.update { it.copy(nashville = enabled) }
    }

    /** Switch presets. While listening, analysis starts over with the new settings. */
    fun setPreset(preset: AnalysisPreset) {
        if (preset == _state.value.preset) return
        prefs.edit().putString(PREF_PRESET, preset.id).apply()
        session?.let { it.analyzer = MusicAnalyzer(it.capture.sampleRate, preset) }
        recorder.get()?.analysisStartSeconds = 0f
        _state.update { it.copy(preset = preset, analysis = AnalysisResult(), tempoMultiplier = 1f, heldKey = null, transpose = 0) }
    }

    fun setTempoMultiplier(multiplier: Float) {
        _state.update { it.copy(tempoMultiplier = multiplier) }
    }

    fun setDarkTheme(dark: Boolean) {
        prefs.edit().putBoolean(PREF_DARK, dark).apply()
        _state.update { it.copy(darkTheme = dark) }
    }

    fun reportPermissionDenied(message: String) {
        _state.update { it.copy(error = message) }
    }

    // ---- Clips ----

    fun startClip() {
        val s = session ?: return
        if (recorder.get() != null) return
        if (!prefs.getBoolean(PREF_CLIP_NOTICE, false)) {
            _state.update { it.copy(clipNoticePending = true) }
            return
        }
        lastReportedSecond = -1
        val r = ClipRecorder(
            dir = clips.dir,
            sampleRate = s.capture.sampleRate,
            preset = _state.value.preset,
            analysisStartSeconds = s.analyzer.result.secondsHeard,
            onError = { message -> _state.update { it.copy(error = message) } },
        )
        if (!recorder.compareAndSet(null, r)) {
            r.discard()
            return
        }
        _state.update { it.copy(recording = true, recordingSeconds = 0, error = null) }
    }

    /** The user has read the recording notice; remember it and start the clip they asked for. */
    fun acceptClipNotice() {
        prefs.edit().putBoolean(PREF_CLIP_NOTICE, true).apply()
        _state.update { it.copy(clipNoticePending = false) }
        startClip()
    }

    fun dismissClipNotice() {
        _state.update { it.copy(clipNoticePending = false) }
    }

    fun stopClip() {
        recorder.get()?.let { finishClip(it) }
    }

    /** Whichever thread wins the compare-and-set finishes the clip; the other returns. */
    private fun finishClip(r: ClipRecorder) {
        if (!recorder.compareAndSet(r, null)) return
        _state.update { it.copy(recording = false, recordingSeconds = 0) }
        r.finish(analyzer?.result ?: AnalysisResult()) { saved ->
            if (saved) _state.update { it.copy(clipCount = it.clipCount + 1, clipAwaitingLabels = r.name) }
        }
    }

    fun saveLabels(key: String, tempo: String, meter: String, notes: String) {
        val name = _state.value.clipAwaitingLabels ?: return
        _state.update { it.copy(clipAwaitingLabels = null) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { clips.saveLabels(name, key, tempo, meter, notes) } }
    }

    fun skipLabels() {
        _state.update { it.copy(clipAwaitingLabels = null) }
    }

    fun shareClips() {
        viewModelScope.launch {
            val zip = withContext(Dispatchers.IO) { runCatching { clips.zipAll() } }
            zip.onSuccess { file -> _state.update { it.copy(shareFile = file) } }
                .onFailure { e -> _state.update { it.copy(error = "Could not prepare the clips: ${e.message}") } }
        }
    }

    fun shareConsumed() {
        _state.update { it.copy(shareFile = null) }
    }

    fun deleteClips() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { clips.deleteAll() }
            val count = clips.count()
            _state.update { it.copy(clipCount = count) }
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        stop()
    }

    private companion object {
        const val PREF_PRESET = "preset"
        const val PREF_DARK = "dark"
        const val PREF_NASHVILLE = "nashville"
        const val PREF_CLIP_NOTICE = "clipNoticeAccepted"
    }
}
