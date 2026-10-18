package com.donfluids.piano

import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.dsp.MusicAnalyzer
import com.donfluids.music.dsp.RelativeKeyResolver
import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Listen with the phone's own speaker playing: a singer in D major while the app's piano plays
 * other chords through the same phone. The microphone hears both, the piano a little late.
 */
class OwnSoundTest {
    private val rate = 44100
    private val bpm = 100
    private val beatSamples = (60.0 / bpm * rate).toInt()

    @Test
    fun theSynthReportsWhatItIsSoundingIncludingTheRingAfterRelease() {
        val synth = PianoSynth(rate)
        val buf = FloatArray(256)
        synth.noteOn(60); synth.noteOn(64)
        synth.render(buf, 256)
        assertEquals((1 shl 0) or (1 shl 4), synth.soundingMask)
        synth.noteOff(60); synth.noteOff(64)
        synth.render(buf, 256)
        assertTrue("still ringing just after release", synth.soundingMask != 0)
        repeat(rate / 256) { synth.render(buf, 256) }
        assertEquals(0, synth.soundingMask)
    }

    @Test
    fun ownSoundHoldsANoteLongEnoughToCoverTheSpeakerDelay() {
        val own = OwnSound(holdNanos = 350_000_000L)
        assertEquals(0, own.mask(0L))
        own.sounded(1 shl 7, atNanos = 1_000_000_000L)
        assertEquals(1 shl 7, own.mask(1_000_000_000L))
        assertEquals(1 shl 7, own.mask(1_300_000_000L))
        assertEquals(0, own.mask(1_400_000_000L))
        // Scheduled ahead of capture time: already counted.
        assertEquals(1 shl 7, own.mask(900_000_000L))
    }

    /** A sung tune in D major, one bar per chord: D, G, A, D, Bm, G, A, D, twice. Ends on a long D. */
    private val melody = listOf(
        listOf(62, 66, 69, 66), listOf(67, 71, 74, 71), listOf(69, 73, 76, 73), listOf(74, 69, 66, 62),
        listOf(71, 74, 78, 74), listOf(67, 71, 74, 71), listOf(69, 73, 76, 73), listOf(62, 62, 62, 62),
    )
    private val tuneChords = listOf("D", "G", "A", "D", "Bm", "G", "A", "D")

    private fun voice(out: FloatArray, start: Int, length: Int, midi: Int) {
        val f = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
        var phase = 0.0
        for (i in 0 until length) {
            val idx = start + i
            if (idx >= out.size) break
            val t = i.toDouble() / rate
            phase += 2 * PI * f * (1.0 + 0.006 * sin(2 * PI * 5.5 * t)) / rate
            val env = minOf(1.0, t / 0.03) * minOf(1.0, (length - i).toDouble() / rate / 0.05)
            out[idx] += (0.25 * env * (sin(phase) + 0.5 * sin(2 * phase) + 0.25 * sin(3 * phase)) / 1.9).toFloat()
        }
    }

    private fun sung(): FloatArray {
        val bars = melody + melody
        val out = FloatArray((bars.size + 1) * 4 * beatSamples)
        for ((b, notes) in bars.withIndex()) {
            val last = b == bars.size - 1
            if (last) voice(out, b * 4 * beatSamples, (4 * beatSamples * 0.95).toInt(), notes[0])
            else notes.forEachIndexed { k, m -> voice(out, (b * 4 + k) * beatSamples, (beatSamples * 0.9).toInt(), m) }
        }
        return out
    }

    data class Heard(val key: Key?, val chordsRight: Int, val chordsTotal: Int, val pairDecided: Boolean, val modeDecided: Boolean)

