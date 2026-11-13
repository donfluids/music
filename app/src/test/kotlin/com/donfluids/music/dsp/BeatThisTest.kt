package com.donfluids.music.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Stands in for Beat This!: it knows when the beats and downbeats are, works out which frames
 * the window it is given covers (one call a second from 4 s, as BeatTracker runs it, unless
 * [momentOf] says otherwise), and answers a clear logit on the frame nearest each beat. What
 * happens around the network (front end, windows, postprocessing, reading, dating, the
 * analyser's fields) is tested for real.
 */
internal class ScoreBeatNetwork(
    bpm: Double,
    beatsPerBar: Int,
    seconds: Double,
    start: Double = 0.0,
    private val momentOf: (Int) -> Double = { BeatThis.FIRST_READING_SECONDS + it.toDouble() },
) : BeatNetwork {
    val beats: List<Double>
    val downbeats: List<Double>
    var calls = 0
        private set

    init {
        val period = 60.0 / bpm
        val b = ArrayList<Double>()
        val d = ArrayList<Double>()
        var k = 0
        while (start + k * period < seconds) {
            b.add(start + k * period)
            if (k % beatsPerBar == 0) d.add(start + k * period)
            k++
        }
        beats = b
        downbeats = d
    }

    override fun logits(spect: FloatArray): BeatLogits? {
        assertEquals(BeatThis.INPUT_FRAMES * BeatThis.MELS, spect.size)
        val moment = momentOf(calls)
        calls++
        val last = floor((moment * 22050 - 512) / 441).toInt()
        val first = last - BeatThis.FRAMES + 1
        fun row(times: List<Double>) = FloatArray(BeatThis.INPUT_FRAMES) { f ->
            val g = (first + f - BeatThis.BORDER).toDouble()
            if (times.any { abs(it * 50 - g) < 0.5 }) 4f else -4f
        }
        return BeatLogits(row(beats), row(downbeats))
    }
}

private class FailingBeatNetwork : BeatNetwork {
    var calls = 0
        private set
    override fun logits(spect: FloatArray): BeatLogits? { calls++; return null }
}

class BeatThisTest {

    // ---- Front end ----

    @Test
    fun frontEndMatchesTheNumPyReference() {
        val ref = BeatThisFixtures.json("beat-this-frontend")
        val frames = ref.getJSONArray("frames")
        val want = List(frames.length()) { BeatThisFixtures.nums(frames.getJSONArray(it)) }
        val signal = BeatThisFixtures.signal(ref.getInt("samples"))
        val fe = BeatThisFrontEnd()
        // Odd chunks: frames are made as the samples arrive, whatever the chunk size.
        var i = 0
        while (i < signal.size) { val n = minOf(333, signal.size - i); for (k in i until i + n) fe.push(signal[k]); i += n }
        assertEquals(want.size, fe.framesMade)
        var worst = 0.0
        for (t in want.indices) {
            val got = fe.frame(t)
            for (b in 0 until BeatThis.MELS) worst = maxOf(worst, abs(got[b] - want[t][b]))
        }
        println("Beat This! front end: largest difference from NumPy $worst")
        // The official float32 front end is 1.7e-4 from the float64 one.
        assertTrue(worst < 1e-3)
    }

    @Test
    fun filterbankLooksLikeTorchaudio() {
        val fb = BeatThisFrontEnd.filterbank
        assertEquals(128, fb.size)
        // Peak 1 at most (no area normalisation), lowest band from about 30 Hz, highest to 11 kHz.
        for ((_, w) in fb) { assertTrue(w.isNotEmpty()); assertTrue(w.max() <= 1f) }
        assertEquals(2, fb[0].first)
        val top = fb[127]
        assertEquals(510, top.first + top.second.size - 1)
    }

    @Test
    fun windowHasBordersAndSilenceBeforeTheStart() {
        val fe = BeatThisFrontEnd()
        for (i in 0 until 22050 * 2) fe.push(sin(i * 0.3).toFloat())
        val input = FloatArray(BeatThis.INPUT_FRAMES * BeatThis.MELS) { 1f }
        val last = fe.framesMade - 1
        fe.window(last, input)
        fun row(r: Int) = input.copyOfRange(r * BeatThis.MELS, (r + 1) * BeatThis.MELS)
        // 6 border rows, then 400 - frames silent rows, the frames, and 6 border rows.
        for (r in 0 until BeatThis.BORDER + BeatThis.FRAMES - fe.framesMade) assertTrue("row $r", row(r).all { it == 0f })
        assertTrue(row(BeatThis.BORDER + BeatThis.FRAMES - 1).contentEquals(fe.frame(last)))
        for (r in BeatThis.BORDER + BeatThis.FRAMES until BeatThis.INPUT_FRAMES) assertTrue(row(r).all { it == 0f })
    }

    /**
     * The synthetic band through the resampler and front end, against the Python pipeline's
     * input to the network at 10 s (soxr and the float64 log-mel; stored as float16).
     */
    @Test
    fun liveFrontEndMatchesPythonOnTheBand() {
        val ref = BeatThisFixtures.json("beat-this-coreml")
        val band = BeatThisFixtures.band()
        var squares = 0.0
        for (v in band) squares += v.toDouble() * v
        assertEquals("the synthetic band must match Python's", ref.getDouble("bandSumSquares"), squares, 1e-6 * squares)
        val size = BeatThis.INPUT_FRAMES * BeatThis.MELS
        val want = BeatThisFixtures.halves("beat-this-coreml-input").copyOfRange(size, 2 * size)
        val resampler = StreamResampler(44100, BeatThis.SAMPLE_RATE, BeatThis.RESAMPLER_CUTOFF_HZ)
        val fe = BeatThisFrontEnd()
        var made = 0
        val moment = 10 * BeatThis.SAMPLE_RATE
        resampler.push(band, 0, band.size) { v -> if (made < moment) { fe.push(v); made++ } }
        assertEquals(moment, made)
        val got = FloatArray(size)
        fe.window(Math.floorDiv(moment - 512, BeatThis.HOP), got)
        var worstLow = 0f
        var worst = 0f
        for (r in 0 until BeatThis.INPUT_FRAMES) {
            for (b in 0 until BeatThis.MELS) {
                val d = abs(got[r * BeatThis.MELS + b] - want[r * BeatThis.MELS + b])
                worst = maxOf(worst, d)
                if (b < 120) worstLow = maxOf(worstLow, d)
            }
        }
        println("Beat This! live front end vs Python on the band: largest difference $worst, below 9.4 kHz $worstLow")
        // Only the resampler differs (and float16 storage, 0.004 at these values): the top bands
        // see soxr's steeper filter, the rest hardly anything.
        assertTrue(worstLow < 0.05f)
        assertTrue(worst < 1.5f)
    }

    // ---- Postprocessing and reading ----

    @Test
    fun peaksFollowTheOfficialRules() {
        val x = FloatArray(40) { -1f }
        x[5] = 2f                     // a peak
        x[7] = 1f                     // within 3 frames of a bigger one: not a peak
        x[15] = 0.5f; x[16] = 0.5f    // a plateau: both frames are maxima, merged to 15.5
        x[25] = 1f; x[26] = 1f; x[27] = 1f // three equal frames: 25 and 26 merge to 25.5; 27 is 1.5 from that mean
        x[35] = -0.5f                 // a maximum but not above 0
        assertEquals(listOf(5.0, 15.5, 25.5, 27.0), BeatPostprocessing.peakFrames(x))
        // Downbeats move to the nearest beat (the first of two equally near) and are not repeated.
        val b = FloatArray(200) { -5f }
        val d = FloatArray(200) { -5f }
        for (f in listOf(10, 35, 60, 85)) b[f] = 3f
        d[12] = 2f; d[20] = 2f; d[47] = 2f // 0.24 s and 0.4 s go to 0.2 s, 0.94 s to 0.7 s
        val (beats, downs) = BeatPostprocessing.beatsAndDownbeats(b, d)
        assertEquals(listOf(0.2, 0.7, 1.2, 1.7), beats)
        assertEquals(listOf(0.2, 0.7), downs)
    }

    @Test
    fun readingsMatchThePythonReference() {
        val ref = BeatThisFixtures.json("beat-this-readings")
        val logits = BeatThisFixtures.halves("beat-this-readings-logits")
        val frames = BeatThis.FRAMES
        var at = 0
        val clips = ref.getJSONArray("clips")
        for (c in 0 until clips.length()) {
            val clip = clips.getJSONObject(c)
            val reader = BeatReader()
            val rows = clip.getJSONArray("readings")
            for (k in 0 until rows.length()) {
                val want = BeatThisFixtures.Reading(rows.getJSONObject(k))
                val beat = logits.copyOfRange(at, at + frames)
                val downbeat = logits.copyOfRange(at + frames, at + 2 * frames)
                at += 2 * frames
                val got = reader.read(want.t, want.firstFrame, beat, downbeat)
                val what = "${clip.getString("id")} at ${want.t} s"
                assertEquals(what, want.beats.size, got.beats.size)
                for ((a, b) in got.beats.zip(want.beats)) assertEquals(what, b, a, 1e-9)
                assertEquals(what, want.downbeats.size, got.downbeats.size)
                for ((a, b) in got.downbeats.zip(want.downbeats)) assertEquals(what, b, a, 1e-9)
                assertEquals(what, want.bpm == null, got.bpm == null)
                if (got.bpm != null && want.bpm != null) assertEquals(what, want.bpm, got.bpm!!, 1e-9)
                if (got.periodSeconds != null && want.period != null) assertEquals(what, want.period, got.periodSeconds!!, 1e-12)
                if (got.lastBeatSeconds != null && want.last != null) assertEquals(what, want.last, got.lastBeatSeconds!!, 1e-12)
                assertEquals(what, want.beatsPerBar, got.beatsPerBar)
                assertEquals(what, want.index, got.lastBeatIndexInBar)
                assertEquals(what, want.locked, got.locked)
                assertEquals(what, want.strength, got.strength, 1e-9)
                for (bb in BeatReader.BARS) assertEquals(what, want.scores[bb]!!, got.barScores[bb]!!, 1e-9)
            }
        }
        assertEquals(logits.size, at)
    }

    /**
     * Logits for a window starting at frame [first]: beats every [period] frames, a downbeat
     * every [bar] beats with the given phase, and [downbeatLogit] on the downbeats.
     */
    private fun comb(first: Int, period: Int, bar: Int, phase: Int, downbeatLogit: Float = 3f): Pair<FloatArray, FloatArray> {
        val b = FloatArray(BeatThis.FRAMES) { -6f }
        val d = FloatArray(BeatThis.FRAMES) { -6f }
        for (f in 0 until BeatThis.FRAMES) {
            if (Math.floorMod(first + f, period) != 0) continue
            b[f] = 4f
            if (Math.floorMod(Math.floorDiv(first + f, period) - phase, bar) == 0) d[f] = downbeatLogit
        }
        return Pair(b, d)
    }

    /** The first frame of the window read at second [m]. */
    private fun firstFrameAt(m: Int) = (m * 22050 - 512) / 441 - 399

    @Test
    fun combCountsTheBarAndFollowsAChange() {
        val reader = BeatReader()
        var r = BeatReading()
        // 120 BPM in 3/4: beats every 25 frames, a downbeat every third beat.
        for (m in 4..8) {
            val first = firstFrameAt(m)
            val (b, d) = comb(first, 25, 3, 0)
            r = reader.read(m.toDouble(), first, b, d)
        }
        assertEquals(120.0, r.bpm ?: 0.0, 1e-9)
        assertEquals(3, r.beatsPerBar)
        assertEquals("3/4", r.meterLabel)
        assertTrue(r.locked)
        // The newest beat's place in the bar: beat k of the song is at frame 25 k, downbeats at k % 3 == 0.
        val k = (r.lastBeatSeconds!! * 2).roundToInt()
        assertEquals(k % 3, r.lastBeatIndexInBar)
        // The band goes to 4/4: the pooled scores halve each second, so it follows within a few readings.
        var switched = 0
        for (m in 9..14) {
            val first = firstFrameAt(m)
            val (b, d) = comb(first, 25, 4, 0)
            r = reader.read(m.toDouble(), first, b, d)
            if (r.beatsPerBar == 4 && switched == 0) switched = m
        }
        assertEquals(4, r.beatsPerBar)
        assertTrue(switched >= 9)
        assertTrue(switched <= 11)
    }

    @Test
    fun noBeatsMeansNoTempo() {
        val reader = BeatReader()
        val quiet = FloatArray(BeatThis.FRAMES) { -8f }
        val r = reader.read(4.0, -199, quiet, quiet)
        assertNull(r.bpm)
        assertNull(r.beatsPerBar)
        assertNull(r.meterLabel)
        assertFalse(r.locked)
    }

    // ---- The same per second of music at one reading every two seconds (Android's slow phones) ----

    @Test
    fun combPoolsTheSamePerSecondAtTwoSeconds() {
        // The same window at every reading: after 6 s the pooled scores are the same whether
        // they were read 6 times a second apart or 3 times two seconds apart.
        val first = firstFrameAt(8)
        val (b, d) = comb(first, 25, 3, 0)
        val everySecond = BeatReader()
        var one = BeatReading()
        repeat(6) { one = everySecond.read(8.0, first, b, d, interval = 1.0) }
        val everyTwo = BeatReader()
        var two = BeatReading()
        repeat(3) { two = everyTwo.read(8.0, first, b, d, interval = 2.0) }
        for (bb in BeatReader.BARS) assertEquals("$bb beats", one.barScores[bb]!!, two.barScores[bb]!!, 1e-12)
        assertEquals(3, two.beatsPerBar)
        assertEquals(one.meterConfidence, two.meterConfidence, 1e-12)
    }

    @Test
    fun combFollowsAChangeAsFastAtTwoSeconds() {
        fun switchSecond(every: Int): Int {
            val reader = BeatReader()
            for (m in 4..8 step every) { val f = firstFrameAt(m); val (b, d) = comb(f, 25, 3, 0); reader.read(m.toDouble(), f, b, d, every.toDouble()) }
            for (m in (8 + every)..16 step every) {
                val f = firstFrameAt(m)
                val (b, d) = comb(f, 25, 4, 0)
                if (reader.read(m.toDouble(), f, b, d, every.toDouble()).beatsPerBar == 4) return m
            }
            return -1
        }
        val one = switchSecond(1)
        val two = switchSecond(2)
        println("Beat This! 3/4 to 4/4 at 8 s: shown at $one s reading every second, at $two s every two")
        assertTrue("one a second: $one", one in 9..11)
        // Within one reading of the same second of music.
        assertTrue("one every two seconds: $two", two in 9..one + 1)
    }

    @Test
    fun lockLooksBackTwoSecondsAtEitherCadence() {
        val (b, d) = comb(firstFrameAt(8), 25, 4, 0)
        // One a second: the third agreeing reading (2 s after the first) locks.
        val r1 = BeatReader()
        val locks1 = (0 until 4).map { r1.read(8.0, firstFrameAt(8), b, d, 1.0).locked }
        assertEquals(listOf(false, false, true, true), locks1)
        // One every two seconds: the second agreeing reading, also 2 s after the first.
        val r2 = BeatReader()
        val locks2 = (0 until 3).map { r2.read(8.0, firstFrameAt(8), b, d, 2.0).locked }
        assertEquals(listOf(false, true, true), locks2)
        // A tempo change two seconds ago still unlocks it.
        val (b2, d2) = comb(firstFrameAt(8), 20, 4, 0)
        assertFalse(r2.read(8.0, firstFrameAt(8), b2, d2, 2.0).locked)
        assertTrue(r2.read(8.0, firstFrameAt(8), b2, d2, 2.0).locked)
    }

    @Test
    fun feelDecidesInTheSameSecondsAtTwoSeconds() {
        val (x, last) = onsets(listOf(1.0 / 3 to 0.6f, 2.0 / 3 to 0.6f))
        fun firstCompoundSecond(every: Int): Int {
            val feel = BeatFeel()
            for (k in 1..40 / every) {
                val (e, pf) = BeatFeel.energies(x, 0, 44100.0 / 512.0, last, 0.6)
                if (feel.update(e!!, pf, every.toDouble())) return k * every
            }
            return -1
        }
        val one = firstCompoundSecond(1)
        val two = firstCompoundSecond(2)
        println("Beat This! triplets: compound after $one s reading every second, $two s every two")
        assertTrue("one a second: $one", one in 18..19)
        assertTrue("one every two seconds: $two", abs(two - one) <= 2)
        // And straight eighths stay simple at either cadence.
        val (straight, l1) = onsets(listOf(0.5 to 0.6f))
        val feel = BeatFeel()
        repeat(20) {
            val (e, pf) = BeatFeel.energies(straight, 0, 44100.0 / 512.0, l1, 0.6)
            assertFalse(feel.update(e!!, pf, 2.0))
        }
    }

    @Test
    fun feelLetsGoInTheSameSecondsAtTwoSeconds() {
        val (triplets, last) = onsets(listOf(1.0 / 3 to 0.6f, 2.0 / 3 to 0.6f))
        val (straight, l2) = onsets(listOf(0.5 to 0.6f))
        fun secondsToLetGo(every: Int): Int {
            val feel = BeatFeel()
            repeat(40 / every) {
                val (e, pf) = BeatFeel.energies(triplets, 0, 44100.0 / 512.0, last, 0.6)
                feel.update(e!!, pf, every.toDouble())
            }
            assertTrue(feel.compound)
            for (k in 1..60 / every) {
                val (e, pf) = BeatFeel.energies(straight, 0, 44100.0 / 512.0, l2, 0.6)
                if (!feel.update(e!!, pf, every.toDouble())) return k * every
            }
            return -1
        }
        val one = secondsToLetGo(1)
        val two = secondsToLetGo(2)
        println("Beat This! straight after triplets: simple again after $one s reading every second, $two s every two")
        assertTrue("one a second: $one", one > 0)
        assertTrue("one every two seconds: $two vs $one", two > 0 && abs(two - one) <= 2)
    }

    // ---- Simple or compound ----

    @Test
    fun feelMatchesThePythonReference() {
        val ref = BeatThisFixtures.json("beat-this-feel")
        val onsets = BeatThisFixtures.nums(ref.getJSONArray("onsets")).map { it.toFloat() }.toFloatArray()
        val fps = ref.getDouble("rate") / ref.getDouble("hop")
        val capacity = floor(8.0 * fps + 0.5).toInt()
        val feel = BeatFeel()
        var shown = 0
        val rows = ref.getJSONArray("readings")
        for (k in 0 until rows.length()) {
            val row = rows.getJSONObject(k)
            val t = row.getDouble("t")
            val last = BeatThisFixtures.num(row, "last")
            val period = BeatThisFixtures.num(row, "period")
            if (BeatThisFixtures.num(row, "bpm") != null && last != null && period != null) {
                val hEnd = floor(t * fps).toInt() - 1
                val h0 = maxOf(0, hEnd - capacity + 1)
                val (e, pf) = BeatFeel.energies(onsets.copyOfRange(h0, hEnd + 1), h0, fps, last, period)
                val want = BeatThisFixtures.nums(row.optJSONArray("energies"))
                assertEquals("at $t s", want.isEmpty(), e == null)
                if (e != null) {
                    for (i in 0 until 4) assertEquals("at $t s", want[i], e[i], 1e-9 * maxOf(1.0, abs(want[i])))
                    assertEquals(row.getDouble("periodFrames"), pf, 1e-9)
                    feel.update(e, pf)
                }
            }
            assertEquals("at $t s", row.getBoolean("compound"), feel.compound)
            if (feel.compound) shown++
        }
        // This 12/8 song is one of the few where the evidence gets strong enough.
        assertTrue(shown > 0)
    }

    /**
     * 8 s of onset signal at 44.1 kHz / 512: a strong onset on each beat (0.6 s apart) and the
     * given ones between, at fractions of the beat.
     */
    private fun onsets(between: List<Pair<Double, Float>>, period: Double = 0.6): Pair<FloatArray, Double> {
        val fps = 44100.0 / 512.0
        val n = floor(8.0 * fps + 0.5).toInt()
        val x = FloatArray(n) { 0.05f }
        fun hop(at: Double) = Math.round(at * fps).toInt() - 1
        fun put(at: Double, v: Float) { val h = hop(at); if (h in 0 until n) x[h] = maxOf(x[h], v) }
        var t = 0.1
        var last = 0.0
        while (hop(t) < n) {
            put(t, 1f)
            last = t
            for ((f, v) in between) put(t + f * period, v)
            t += period
        }
        return Pair(x, last)
    }

    private fun feelAfter(readings: Int, x: FloatArray, last: Double, period: Double = 0.6): List<Boolean> {
        val feel = BeatFeel()
        return (0 until readings).map {
            val (e, pf) = BeatFeel.energies(x, 0, 44100.0 / 512.0, last, period)
            feel.update(e!!, pf)
        }
    }

    @Test
    fun feelHearsTripletsSlowly() {
        val (x, last) = onsets(listOf(1.0 / 3 to 0.6f, 2.0 / 3 to 0.6f))
        val flags = feelAfter(30, x, last)
        // Pooled evidence first (about 14 readings), then 5 strong readings in a row.
        assertFalse("$flags", flags.subList(0, 17).contains(true))
        assertTrue("$flags", flags.subList(18, flags.size).all { it })
    }

    @Test
    fun feelStaysSimpleForStraightOrSwungEighths() {
        val (straight, l1) = onsets(listOf(0.5 to 0.6f))
        assertFalse(feelAfter(40, straight, l1).contains(true))
        // A swung eighth sits at two thirds alone: that is still simple time.
        val (swung, l2) = onsets(listOf(2.0 / 3 to 0.6f))
        assertFalse(feelAfter(40, swung, l2).contains(true))
        // Triplets with a busier halfway point: not strong enough.
        val (mixed, l3) = onsets(listOf(1.0 / 3 to 0.5f, 0.5 to 0.4f, 2.0 / 3 to 0.5f))
        assertFalse(feelAfter(40, mixed, l3).contains(true))
    }

    @Test
    fun feelStartsOverWhenTheBeatChangesLevel() {
        val (x, last) = onsets(listOf(1.0 / 3 to 0.6f, 2.0 / 3 to 0.6f))
        val feel = BeatFeel()
        repeat(30) {
            val (e, pf) = BeatFeel.energies(x, 0, 44100.0 / 512.0, last, 0.6)
            feel.update(e!!, pf)
        }
        assertTrue(feel.compound)
        // The beat is now read at the eighth: a third of the period. The evidence starts over.
        val (e, pf) = BeatFeel.energies(x, 0, 44100.0 / 512.0, last, 0.2)
        assertFalse(feel.update(e!!, pf))
    }

    // ---- In the analyser ----

    private fun clicks(bpm: Double, seconds: Double, bar: Int): FloatArray =
        Synth.clickTrack(bpm, seconds, accents = listOf(1f) + List(bar - 1) { 0.5f })

    @Test
    fun bandFollowsTheBeatWithBeatThis() {
        val net = ScoreBeatNetwork(120.0, 3, 20.0)
        var made: Pair<Int, Int>? = null
        val analyzer = MusicAnalyzer(Synth.SAMPLE_RATE, AnalysisPreset.BAND, beats = { rate, hop ->
            made = rate to hop
            BeatTracker(net, rate, hopSize = hop, name = BeatThisModel.SMALL.title)
        })
        assertEquals(Synth.SAMPLE_RATE, made?.first)
        assertEquals(512, made?.second)
        var firstBpmAt: Float? = null
        val result = Synth.analyze(clicks(120.0, 12.5, 3), analyzer) { r ->
            if (r.bpm != null && firstBpmAt == null) firstBpmAt = r.secondsHeard
        }
        assertEquals(9, net.calls) // 4 s .. 12 s
        // The first reading comes at 4 s.
        assertEquals(4f, firstBpmAt ?: 0f, 0.05f)
        assertEquals(120f, result.bpm ?: 0f, 0.01f)
        assertTrue(result.tempoLocked)
        assertEquals(3, result.beatsPerBar)
        assertEquals("3/4", result.meterLabel)
        assertFalse(result.meterCompound)
        assertTrue(result.meterConfidence > 0)
        assertEquals(500f, result.beatPeriodMillis, 0.01f)
        assertEquals("Beat This! small", result.beatModel)
        assertEquals(1, result.beatEverySeconds)
        assertFalse(result.beatTooSlow)
        // The published beat is the latest one at or before now, on the clock of the audio.
        val lastBeat = result.lastBeatAtNanos / 1e9
        val k = Math.rint(lastBeat / 0.5)
        assertEquals(k * 0.5, lastBeat, 0.011)
        assertTrue(lastBeat <= result.secondsHeard + 0.01)
        assertTrue(lastBeat > result.secondsHeard - 0.51)
        assertEquals(k.toInt() % 3, result.lastBeatIndexInBar)
    }

    @Test
    fun guitarUsesTheSmallModelTooAndVoicesNone() {
        assertEquals(BeatThisModel.SMALL, AnalysisPreset.BAND.beatModel)
        // Android has only the small one: the full one costs a phone CPU two to three times as much.
        assertEquals(BeatThisModel.SMALL, AnalysisPreset.GUITAR.beatModel)
        assertNull(AnalysisPreset.VOICES.beatModel)
        var asked = false
        val voices = MusicAnalyzer(Synth.SAMPLE_RATE, AnalysisPreset.VOICES, beats = { rate, hop ->
            asked = true
            BeatTracker(ScoreBeatNetwork(120.0, 4, 10.0), rate, hopSize = hop)
        })
        val r = Synth.analyze(clicks(120.0, 6.0, 4), voices)
        assertFalse("Voices taps its tempo and never builds the tracker", asked)
        assertNull(r.beatModel)
    }

    @Test
    fun fallsBackToTheStandardTrackerWhenTheNetworkFails() {
        val audio = Synth.clickTrack(110.0, 16.0, accents = listOf(1f, 0.4f, 0.7f, 0.4f))
        val standard = Synth.analyze(audio, MusicAnalyzer(Synth.SAMPLE_RATE, AnalysisPreset.BAND))
        val net = FailingBeatNetwork()
        val failing = MusicAnalyzer(Synth.SAMPLE_RATE, AnalysisPreset.BAND, beats = { r, h -> BeatTracker(net, r, hopSize = h) })
        var before: AnalysisResult? = null
        val result = Synth.analyze(audio, failing) { r -> if (r.secondsHeard < 3.9f) before = r }
        assertEquals("a failed network is not asked again", 1, net.calls)
        assertNotNull("named while it is expected to work", before?.beatModel)
        assertNull(result.beatModel)
        // Silently: Details says nothing about a slow phone.
        assertFalse(result.beatTooSlow)
        assertEquals(standard.bpm, result.bpm)
        assertEquals(standard.meterLabel, result.meterLabel)
        assertEquals(standard.lastBeatAtNanos, result.lastBeatAtNanos)
        assertEquals(standard.beatsPerBar, result.beatsPerBar)
        assertEquals(standard.lastBeatIndexInBar, result.lastBeatIndexInBar)
    }

    @Test
    fun theStandardTrackerIsUnchangedWithoutBeatThis() {
        val audio = Synth.clickTrack(130.0, 12.0, accents = listOf(1f, 0.4f, 0.4f))
        val r = Synth.analyze(audio, MusicAnalyzer(Synth.SAMPLE_RATE, AnalysisPreset.GUITAR))
        assertNull(r.beatModel)
        assertEquals(0f, r.beatMillisPerSecond)
        assertEquals(0, r.beatEverySeconds)
        assertEquals(130.0, (r.bpm ?: 0f).toDouble(), 4.0)
    }

    @Test
    fun chordsKeepTheirOwnBeatGrid() {
        // The chord grid stays with the standard tracker, so chords come out the same either way.
        var audio = Synth.progression(listOf(listOf(60, 64, 67), listOf(57, 60, 64), listOf(53, 57, 60), listOf(55, 59, 62)), secondsEach = 2.0)
        audio = Synth.mix(audio, Synth.clickTrack(120.0, 8.0, accents = listOf(1f, 0.5f, 0.5f, 0.5f)))
        val plain = Synth.analyze(audio, MusicAnalyzer(Synth.SAMPLE_RATE, AnalysisPreset.BAND))
        val bt = Synth.analyze(audio, MusicAnalyzer(Synth.SAMPLE_RATE, AnalysisPreset.BAND, beats = { r, h ->
            BeatTracker(ScoreBeatNetwork(120.0, 4, 10.0), r, hopSize = h)
        }))
        assertEquals(plain.chordSegments, bt.chordSegments)
        assertEquals(plain.displayKey, bt.displayKey)
        assertEquals(4, bt.beatsPerBar)
    }

    @Test
    fun onAWorkerTheBeatsStayWhereTheyWerePlayed() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val net = ScoreBeatNetwork(100.0, 4, 12.0)
        val tracker = BeatTracker(net, 48000, worker = worker)
        val audio = FloatArray(48000 * 9 + 4800) { i -> (0.1 * sin(i * 0.05)).toFloat() }
        var i = 0
        while (i < audio.size) {
            val n = minOf(512, audio.size - i)
            tracker.pushOnset(0f)
            tracker.push(audio, i, n)
            i += n
        }
        worker.submit {}.get()
        worker.shutdown()
        assertEquals(6, tracker.readings) // 4 s .. 9 s
        val r = tracker.latest!!
        assertEquals(9.0, r.moment, 0.0)
        assertEquals(100.0, r.bpm ?: 0.0, 0.01)
        assertEquals(Math.rint(r.lastBeatSeconds!! / 0.6), r.lastBeatSeconds!! / 0.6, 0.02)
        assertEquals(4, r.beatsPerBar)
        assertEquals(0, tracker.skipped)
        assertFalse(tracker.failed)
        tracker.reset()
        assertNull(tracker.latest)
    }

    @Test
    fun silenceSkipsTheNetwork() {
        val net = ScoreBeatNetwork(100.0, 4, 12.0)
        val tracker = BeatTracker(net, 44100)
        tracker.push(FloatArray(44100 * 6), 0, 44100 * 6)
        assertEquals(0, net.calls)
        assertEquals(2, tracker.readings)
        assertNull(tracker.latest?.bpm)
    }
}

