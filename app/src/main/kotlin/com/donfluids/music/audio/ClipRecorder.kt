package com.donfluids.music.audio

import android.os.Build
import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.AnalysisResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records one clip: the raw microphone signal to a WAV file plus a JSON sidecar with the
 * preset, a timeline of what the analyser decided, and (later) the user's labels.
 *
 * The clips are the raw material for tuning the presets against a real room: a replay test
 * runs them through the same pipeline offline and compares against the labels.
 */
class ClipRecorder(
    private val dir: File,
    private val sampleRate: Int,
    private val preset: AnalysisPreset,
    private val maxSeconds: Double = 60.0,
) {
    val name: String = "clip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val wavFile = File(dir, "$name.wav")
    val jsonFile = File(dir, "$name.json")

    private val writer: WavWriter
    private val timeline = JSONArray()
    private var lastTimelineSeconds = -1.0
    @Volatile private var closed = false

    /** True once the clip reached [maxSeconds]; the owner should then call [finish]. */
    @Volatile var full = false
        private set

    val seconds: Double get() = writer.sampleCount.toDouble() / sampleRate

    init {
        dir.mkdirs()
        writer = WavWriter(wavFile, sampleRate)
    }

    /** Called on the audio thread. */
    fun write(samples: FloatArray, count: Int) {
        if (closed || full) return
        writer.write(samples, count)
        if (seconds >= maxSeconds) full = true
    }

    /** Notes what the analyser currently thinks, at most twice a second. */
    fun noteResult(result: AnalysisResult) {
        if (closed) return
        val t = seconds
        if (t - lastTimelineSeconds < 0.5) return
        lastTimelineSeconds = t
        timeline.put(
            JSONObject()
                .put("t", round(t))
                .put("key", result.key?.label)
                .put("keyConfidence", round(result.keyConfidence))
                .put("bpm", result.bpm?.let { round(it) })
                .put("meter", result.meterLabel)
                .put("meterConfidence", round(result.meterConfidence))
                .put("chord", result.currentChord?.toString()),
        )
    }

    /** Closes the WAV and writes the sidecar. Safe to call once. */
    fun finish(finalResult: AnalysisResult) {
        if (closed) return
        closed = true
        writer.close()
        val json = JSONObject()
            .put("clip", name)
            .put("recordedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
            .put("sampleRate", sampleRate)
            .put("preset", preset.id)
            .put("durationSeconds", round(seconds))
            .put(
                "final",
                JSONObject()
                    .put("key", finalResult.key?.label)
                    .put("keyConfidence", round(finalResult.keyConfidence))
                    .put("tuningCents", round(finalResult.tuningCents))
                    .put("bpm", finalResult.bpm?.let { round(it) })
                    .put("tempoStrength", round(finalResult.tempoStrength))
                    .put("meter", finalResult.meterLabel)
                    .put("meterConfidence", round(finalResult.meterConfidence))
                    .put("songKey", finalResult.songKey?.label)
                    .put("songKeyConfidence", round(finalResult.songKeyConfidence)),
            )
            .put(
                "chords",
                JSONArray().also { arr ->
                    finalResult.chordSegments.forEach { seg ->
                        arr.put(JSONObject().put("chord", seg.chord?.toString()).put("start", round(seg.startSeconds)).put("end", round(seg.endSeconds)))
                    }
                },
            )
            .put("timeline", timeline)
            .put("labels", JSONObject())
        jsonFile.writeText(json.toString(2))
    }

    private fun round(v: Float): Double = Math.round(v * 100.0) / 100.0
    private fun round(v: Double): Double = Math.round(v * 100.0) / 100.0
}
