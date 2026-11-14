package com.donfluids.music.dsp

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Not part of the app: the tools/keyeval/detune tracer. Runs the Voices analyser over a clip and
 * writes, once a second, the reading (the same fields as KeyVariantsDump, so score_variants.py
 * reads it) plus the singer model, the key-level tracker and the melodic ranking; at the live
 * moments (10, 14, 18, ... s) also the notes the key finders were given.
 *
 * CREPE runs once per clip: its answers are saved to a cache file and replayed for later runs
 * (the audio is still fed, for the chroma the resolver reads), so the downstream stages can be
 * re-run cheaply with scratch fixes switched on (DetuneFix flags).
 *
 * Each list line: <input.f32> <id> <outDir> <sampleRate> <preset id> <crepe cache>
 * -Pdt.variants=base,fixA,fixA+fixB ... : one output directory per variant; "+" joins flags.
 */
class DetuneTrace {
    private fun k(key: com.donfluids.music.music.Key?): Int = if (key == null) -1 else key.tonic + if (key.mode == com.donfluids.music.music.Mode.MINOR) 12 else 0

    private class Recorder(val inner: PitchEstimator) : PitchEstimator {
        val voiced = ArrayList<Boolean>(); val hzs = ArrayList<Double>(); val cl = ArrayList<Double>()
        override fun process(buffer: FloatArray, end: Int): Boolean {
            val v = inner.process(buffer, end)
            voiced += v; hzs += inner.hz; cl += inner.clarity
            return v
        }
        override val hz: Double get() = inner.hz
        override val clarity: Double get() = inner.clarity
        override val samplesNeeded: Int get() = inner.samplesNeeded
        fun save(f: File) {
            val tmp = File(f.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { o ->
                o.writeInt(voiced.size)
                for (i in voiced.indices) { o.writeBoolean(voiced[i]); o.writeDouble(hzs[i]); o.writeDouble(cl[i]) }
            }
            tmp.renameTo(f)
        }
    }

    private class Replayer(f: File, override val samplesNeeded: Int) : PitchEstimator {
        val voiced: BooleanArray; val hzs: DoubleArray; val cl: DoubleArray
        init {
            DataInputStream(f.inputStream().buffered()).use { s ->
                val n = s.readInt(); voiced = BooleanArray(n); hzs = DoubleArray(n); cl = DoubleArray(n)
                for (i in 0 until n) { voiced[i] = s.readBoolean(); hzs[i] = s.readDouble(); cl[i] = s.readDouble() }
            }
        }
        private var i = -1
        override fun process(buffer: FloatArray, end: Int): Boolean { i++; return voiced[i] }
        override val hz: Double get() = hzs[i]
        override val clarity: Double get() = cl[i]
    }

    @Test
    fun trace() {
        val listName = System.getProperty("dt.list")
        assumeTrue("pass -Pdt.list=<file>", listName != null)
        val variants = (System.getProperty("dt.variants") ?: "base").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val lines = File(listName!!).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val threads = (System.getProperty("dt.threads") ?: "3").toInt()
        val pool = Executors.newFixedThreadPool(threads)
        for (line in lines) pool.execute {
            val p = line.split(" ")
            val id = p[1]
            val outDir = File(p[2])
            val cache = File(p[5])
            try {
                val rate = p[3].toInt()
                val preset = AnalysisPreset.byId(p[4])
                for (variant in variants) {
                    val out = File(outDir, "$variant/$id.jsonl")
                    if (out.exists()) continue
                    val flags = if (variant == "base") emptySet() else variant.split("+").toSet()
                    var recorder: Recorder? = null
                    val haveCache = cache.exists()
                    val bytes = File(p[0]).readBytes()
                    val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val audio = FloatArray(buf.remaining()).also { buf.get(it) }
                    DetuneFix.flags.set(flags)
                    val a = MusicAnalyzer(rate, preset, pitch = { r: Int ->
                        val c = TestPitch.crepe(r)
                        if (haveCache) Replayer(cache, c.samplesNeeded) else Recorder(c).also { recorder = it }
                    })
                    val total = audio.size
                    val sb = StringBuilder()
                    var next = 1.0
                    var fed = 0
                    val chunk = 1024
                    val baseT = 10_000_000_000L
                    var nextMoment = 10.0
                    while (fed + chunk <= total) {
                        val piece = audio.copyOfRange(fed, fed + chunk)
                        val at = baseT + ((fed + chunk).toDouble() / rate * 1e9).toLong()
                        a.process(piece, chunk, at)
                        fed += chunk
                        val t = fed.toDouble() / rate
                        if (t >= next) {
                            val r = a.result
                            val s = a.singer
                            val lvl = a.keyLevel
                            val kn = a.keyNotes()
                            val top = if (kn.size >= 8) MelodicKeyFinder.rank(kn, sigma = a.keySigma()).take(3) else emptyList()
                            sb.append("{\"t\":${"%.6f".format(t)},\"moment\":$next,\"key\":${k(r.key)},\"display\":${k(r.displayKey)},")
                                .append("\"modeDecided\":${r.modeDecided},\"pairDecided\":${r.pairDecided},\"modeScore\":${"%.4f".format(r.modeScore)},")
                                .append("\"tuning\":${"%.2f".format(r.tuningCents)},\"fromMelody\":${r.keyFromMelody},")
                                .append("\"off\":${"%.2f".format(s.offsetCents)},\"cur\":${"%.2f".format(s.currentCents)},\"relabel\":${s.relabelSemitones},")
                                .append("\"seen\":${s.notesSeen},\"spread\":${"%.1f".format(s.spreadCents)},\"sigma\":${"%.3f".format(a.keySigma())},")
                                .append("\"lvl\":").append(if (lvl == null) "null" else "[${"%.1f".format(lvl.centreCents)},${"%.1f".format(lvl.nowCents)},${"%.1f".format(lvl.lowCents)},${"%.1f".format(lvl.highCents)},${"%.1f".format(lvl.spreadCents)}]")
                                .append(",\"nshift\":${a.nowShiftShown},\"top\":[").append(top.joinToString(",") { "[${k(it.key)},${"%.4f".format(it.score)}]" }).append("]")
                            if (next >= nextMoment - 1e-9) {
                                sb.append(",\"notes\":[").append(kn.joinToString(",") { "[${"%.3f".format(it.pitch)},${"%.3f".format(it.startSeconds)},${"%.3f".format(it.durationSeconds)}]" }).append("]")
                                nextMoment += 4.0
                            }
                            sb.append("}\n")
                            while (next <= t) next += 1.0
                        }
                    }
                    DetuneFix.flags.set(emptySet())
                    val seg = a.segmenter.snapshot()
                    sb.append("{\"final\":[").append(seg.joinToString(",") { "[${"%.3f".format(it.concertPitch)},${"%.3f".format(it.startSeconds)},${"%.3f".format(it.durationSeconds)}]" }).append("]}\n")
                    sb.append("{\"done\":true,\"seconds\":${total.toDouble() / rate}}\n")
                    recorder?.let { rc -> cache.parentFile.mkdirs(); rc.save(cache) }
                    out.parentFile.mkdirs()
                    val tmp = File(out.path + ".tmp"); tmp.writeText(sb.toString()); tmp.renameTo(out)
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