/**
 * Android's slow phones: the first windows are timed, and Beat This! goes to one window every
 * two seconds, then back to the standard tracker, when they take too long. A fake clock stands
 * for the phone: the network moves it on by as long as each window "took".
 */
class BeatCadenceTest {
    private class Clock { var nanos = 0L }

    /** Wraps a network so that call [n] takes [millis] (n) milliseconds on [clock]. */
    private class SlowBeatNetwork(private val inner: BeatNetwork, private val clock: Clock, private val millis: (Int) -> Double) : BeatNetwork {
        var calls = 0
            private set
        override fun logits(spect: FloatArray): BeatLogits? {
            val out = inner.logits(spect)
            clock.nanos += (millis(calls) * 1e6).toLong()
            calls++
            return out
        }
    }

    /** Moments of the windows: 4, 5 and 6 s, then every two seconds. */
    private val slowedAfterThree: (Int) -> Double = { if (it < 3) 4.0 + it else 6.0 + 2 * (it - 2) }

    private fun analyzer(net: BeatNetwork, clock: Clock, cadence: BeatCadence = BeatCadence(), preset: AnalysisPreset = AnalysisPreset.BAND) =
        MusicAnalyzer(Synth.SAMPLE_RATE, preset, beats = { r, h -> BeatTracker(net, r, hopSize = h, name = BeatThisModel.SMALL.title, cadence = cadence, clock = { clock.nanos }) })

