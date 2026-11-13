package com.donfluids.music.dsp

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Not part of the app: the keyeval driver (tools/keyeval). Like tools/skey's KeyReadingsDump, but
 * runs several variants of the key finder on the same clip in lockstep, sharing one Basic Pitch
 * (Guitar, Band) or one CREPE (Voices) between them, so a variant costs only the key logic.
 *
 * A variant is "<memory seconds | inf>:<profile>" (comma-separated in -Pkc.variants). The memory
 * replaces the preset's keyMemorySeconds; the profile replaces KeyDetector's K-K profiles
 * (Guitar/Band) or MelodicKeyFinder's Temperley profiles (Voices), through the ThreadLocal
 * overrides added to the scratch copy. "app" is the app's own profile for that finder.
 * Variant 0 also writes the mode evidence at every key update to <out>/evidence/<id>.csv when
 * -Pkc.evidence=1.
 *
 * Each list line: <input.f32> <id> <outDir> <sampleRate> <preset id>
 * Output: <outDir>/<variant name with ':' -> '_'>/<id>.jsonl (the same JSON lines as KeyReadingsDump).
 * Requires the scratch-copy edits (HeardNotes.source, KeyDetector.override,
 * MelodicKeyFinder.override, MusicAnalyzer.evidenceHook); see tools/keyeval/README.md.
 */
class KeyVariantsDump {
    private fun k(key: com.donfluids.music.music.Key?): Int = if (key == null) -1 else key.tonic + if (key.mode == com.donfluids.music.music.Mode.MINOR) 12 else 0

    /** Records the leader's CREPE results; followers replay them in the same order. */
    private class Recorder(val inner: PitchEstimator) : PitchEstimator {
        val log = ArrayList<Triple<Boolean, Double, Double>>()
        override fun process(buffer: FloatArray, end: Int): Boolean {
            val v = inner.process(buffer, end)
            log += Triple(v, inner.hz, inner.clarity)
            return v
        }
        override val hz: Double get() = inner.hz
        override val clarity: Double get() = inner.clarity
        override val samplesNeeded: Int get() = inner.samplesNeeded
    }

    private class Replayer(val rec: Recorder) : PitchEstimator {
        private var i = 0
        private var cur = Triple(false, 0.0, 0.0)
        override fun process(buffer: FloatArray, end: Int): Boolean { cur = rec.log[i++]; return cur.first }
        override val hz: Double get() = cur.second
        override val clarity: Double get() = cur.third
        override val samplesNeeded: Int get() = rec.inner.samplesNeeded
    }

