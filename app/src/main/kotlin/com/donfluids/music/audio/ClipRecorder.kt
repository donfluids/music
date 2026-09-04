package com.donfluids.music.audio

import android.os.Build
import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.AnalysisResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Records one clip: the raw microphone signal to a WAV file plus a JSON sidecar with the
 * preset, a timeline of what the analyser decided, the chords heard during the clip, and
 * (later) the user's labels.
 *
 * All file work happens on the recorder's own thread; the audio thread only copies samples
 * into a queue. Failures are reported once through [onError].
 *
 * @param analysisStartSeconds the analyser's clock when the clip began, used to keep only the
 * chords heard during the clip.
 */
class ClipRecorder(
    dir: File,
    private val sampleRate: Int,
    private val preset: AnalysisPreset,
    @Volatile var analysisStartSeconds: Float,
    private val maxSeconds: Double = 60.0,
    private val onError: (String) -> Unit = {},
) {
    val name: String = "clip_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val wavFile = File(dir, "$name.wav")
    private val partFile = File(dir, "$name.wav.part")
    val jsonFile = File(dir, "$name.json")

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "LiveKey-clip") }
    private var writer: WavWriter? = null
    private val timeline = JSONArray()
    private var lastTimelineSeconds = -1.0
    private val submitted = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    @Volatile private var failed = false

    /** True once the clip reached [maxSeconds]; the owner should then call [finish]. */
    val full: Boolean get() = submitted.get() >= maxSeconds * sampleRate

    /** Seconds of audio handed to the recorder so far. */
    val seconds: Double get() = submitted.get().toDouble() / sampleRate

    init {
        dir.mkdirs()
        executor.execute {
            try {
                writer = WavWriter(partFile, sampleRate)
            } catch (e: IOException) {
                fail(e)
            }
        }
    }

    /** Called on the audio thread: copies the samples and returns at once. */
    fun write(samples: FloatArray, count: Int) {
        if (closed.get() || failed || full) return
        val copy = samples.copyOf(count)
        submitted.addAndGet(count.toLong())
        submit {
            val w = writer ?: return@submit
            try {
                w.write(copy, count)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Runs [task] on the recorder thread; a task arriving after [finish] is dropped. */
    private fun submit(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // finish() shut the executor down between our closed-check and here.
        }
    }

    /** Notes what the analyser currently thinks, at most twice a second. Audio thread only. */
    fun noteResult(result: AnalysisResult) {
        if (closed.get() || failed) return
        val t = seconds
        if (t - lastTimelineSeconds < 0.5) return
        lastTimelineSeconds = t
        val entry = JSONObject()
            .put("t", round(t))
            .put("key", result.key?.label)
            .put("keyConfidence", round(result.keyConfidence))
            .put("bpm", result.bpm?.let { round(it) })
            .put("meter", result.meterLabel)
            .put("meterConfidence", round(result.meterConfidence))
            .put("chord", result.currentChord?.toString())
        submit { timeline.put(entry) }
    }

    /**
     * Closes the WAV, writes the sidecar and renames the clip into place, all on the recorder
     * thread. Returns at once; [onDone] is called on the recorder thread with true if the clip
     * was saved. Safe to call more than once (later calls are ignored).
     */
    fun finish(finalResult: AnalysisResult, onDone: (saved: Boolean) -> Unit = {}) {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            val w = writer
            writer = null // late write tasks, if any slipped in, find nothing to write to
            var saved = false
            try {
                if (w == null || failed) {
                    runCatching { w?.close() }
                    partFile.delete()
                } else {
                    w.close()
                    jsonFile.writeText(sidecar(finalResult, w.sampleCount.toDouble() / sampleRate).toString(2))
                    if (!partFile.renameTo(wavFile)) throw IOException("Could not finalise ${wavFile.name}")
                    saved = true
                }
            } catch (e: Exception) {
                fail(e)
                runCatching { w?.close() }
                partFile.delete()
                jsonFile.delete()
            }
            onDone(saved)
        }
        executor.shutdown()
    }

    /** Throws the clip away without saving anything. */
    fun discard() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            runCatching { writer?.close() }
            writer = null
            partFile.delete()
        }
        executor.shutdown()
    }

    /** Waits for the recorder thread to finish; for tests and orderly shutdown. */
    fun awaitFinished(timeoutMillis: Long = 5000): Boolean = executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)

    private fun sidecar(finalResult: AnalysisResult, durationSeconds: Double): JSONObject = JSONObject()
        .put("clip", name)
        .put("recordedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
        .put("android", Build.VERSION.SDK_INT) // API level only; no device model
        .put("sampleRate", sampleRate)
        .put("preset", preset.id)
        .put("durationSeconds", round(durationSeconds))
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
                finalResult.chordSegments
                    .filter { it.endSeconds > analysisStartSeconds }
                    .forEach { seg ->
                        arr.put(
                            JSONObject()
                                .put("chord", seg.chord?.toString())
                                .put("start", round((seg.startSeconds - analysisStartSeconds).coerceAtLeast(0f)))
                                .put("end", round(seg.endSeconds - analysisStartSeconds)),
                        )
                    }
            },
        )
        .put("timeline", timeline)
        .put("labels", JSONObject())

    private fun fail(e: Exception) {
        if (failed) return
        failed = true
        onError("Could not write the clip: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun round(v: Float): Double = Math.round(v * 100.0) / 100.0
    private fun round(v: Double): Double = Math.round(v * 100.0) / 100.0
}
