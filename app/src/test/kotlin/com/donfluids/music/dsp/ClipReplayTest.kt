package com.donfluids.music.dsp

import com.donfluids.music.audio.WavWriter
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays recorded clips through the analyser and prints what it decided next to the labels
 * the user entered. Not a pass/fail test: it is the tuning loop for the presets.
 *
 * Run with the folder of unzipped clips:
 *   ./gradlew :app:testDebugUnitTest --tests '*ClipReplayTest*' -Pclips.dir=/path/to/clips -i --rerun
 */
class ClipReplayTest {
    @Test
    fun replayClips() {
        val dirName = System.getProperty("clips.dir")
        assumeTrue("pass -Pclips.dir=<folder> to replay clips", dirName != null)
        val dir = File(dirName!!)
        val wavs = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name } ?: emptyList()
        assumeTrue("no clips in $dir", wavs.isNotEmpty())
        println("clip | preset | label key / tempo / meter | detected key (conf) / bpm / meter (conf) | tuning")
        for (wav in wavs) {
            val sidecar = File(dir, wav.nameWithoutExtension + ".json")
            val meta = if (sidecar.exists()) org.json.JSONObject(sidecar.readText()) else org.json.JSONObject()
            val labels = meta.optJSONObject("labels") ?: org.json.JSONObject()
            val presetId = meta.optString("preset", "band")
            val (rate, samples) = WavWriter.read(wav)
            // Voices twice: with the standard tracker and with CREPE, for comparison.
            // Band and Guitar twice too: with the spectral chroma and with Basic Pitch.
            val runs: List<Triple<AnalysisPreset, ((Int) -> PitchEstimator)?, Boolean>> = AnalysisPreset.ALL.map { Triple(it, null, false) } +
                listOf(Triple(AnalysisPreset.VOICES, TestPitch.crepe, false), Triple(AnalysisPreset.BAND, null, true), Triple(AnalysisPreset.GUITAR, null, true))
            for ((preset, pitch, bp) in runs) {
                val analyzer = MusicAnalyzer(rate, preset, pitch = pitch, notes = if (bp) { r: Int -> HeardNotes(TestNotes.weights, r) } else null)
                val chunk = 1024
                var i = 0
                while (i < samples.size) {
                    val n = minOf(chunk, samples.size - i)
                    analyzer.process(samples.copyOfRange(i, i + n), n, ((i + n).toDouble() / rate * 1e9).toLong())
                    i += n
                }
                val r = analyzer.result
                val mark = if (preset.id == presetId) "*" else " "
                println(
                    "${wav.nameWithoutExtension} |$mark${preset.id}${if (pitch != null) "+crepe" else ""}${if (bp) "+basicpitch" else ""} | " +
                        "${labels.optString("key", "?")} / ${labels.optString("tempo", "?")} / ${labels.optString("meter", "?")} | " +
                        "${r.key?.label ?: "-"} (${"%.2f".format(r.keyConfidence)}) / " +
                        "${r.bpm?.let { "%.0f".format(it) } ?: "-"} / ${r.meterLabel ?: "-"} (${"%.2f".format(r.meterConfidence)}) | " +
                        "${"%+.0f".format(r.tuningCents)}c" +
                        (if (r.keyFromMelody) " | melody ${r.sungNotes.size} notes" else ""),
                )
            }
        }
    }
}