    private fun clicks(bpm: Double, seconds: Double) = Synth.clickTrack(bpm, seconds, accents = listOf(1f, 0.5f, 0.5f, 0.5f))

    @Test
    fun aQuickPhoneKeepsOneWindowASecond() {
        val clock = Clock()
        // A slow first run (the model warming up) does not count: the median of three does.
        val net = SlowBeatNetwork(ScoreBeatNetwork(120.0, 4, 20.0), clock) { if (it == 0) 900.0 else 300.0 }
        val r = Synth.analyze(clicks(120.0, 12.5), analyzer(net, clock))
        assertEquals(9, net.calls) // 4 s .. 12 s
        assertEquals(1, r.beatEverySeconds)
        assertEquals(120f, r.bpm ?: 0f, 0.01f)
        assertEquals("Beat This! small", r.beatModel)
        // The cost per second of audio is the cost of a window, a running average from the first.
        assertEquals(300.0 + 600.0 * Math.pow(0.8, 8.0), r.beatMillisPerSecond.toDouble(), 0.01)
    }

    @Test
    fun aSlowPhoneRunsAWindowEveryTwoSeconds() {
        val clock = Clock()
        val net = SlowBeatNetwork(ScoreBeatNetwork(120.0, 4, 30.0, momentOf = slowedAfterThree), clock) { 700.0 }
        val cadence = BeatCadence()
        var firstBpmAt: Float? = null
        val r = Synth.analyze(clicks(120.0, 16.5), analyzer(net, clock, cadence)) { a ->
            if (a.bpm != null && firstBpmAt == null) firstBpmAt = a.secondsHeard
        }
        // 4, 5 and 6 s are timed (700 ms each, more than 500), then 8, 10, 12, 14 and 16 s.
        assertEquals(8, net.calls)
        assertEquals(2, cadence.everySeconds)
        assertFalse(cadence.tooSlow)
        assertEquals(2, r.beatEverySeconds)
        assertFalse(r.beatTooSlow)
        assertEquals("Beat This! small", r.beatModel)
        assertEquals(120f, r.bpm ?: 0f, 0.01f)
        assertTrue(r.tempoLocked)
        assertEquals(4, r.beatsPerBar)
        // 700 ms every two seconds is 350 ms per second of audio (a running average, from 700
        // per second for the three windows a second apart).
        assertEquals(350.0 + 350.0 * Math.pow(0.8, 5.0), r.beatMillisPerSecond.toDouble(), 0.01)
        // The published beat still lands on the beat grid.
        val lastBeat = r.lastBeatAtNanos / 1e9
        assertEquals(Math.rint(lastBeat / 0.5) * 0.5, lastBeat, 0.011)
        assertEquals(4f, firstBpmAt ?: 0f, 0.05f)
    }

