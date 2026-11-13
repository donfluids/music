package com.donfluids.music.dsp

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Not part of the app: the S-KEY evaluation's JVM driver (tools/skey). Runs the Kotlin
 * MusicAnalyzer with the app's default models, Basic Pitch for Band and Guitar ("Chords and key")
 * and CREPE for Voices, over raw audio and writes the key readout once a second, in the same
 * JSON lines as the Swift kckey driver. Copy into analysis/src/test/kotlin/com/donfluids/music/dsp of
 * a scratch copy of the repo and run:
 *   ./gradlew --offline :analysis:testDebugUnitTest --tests '*KeyReadingsDump*' -Pkc.list=/path/list.txt -Pkc.threads=3
 * (analysis/build.gradle.kts must pass kc.list and kc.threads on as system properties; see README).
 * Each line of the list: <input.f32> <output.jsonl> <sampleRate> <preset id>
 */
class KeyReadingsDump {
    private fun k(key: com.donfluids.music.music.Key?): Int = if (key == null) -1 else key.tonic + if (key.mode == com.donfluids.music.music.Mode.MINOR) 12 else 0

    @Test
    fun dump() {
        val listName = System.getProperty("kc.list")
        assumeTrue("pass -Pkc.list=<file>", listName != null)
        val lines = File(listName!!).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val threads = (System.getProperty("kc.threads") ?: "3").toInt()
        val pool = Executors.newFixedThreadPool(threads)
        for (line in lines) pool.execute {
            val p = line.split(" ")
            val out = File(p[1])
            if (out.exists()) return@execute
            try {
                val rate = p[2].toInt()
                val preset = AnalysisPreset.byId(p[3])
                val bytes = File(p[0]).readBytes()
                val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val audio = FloatArray(buf.remaining()).also { buf.get(it) }
                val a = MusicAnalyzer(
                    rate, preset,
                    pitch = if (preset.melodicKey) TestPitch.crepe else null,
                    notes = if (!preset.melodicKey) { r: Int -> HeardNotes(TestNotes.weights, r) } else null,
                )
                val sb = StringBuilder()
                val base = 10_000_000_000L
                var next = 1.0
                var fed = 0
                val chunk = 1024
                val started = System.nanoTime()
                while (fed + chunk <= audio.size) {
                    a.process(audio.copyOfRange(fed, fed + chunk), chunk, base + ((fed + chunk).toDouble() / rate * 1e9).toLong())
                    fed += chunk
                    val t = fed.toDouble() / rate
                    if (t >= next) {
                        val r = a.result
                        val alts = r.keyAlternatives.joinToString(",") { "[${k(it.first)},${"%.4f".format(it.second)}]" }
                        sb.append("{\"t\":${"%.6f".format(t)},\"moment\":$next,\"key\":${k(r.key)},\"display\":${k(r.displayKey)},")
                            .append("\"fromChords\":${r.displayKeyFromChords},\"modeDecided\":${r.modeDecided},\"pairDecided\":${r.pairDecided},")
                            .append("\"modeScore\":${"%.4f".format(r.modeScore)},\"keyConf\":${"%.4f".format(r.keyConfidence)},")
                            .append("\"keyScore\":${"%.4f".format(r.keyScore)},\"songKey\":${k(r.songKey)},\"songConf\":${"%.4f".format(r.songKeyConfidence)},")
                            .append("\"keyNow\":${k(r.keyNow)},\"tuning\":${"%.2f".format(r.tuningCents)},\"fromMelody\":${r.keyFromMelody},\"alts\":[$alts]}\n")
                        while (next <= t) next += 1.0
                    }
                }
                sb.append("{\"done\":true,\"seconds\":${audio.size.toDouble() / rate},\"cpuMs\":${"%.1f".format((System.nanoTime() - started) / 1e6)}}\n")
                val tmp = File(p[1] + ".tmp")
                tmp.writeText(sb.toString())
                tmp.renameTo(out)
            } catch (e: Throwable) {
                System.err.println("failed $line: $e")
            }
        }
        pool.shutdown()
        pool.awaitTermination(48, TimeUnit.HOURS)
    }
}