    @Test
    fun dump() {
        val listName = System.getProperty("kc.list")
        assumeTrue("pass -Pkc.list=<file>", listName != null)
        val variants = (System.getProperty("kc.variants") ?: "app:app").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val evidence = System.getProperty("kc.evidence") == "1"
        val lines = File(listName!!).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val threads = (System.getProperty("kc.threads") ?: "3").toInt()
        val pool = Executors.newFixedThreadPool(threads)
        for (line in lines) pool.execute {
            val p = line.split(" ")
            val id = p[1]
            val outDir = File(p[2])
            val outs = variants.map { File(outDir, it.replace(':', '_') + "/" + id + ".jsonl") }
            if (outs.all { it.exists() }) return@execute
            try {
                val rate = p[3].toInt()
                val base = AnalysisPreset.byId(p[4])
                val bytes = File(p[0]).readBytes()
                val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val audio = FloatArray(buf.remaining()).also { buf.get(it) }
                var leaderNotes: HeardNotes? = null
                var recorder: Recorder? = null
                val profiles = ArrayList<Array<DoubleArray>?>()
                val analyzers = variants.mapIndexed { vi, v ->
                    val (mem, prof) = v.split(":")
                    profiles += KeyProfiles.byName(prof)
                    val preset = if (mem == "app") base else base.copy(keyMemorySeconds = if (mem == "inf") Double.POSITIVE_INFINITY else mem.toDouble())
                    MusicAnalyzer(
                        rate, preset,
                        pitch = if (preset.melodicKey) { r: Int ->
                            if (vi == 0) Recorder(TestPitch.crepe(r)).also { recorder = it } else Replayer(recorder!!)
                        } else null,
                        notes = if (!preset.melodicKey) { r: Int ->
                            if (vi == 0) HeardNotes(TestNotes.weights, r).also { leaderNotes = it }
                            else HeardNotes(TestNotes.weights, r).also { it.source = leaderNotes }
                        } else null,
                    )
                }
                val melodic = base.melodicKey
                val ev = StringBuilder()
                if (evidence) analyzers[0].evidenceHook = { now, found, e, scale, fromChords, songKey ->
                    ev.append("%.3f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.3f,%d,%d\n".format(
                        now, k(found), e.endings, e.leadingTone, e.approaches, e.chords, e.profile, e.prior, scale,
                        if (fromChords) 1 else 0, k(songKey)))
                }
                val chords = StringBuilder()
                val sbs = variants.map { StringBuilder() }
                val baseT = 10_000_000_000L
                var next = 1.0
                var fed = 0
                val chunk = 1024
                val started = System.nanoTime()
                while (fed + chunk <= audio.size) {
                    val piece = audio.copyOfRange(fed, fed + chunk)
                    val at = baseT + ((fed + chunk).toDouble() / rate * 1e9).toLong()
                    for ((vi, a) in analyzers.withIndex()) {
                        if (melodic) MelodicKeyFinder.override.set(profiles[vi]) else KeyDetector.override.set(profiles[vi])
                        a.process(piece.copyOf(), chunk, at)
                    }
                    MelodicKeyFinder.override.set(null); KeyDetector.override.set(null)
                    fed += chunk
                    val t = fed.toDouble() / rate
                    if (t >= next) {
                        if (evidence && !melodic) {
                            val segs = analyzers[0].result.chordSegments.filter { it.chord != null }
                            chords.append("{\"moment\":$next,\"seg\":[")
                                .append(segs.joinToString(",") { "[${it.chord!!.root},${if (it.chord!!.minor) 1 else 0},${"%.3f".format(it.startSeconds)},${"%.3f".format(it.endSeconds)}]" })
                                .append("]}\n")
                        }
                        for ((vi, a) in analyzers.withIndex()) {
                            val r = a.result
                            sbs[vi].append("{\"t\":${"%.6f".format(t)},\"moment\":$next,\"key\":${k(r.key)},\"display\":${k(r.displayKey)},")
                                .append("\"fromChords\":${r.displayKeyFromChords},\"modeDecided\":${r.modeDecided},\"pairDecided\":${r.pairDecided},")
                                .append("\"modeScore\":${"%.4f".format(r.modeScore)},\"keyConf\":${"%.4f".format(r.keyConfidence)},")
                                .append("\"songKey\":${k(r.songKey)},\"songConf\":${"%.4f".format(r.songKeyConfidence)},")
                                .append("\"tuning\":${"%.2f".format(r.tuningCents)},\"fromMelody\":${r.keyFromMelody}}\n")
                        }
                        while (next <= t) next += 1.0
                    }
                }
                val done = "{\"done\":true,\"seconds\":${audio.size.toDouble() / rate},\"cpuMs\":${"%.1f".format((System.nanoTime() - started) / 1e6)},\"variants\":${variants.size}}\n"
                if (evidence) {
                    val f = File(outDir, "evidence/$id.csv"); f.parentFile.mkdirs()
                    val tmp = File(f.path + ".tmp"); tmp.writeText(ev.toString()); tmp.renameTo(f)
                    if (!melodic) { val g = File(outDir, "chords/$id.jsonl"); g.parentFile.mkdirs(); val t2 = File(g.path + ".tmp"); t2.writeText(chords.toString()); t2.renameTo(g) }
                }
                for ((vi, out) in outs.withIndex()) {
                    out.parentFile.mkdirs()
                    val tmp = File(out.path + ".tmp")
                    tmp.writeText(sbs[vi].toString() + done)
                    tmp.renameTo(out)
                }
            } catch (e: Throwable) {
                System.err.println("failed $line: $e")
                e.printStackTrace()
            }
        }
        pool.shutdown()
        pool.awaitTermination(48, TimeUnit.HOURS)
    }
}

/**
 * Key profiles, tonic first, (major, minor). Numbers copied from AMADS (MIT licence),
 * github.com/music-computing/amads, amads/pitch/key/profiles.py (see tools/keyeval/README.md
 * for the commit). "app" = null: the finder's own profiles.
 */
object KeyProfiles {
    val KK = arrayOf(
        doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88),
        doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17))
    val T99 = arrayOf(
        doubleArrayOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0),
        doubleArrayOf(5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0))
    val AE = arrayOf(
        doubleArrayOf(17.7661, 0.145624, 14.9265, 0.160186, 19.8049, 11.3587, 0.291248, 22.062, 0.145624, 8.15494, 0.232998, 4.95122),
        doubleArrayOf(18.2648, 0.737619, 14.0499, 16.8599, 0.702494, 14.4362, 0.702494, 18.6161, 4.56621, 1.93186, 7.37619, 1.75623))
    val TDCM = arrayOf(
        doubleArrayOf(0.223, 0.001, 0.158, 0.015, 0.194, 0.071, 0.002, 0.169, 0.003, 0.119, 0.008, 0.035),
        doubleArrayOf(0.317, 0.001, 0.09, 0.159, 0.046, 0.097, 0.007, 0.131, 0.009, 0.047, 0.087, 0.009))
    val TDCH = arrayOf(
        doubleArrayOf(0.231, 0.002, 0.091, 0.004, 0.149, 0.111, 0.004, 0.193, 0.004, 0.126, 0.011, 0.076),
        doubleArrayOf(0.202, 0.006, 0.102, 0.127, 0.047, 0.113, 0.005, 0.177, 0.046, 0.051, 0.09, 0.034))
    val AS = arrayOf(
        doubleArrayOf(0.238, 0.006, 0.111, 0.006, 0.137, 0.094, 0.016, 0.214, 0.009, 0.08, 0.008, 0.081),
        doubleArrayOf(0.220, 0.006, 0.104, 0.123, 0.019, 0.103, 0.012, 0.214, 0.062, 0.022, 0.061, 0.052))

    fun byName(n: String): Array<DoubleArray>? = when (n) {
        "app" -> null
        "kk" -> KK
        "t99" -> T99
        "ae" -> AE
        "tdcm" -> TDCM
        "tdch" -> TDCH
        "as" -> AS
        else -> error("unknown profile $n")
    }
}