    /**
     * Plays [pianoChords] (one per bar, held) on the app's synth while the singer sings, mixes the
     * speaker into the microphone at [gain] after [latencyMs], and listens with or without
     * leaving out the app's own notes.
     */
    private fun listen(pianoChords: List<List<Int>>, gain: Float, leaveOutOwn: Boolean, latencyMs: Int = 120): Heard {
        val voiceTrack = sung()
        val synth = PianoSynth(rate)
        val own = OwnSound()
        val analyzer = MusicAnalyzer(rate, AnalysisPreset.VOICES)
        if (leaveOutOwn) analyzer.ownSound = { t -> own.mask(t) }
        val latency = rate * latencyMs / 1000
        val speaker = FloatArray(voiceTrack.size + latency)
        val block = 256
        val buf = FloatArray(block)
        var held = emptyList<Int>()
        val harmonizer = MelodyHarmonizer(Theory.Scale(2, minor = false))
        var chord: BandChord? = null
        var lastHalfBar = -1
        var right = 0; var total = 0
        var i = 0
        while (i < voiceTrack.size) {
            val bar = i / (4 * beatSamples)
            val want = pianoChords[bar % pianoChords.size]
            if (want != held) { held.forEach { synth.noteOff(it) }; want.forEach { synth.noteOn(it, 0.8f) }; held = want }
            synth.render(buf, block)
            own.sounded(synth.soundingMask, (i.toDouble() / rate * 1e9).toLong())
            for (k in 0 until block) if (i + k + latency < speaker.size) speaker[i + k + latency] = buf[k]
            val n = minOf(block, voiceTrack.size - i)
            val mic = FloatArray(n) { k -> voiceTrack[i + k] + gain * speaker[i + k] }
            analyzer.process(mic, n, ((i + n).toDouble() / rate * 1e9).toLong())
            // The sung chord, as Listen shows it, judged at the end of each bar.
            val halfBar = i / (2 * beatSamples)
            if (halfBar != lastHalfBar) {
                lastHalfBar = halfBar
                chord = harmonizer.next(analyzer.result.recentChroma, chord)
                val barNow = i / (4 * beatSamples)
                if (halfBar % 2 == 1 && barNow < 16) { total++; if (chord.name() == tuneChords[barNow % 8]) right++ }
            }
            i += n
        }
        val r: AnalysisResult = analyzer.result
        return Heard(r.displayKey ?: r.key, right, total, r.pairDecided, r.modeDecided)
    }

    private val cMajorChords = listOf(listOf(60, 64, 67), listOf(65, 69, 72), listOf(67, 71, 74), listOf(69, 72, 76))
    private val dMajorChords = listOf(listOf(62, 66, 69), listOf(67, 71, 74), listOf(69, 73, 76), listOf(62, 66, 69))
    private val dMajor = Key(2, Mode.MAJOR)

    @Test
    fun theAppsOwnPianoInAnotherKeyIsLeftOutOfTheSungChord() {
        // As loud as the singer at the microphone: the reading holds either way.
        val evenWithout = listen(cMajorChords, 1.0f, leaveOutOwn = false)
        val evenWith = listen(cMajorChords, 1.0f, leaveOutOwn = true)
        // Twice as loud: without leaving its own notes out, the sung chord is mostly the piano's.
        val loudWithout = listen(cMajorChords, 2.0f, leaveOutOwn = false)
        val loudWith = listen(cMajorChords, 2.0f, leaveOutOwn = true)
        for ((name, h) in listOf("even, without" to evenWithout, "even, with" to evenWith, "loud, without" to loudWithout, "loud, with" to loudWith)) {
            println("piano in C, $name: ${h.key?.label} pair=${h.pairDecided} mode=${h.modeDecided}, sung chord right ${h.chordsRight}/${h.chordsTotal}")
        }
        assertEquals(dMajor, evenWith.key)
        assertTrue("${evenWith.chordsRight} vs ${evenWithout.chordsRight}", evenWith.chordsRight >= evenWithout.chordsRight)
        assertTrue("${loudWith.chordsRight} vs ${loudWithout.chordsRight}", loudWith.chordsRight >= loudWithout.chordsRight + 4)
        // Too loud to read the singer well: whatever it shows, it is not sure of a wrong key.
        for (h in listOf(loudWith, loudWithout)) if (h.pairDecided) assertEquals(dMajor, h.key?.let { RelativeKeyResolver.pair(it).first })
    }

    @Test
    fun playingAlongInTheRightKeyDoesNoHarm() {
        val along = listen(dMajorChords, 1.0f, leaveOutOwn = true)
        val alone = listen(listOf(emptyList()), 0f, leaveOutOwn = true)
        println("piano in D: ${along.key?.label} pair=${along.pairDecided} mode=${along.modeDecided}, sung chord ${along.chordsRight}/${along.chordsTotal}; singer alone ${alone.key?.label} chords ${alone.chordsRight}/${alone.chordsTotal}")
        assertEquals(dMajor, along.key)
        assertTrue(along.pairDecided)
        assertTrue("${along.chordsRight} vs alone ${alone.chordsRight}", along.chordsRight >= alone.chordsRight)
    }
}
