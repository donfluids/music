package com.donfluids.music.dsp

import com.donfluids.music.audio.WavWriter
import com.donfluids.music.music.ChordSegment
import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Scores chords and key against labelled recordings, the spectral chroma against Basic Pitch.
 * Not a pass/fail test: the measuring loop for choosing what ships.
 *
 * The folder holds audio/ (WAV) and labels/ (one JSON per recording, named like the WAV less
 * any "_mic" suffix): {"key": [tonic, "major"|"minor"], "chords": [[start, end, root, "maj"|"min"|"other"], ...]}.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*ChordKeyEvalTest*' -Peval.dir=/path -i --rerun
 *
 * Chords: the share of labelled maj or min time where the chord shown for that moment (as dated
 * in the song's chord list) has the same root and the same major or minor third. Sevenths count
 * with their triad; sus chords count as wrong. Key: the MIREX score of the key shown at the end
 * (1 same, 0.5 a fifth away, 0.3 relative, 0.2 parallel).
 */
class ChordKeyEvalTest {
    private data class Labels(val key: Key?, val chords: List<Triple<Double, Double, Pair<Int, String>>>)
    private data class Score(val file: String, val system: String, val chordRight: Double, val chordTotal: Double, val rootRight: Double, val keyScore: Double?, val key: Key?, val ms: Double, val shifted: DoubleArray = DoubleArray(0))

    private fun labels(f: File): Labels {
        val j = JSONObject(f.readText())
        val k = j.getJSONArray("key")
        val ch = j.getJSONArray("chords")
        return Labels(
            if (j.optBoolean("nokey")) null else Key(k.getInt(0), if (k.getString(1) == "minor") Mode.MINOR else Mode.MAJOR),
            (0 until ch.length()).map { i -> ch.getJSONArray(i).let { Triple(it.getDouble(0), it.getDouble(1), it.getInt(2) to it.getString(3)) } },
        )
    }

    private fun mirex(est: Key?, ref: Key): Double = when {
        est == null -> 0.0
        est == ref -> 1.0
        est.mode == ref.mode && (Math.floorMod(est.tonic - ref.tonic, 12) == 7 || Math.floorMod(est.tonic - ref.tonic, 12) == 5) -> 0.5
        ref.mode == Mode.MAJOR && est.mode == Mode.MINOR && est.tonic == Math.floorMod(ref.tonic + 9, 12) -> 0.3
        ref.mode == Mode.MINOR && est.mode == Mode.MAJOR && est.tonic == Math.floorMod(ref.tonic + 3, 12) -> 0.3
        est.tonic == ref.tonic -> 0.2
        else -> 0.0
    }

    private fun score(name: String, system: String, segments: List<ChordSegment>, key: Key?, l: Labels, ms: Double): Score {
        val shifted = DoubleArray(SHIFTS.size) { si -> chordRight(segments, l, SHIFTS[si]) }
        var right = 0.0; var total = 0.0; var root = 0.0
        val step = 0.02
        for ((start, end, chord) in l.chords) {
            if (chord.second == "other") continue
            var t = start + step / 2
            while (t < end) {
                total += step
                val est = segments.firstOrNull { t >= it.startSeconds && t < it.endSeconds }?.chord
                if (est != null && est.root == chord.first) {
                    root += step
                    if (!est.type.isSus && est.minor == (chord.second == "min")) right += step
                }
                t += step
            }
        }
        return Score(name, system, right, total, root, l.key?.let { mirex(key, it) }, key, ms, shifted)
    }

    /** Right chord time with the estimate moved [shift] seconds later: shows any dating error. */
    private fun chordRight(segments: List<ChordSegment>, l: Labels, shift: Double): Double {
        var right = 0.0
        val step = 0.02
        for ((start, end, chord) in l.chords) {
            if (chord.second == "other") continue
            var t = start + step / 2
            while (t < end) {
                val est = segments.firstOrNull { t - shift >= it.startSeconds && t - shift < it.endSeconds }?.chord
                if (est != null && est.root == chord.first && !est.type.isSus && est.minor == (chord.second == "min")) right += step
                t += step
            }
        }
        return right
    }
    private val SHIFTS = doubleArrayOf(-0.6, -0.4, -0.2, 0.0, 0.2, 0.4, 0.6)

    @Test
    fun evaluate() {
        val dirName = System.getProperty("eval.dir")
        assumeTrue("pass -Peval.dir=<folder> to evaluate", dirName != null)
        val dir = File(dirName!!)
        val filter = System.getProperty("eval.filter") ?: ""
        val wavs = File(dir, "audio").listFiles { f -> f.name.endsWith(".wav") && f.name.contains(filter) }!!.sortedBy { it.name }
        val all: List<Triple<String, AnalysisPreset, ((Int) -> HeardNotes)?>> = listOf(
            Triple("guitar", AnalysisPreset.GUITAR, null),
            Triple("guitar+bp", AnalysisPreset.GUITAR, { rate: Int -> HeardNotes(TestNotes.weights, rate) }),
            Triple("bp-plain", AnalysisPreset.GUITAR, { rate: Int -> HeardNotes(TestNotes.weights, rate, bassBoost = 0f, dateShift = 0.0) }),
            Triple("bp-follow", AnalysisPreset.GUITAR, { rate: Int -> HeardNotes(TestNotes.weights, rate, followTuning = true) }),
            Triple("band", AnalysisPreset.BAND, null),
            Triple("band+bp", AnalysisPreset.BAND, { rate: Int -> HeardNotes(TestNotes.weights, rate) }),
        )
        val wanted = (System.getProperty("eval.systems") ?: "guitar,guitar+bp").split(",").toSet()
        val systems = all.filter { it.first in wanted }
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        val results = java.util.Collections.synchronizedList(ArrayList<Score>())
        for (wav in wavs) for ((system, preset, notes) in systems) pool.execute {
            val name = wav.nameWithoutExtension.removeSuffix("_mic")
            val l = labels(File(dir, "labels/$name.json"))
            val (rate, samples) = WavWriter.read(wav)
            val a = MusicAnalyzer(rate, preset, notes = notes)
            val started = System.nanoTime()
            var i = 0
            while (i < samples.size) { val n = minOf(1024, samples.size - i); a.process(samples.copyOfRange(i, i + n), n, ((i + n).toDouble() / rate * 1e9).toLong()); i += n }
            // Let the last chords and the read-behind finish: a second of silence.
            val tail = FloatArray(1024)
            repeat((2.0 * rate / 1024).toInt()) { a.process(tail, 1024, 0L) }
            val r = a.result
            results += score(name, system, r.chordSegments, r.displayKey, l, (System.nanoTime() - started) / 1e6)
        }
        pool.shutdown(); pool.awaitTermination(6, TimeUnit.HOURS)
        val out = File(dir, "results-${System.currentTimeMillis()}.csv")
        out.writeText("file,system,chord_right,chord_total,root_right,key_score,key,ms\n" + results.sortedWith(compareBy({ it.file }, { it.system })).joinToString("\n") { s ->
            "${s.file},${s.system},${"%.2f".format(s.chordRight)},${"%.2f".format(s.chordTotal)},${"%.2f".format(s.rootRight)},${s.keyScore},${s.key?.label},${"%.0f".format(s.ms)}"
        })
        println("written $out")
        fun report(label: String, pick: (Score) -> Boolean) {
            for ((system, _, _) in systems) {
                val rs = results.filter { it.system == system && pick(it) }
                if (rs.isEmpty()) continue
                // GuitarSet's solos carry the lead sheet's chords but play only the melody.
                val chords = rs.filter { !it.file.endsWith("solo") }
                val keyed = rs.mapNotNull { it.keyScore }
                val acc = chords.sumOf { it.chordRight } / chords.sumOf { it.chordTotal }.coerceAtLeast(1e-9)
                val rootAcc = chords.sumOf { it.rootRight } / chords.sumOf { it.chordTotal }.coerceAtLeast(1e-9)
                val total = chords.sumOf { it.chordTotal }.coerceAtLeast(1e-9)
                val byShift = SHIFTS.indices.joinToString(" ") { si -> "%+.1f:%.3f".format(SHIFTS[si], chords.sumOf { it.shifted[si] } / total) }
                if (label == "all") println("SHIFT %-10s %s".format(system, byShift))
                println("EVAL %-8s %-10s files=%3d chords(majmin)=%.3f root=%.3f key(mirex)=%.3f key exact=%.2f".format(label, system, rs.size, acc, rootAcc, if (keyed.isEmpty()) Double.NaN else keyed.average(), if (keyed.isEmpty()) Double.NaN else keyed.count { it == 1.0 }.toDouble() / keyed.size))
            }
        }
        report("all") { true }

        report("comp") { it.file.endsWith("comp") }
        report("solo") { it.file.endsWith("solo") }
        for (style in listOf("BN", "Funk", "Jazz", "Rock", "SS")) report(style) { it.file.contains("_$style") }
    }
}
