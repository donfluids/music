package com.donfluids.piano

import org.junit.Test
import java.io.File

/** Renders short listening demos into piano/build/sound-demos. Not a pass/fail test. */
class DemoRenderTest {
    private val rate = 44100
    private val dir = File("build/sound-demos")

    private fun play(synth: PianoSynth, reverb: Reverb?, events: List<Triple<Double, List<Int>, Double>>, tail: Double): FloatArray {
        // events: (start seconds, midi notes, length seconds)
        val end = events.maxOf { it.first + it.third } + tail
        val out = FloatArray((end * rate).toInt())
        val block = FloatArray(256)
        var i = 0
        val pendingOn = events.sortedBy { it.first }.toMutableList()
        val pendingOff = ArrayList<Pair<Double, List<Int>>>()
        while (i < out.size) {
            val t = i.toDouble() / rate
            while (pendingOn.isNotEmpty() && pendingOn.first().first <= t) { val e = pendingOn.removeAt(0); e.second.forEach { synth.noteOn(it, 0.85f) }; pendingOff += (e.first + e.third) to e.second }
            pendingOff.filter { it.first <= t }.forEach { it.second.forEach { n -> synth.noteOff(n) } }
            pendingOff.removeAll { it.first <= t }
            val n = minOf(256, out.size - i)
            synth.render(block, n)
            reverb?.process(block, n)
            System.arraycopy(block, 0, out, i, n)
            i += n
        }
        return out
    }

    @Test
    fun renderDemos() {
        val scale = listOf(60, 62, 64, 65, 67, 69, 71, 72)
        for (tone in PianoSynth.Tone.entries) {
            val synth = PianoSynth(rate).also { it.tone = tone }
            val reverb = Reverb(rate).also { it.enabled = true }
            val events = scale.mapIndexed { k, m -> Triple(k * 0.35, listOf(m), 0.3) } +
                listOf(Triple(3.2, listOf(48, 55, 60, 64), 1.6), Triple(5.0, listOf(53, 57, 60, 65), 1.6), Triple(6.8, listOf(55, 59, 62, 67), 1.6), Triple(8.6, listOf(48, 55, 60, 64), 2.5))
            Audio.writeWav(File(dir, "${tone.name.lowercase()}-scale-and-chords.wav"), play(synth, reverb, events, 2.5), rate)
        }
        val dry = PianoSynth(rate)
        Audio.writeWav(File(dir, "piano-dry.wav"), play(dry, null, listOf(Triple(0.0, listOf(48, 55, 60, 64), 2.0)), 1.5), rate)
        val m = Metronome(rate).also { it.bpm = 100; it.beatsPerBar = 4; it.enabled = true }
        val click = FloatArray(rate * 4)
        var i = 0
        while (i < click.size) { val n = minOf(256, click.size - i); val b = FloatArray(n); m.mix(b, n, 0L); System.arraycopy(b, 0, click, i, n); i += n }
        Audio.writeWav(File(dir, "metronome-100-in-4.wav"), click, rate)
    }
}
