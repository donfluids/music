package com.donfluids.music.dsp

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Driver for the Beat This! Kotlin prototype and the yardstick timings.
 *
 *   check <weights.bin> <in.f32> <out.f32> [threads]             writes beat then downbeat logits
 *   bench <weights.bin> <in.f32> <threads> <runs> <warmup>          prints timing (BT_PROFILE=1: per stage)
 *   yard <basic-pitch.bin> <crepe-tiny.bin> <runs>                 times BasicPitch.notes and CrepePitch
 */
fun main(args: Array<String>) {
    when (args[0]) {
        "check" -> {
            val w = BeatThis.Weights.read(File(args[1]).readBytes())
            val spect = readF32(args[2])
            val threads = args.getOrNull(4)?.toInt() ?: 1
            val m = BeatThis(w, 412, threads)
            val beat = FloatArray(412); val down = FloatArray(412)
            m.logits(spect, beat, down)
            writeF32(args[3], beat + down)
            m.close()
        }
        "bench" -> {
            val w = BeatThis.Weights.read(File(args[1]).readBytes())
            val spect = readF32(args[2])
            val threads = args[3].toInt()
            val runs = args[4].toInt(); val warm = args[5].toInt()
            val m = BeatThis(w, 412, threads)
            val beat = FloatArray(412); val down = FloatArray(412)
            val ms = time(runs, warm) { m.logits(spect, beat, down) }
            if (System.getenv("BT_PROFILE") != null) {
                BeatThis.PROFILE = true
                repeat(runs) { m.logits(spect, beat, down) }
                for (i in 0 until 6) println("  %-16s %8.1f ms/run".format(BeatThis.profNames[i], BeatThis.prof[i] / 1e6 / runs))
            }
            println("bench ${File(args[1]).name} threads=$threads: ${stats(ms)}")
            m.close()
        }
        "yard" -> {
            val runs = args[3].toInt()
            val bp = BasicPitch(BasicPitch.Weights.read(File(args[1]).readBytes()))
            // the chords test signal of BasicPitchTest, roughly: a few harmonic tones
            val x = FloatArray(BasicPitch.WINDOW) { i ->
                val t = i / 22050.0
                var s = 0.0
                for (f in doubleArrayOf(130.81, 164.81, 196.0, 261.63)) for (h in 1..4) s += 0.05 * Math.pow(0.6, h - 1.0) * sin(2 * PI * f * h * t)
                s.toFloat()
            }
            val out = Array(BasicPitch.FRAMES) { FloatArray(BasicPitch.NOTES) }
            val bpMs = time(runs, 20) { bp.notes(x, 0, BasicPitch.FRAMES, out) }
            println("yard BasicPitch.notes (one 2 s window, all 172 frames): ${stats(bpMs)}")
            // what HeardNotes asks for every 0.5 s: frames KEEP_END - runFrames until KEEP_END = 114 until 157
            val part = Array(43) { FloatArray(BasicPitch.NOTES) }
            val bpApp = time(runs, 20) { bp.notes(x, 114, 157, part) }
            println("yard BasicPitch.notes as HeardNotes runs it (frames 114..157, twice a second): ${stats(bpApp)}")
            val cw = CrepePitch.Weights.read(File(args[2]).readBytes())
            val crepe = CrepePitch(cw, 44100)
            val buf = FloatArray(8192) { i -> (0.3 * sin(2 * PI * 220.0 * i / 44100) + 0.1 * sin(2 * PI * 440.0 * i / 44100)).toFloat() }
            val crMs = time(runs * 10, 200) { crepe.process(buf, buf.size) }
            println("yard CrepePitch.process (one frame, 44.1 kHz, voiced tone): ${stats(crMs)}")
            val f16 = FloatArray(1024) { i -> (0.3 * sin(2 * PI * 220.0 * i / 16000)).toFloat() }
            val crNet = time(runs * 10, 200) { crepe.activationsOf(f16) }
            println("yard CrepePitch.activationsOf (network only, one 16 kHz frame): ${stats(crNet)}")
        }
        else -> error("unknown mode ${args[0]}")
    }
}

private inline fun time(runs: Int, warm: Int, body: () -> Unit): DoubleArray {
    repeat(warm) { body() }
    return DoubleArray(runs) {
        val t0 = System.nanoTime(); body(); (System.nanoTime() - t0) / 1e6
    }
}

private fun stats(ms: DoubleArray): String {
    val s = ms.sorted()
    val med = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    return "median %.3f ms, min %.3f, max %.3f, n=%d".format(med, s.first(), s.last(), s.size)
}

private fun readF32(path: String): FloatArray {
    val b = ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(b.remaining() / 4) { b.float }
}

private fun writeF32(path: String, a: FloatArray) {
    val b = ByteBuffer.allocate(4 * a.size).order(ByteOrder.LITTLE_ENDIAN)
    for (v in a) b.putFloat(v)
    File(path).writeBytes(b.array())
}