    @Test
    fun aPhoneTooSlowEvenThenGoesBackToTheStandardTracker() {
        val clock = Clock()
        val net = SlowBeatNetwork(ScoreBeatNetwork(110.0, 4, 30.0, momentOf = slowedAfterThree), clock) { 1800.0 }
        val cadence = BeatCadence()
        val audio = Synth.clickTrack(110.0, 20.0, accents = listOf(1f, 0.4f, 0.7f, 0.4f))
        val standard = Synth.analyze(audio, MusicAnalyzer(Synth.SAMPLE_RATE, AnalysisPreset.BAND))
        val r = Synth.analyze(audio, analyzer(net, clock, cadence))
        // 4, 5 and 6 s at one a second, then 8, 10 and 12 s at one every two: still 1.8 s each.
        assertEquals("not asked again once too slow", 6, net.calls)
        assertTrue(cadence.tooSlow)
        assertTrue("Details says so", r.beatTooSlow)
        assertNull(r.beatModel)
        assertEquals(standard.bpm, r.bpm)
        assertEquals(standard.meterLabel, r.meterLabel)
        assertEquals(standard.lastBeatAtNanos, r.lastBeatAtNanos)
        assertEquals(standard.lastBeatIndexInBar, r.lastBeatIndexInBar)
        // The next song (a new analyser sharing what was learnt) goes straight to the standard tracker.
        val next = Synth.analyze(audio, analyzer(net, clock, cadence))
        assertEquals(6, net.calls)
        assertTrue(next.beatTooSlow)
        assertEquals(standard.bpm, next.bpm)
    }

    @Test
    fun theNextSongStartsAtTheCadenceLearnt() {
        val clock = Clock()
        val cadence = BeatCadence()
        val first = SlowBeatNetwork(ScoreBeatNetwork(120.0, 4, 30.0, momentOf = slowedAfterThree), clock) { 700.0 }
        Synth.analyze(clicks(120.0, 8.5), analyzer(first, clock, cadence))
        assertEquals(2, cadence.everySeconds)
        // The next analyser runs every two seconds from 4 s, and keeps it at 700 ms.
        val next = SlowBeatNetwork(ScoreBeatNetwork(120.0, 4, 30.0, momentOf = { 4.0 + 2 * it }), clock) { 700.0 }
        val r = Synth.analyze(clicks(120.0, 12.5), analyzer(next, clock, cadence))
        assertEquals(5, next.calls) // 4, 6, 8, 10 and 12 s
        assertEquals(2, r.beatEverySeconds)
        assertFalse(cadence.tooSlow)
        assertEquals(120f, r.bpm ?: 0f, 0.01f)
    }

    @Test
    fun silentWindowsAreNotTimed() {
        val clock = Clock()
        val net = SlowBeatNetwork(ScoreBeatNetwork(100.0, 4, 12.0), clock) { 2000.0 }
        val cadence = BeatCadence()
        val tracker = BeatTracker(net, 44100, cadence = cadence, clock = { clock.nanos })
        // A little more than 9 s: the resampler holds back its last few milliseconds.
        tracker.push(FloatArray(44100 * 9 + 4410), 0, 44100 * 9 + 4410)
        assertEquals(0, net.calls)
        assertEquals(1, cadence.everySeconds)
        assertEquals(6, tracker.readings) // 4 s .. 9 s, all silent
    }
}
