package com.donfluids.piano

/**
 * The band: plays a [Style] for the current [chord] at [bpm], mixing synthesised drums, a
 * bass and an electric-piano comp into the audio stream. Events are triggered on the exact
 * sample, so the groove is tight however the UI thread is doing. The display reads
 * [beatIndex] and [beatAtNanos], the same way it reads the metronome.
 */
class Accompaniment(private val sampleRate: Int) {
    val drums = DrumKit(sampleRate)
    val bass = PianoSynth(sampleRate, maxVoices = 4).also { it.tone = PianoSynth.Tone.BASS; it.volume = 1f }
    val comp = PianoSynth(sampleRate, maxVoices = 12).also { it.tone = PianoSynth.Tone.ELECTRIC; it.volume = 1f }

    @Volatile var style: Style = Style.WORSHIP_SLOW
        set(value) { field = value; comp.tone = value.compTone; restart = true }
    @Volatile var bpm: Int = 72
    /** Overall band level, 0..1. */
    @Volatile var volume: Float = 0.8f
    @Volatile var playing: Boolean = false
        set(value) { if (value && !field) restart = true; field = value; if (!value) stopNotes = true }

    @Volatile private var pendingChord: BandChord? = null
    @Volatile private var chordChanged = false
    private var chord: BandChord? = null

    /** The chord the band is playing, for the display. */
    val currentChord: BandChord? get() = pendingChord ?: chord

    @Volatile var beatIndex: Int = -1; private set
    @Volatile var beatAtNanos: Long = 0L; private set
    @Volatile var barCount: Long = 0; private set

    @Volatile private var restart = true
    @Volatile private var stopNotes = false
    private var samplePos = 0.0 // position within the bar, in samples
    private var nextEvent = 0
    private class Event(val sample: Double, val step: Int, val drum: DrumHit?, val bass: BassNote?, val comp: CompHit?)
    private var events: List<Event> = emptyList()
    private var eventsForStyle: Style? = null
    private var eventsForBpm = 0
    private val compNotes = ArrayList<Int>()
    private var bassNote = -1
    private val scratch = FloatArray(4096)

    /** Sets the chord the band plays; takes effect within a few milliseconds. */
    fun setChord(c: BandChord?) {
        if (c == null) return
        pendingChord = c
        chordChanged = true
    }

    private fun stepSamples(): Double = 60.0 / bpm.coerceIn(20, 300) / style.stepsPerBeat * sampleRate

    private fun rebuildEvents() {
        val s = style
        val step = stepSamples()
        fun pos(st: Int): Double {
            // Swing delays the off-beat 8th (the middle of each beat) by a fraction of the beat.
            val offbeat = s.stepsPerBeat % 2 == 0 && st % s.stepsPerBeat == s.stepsPerBeat / 2
            return (st + if (offbeat) s.swing * s.stepsPerBeat else 0f) * step
        }
        val list = ArrayList<Event>()
        s.drums.forEach { list += Event(pos(it.step), it.step, it, null, null) }
        s.bass.forEach { list += Event(pos(it.step), it.step, null, it, null) }
        s.comp.forEach { list += Event(pos(it.step), it.step, null, null, it) }
        events = list.sortedBy { it.sample }
        eventsForStyle = s
        eventsForBpm = bpm
    }

    /** Mixes the band into [out]; [nowNanos] is when the first sample reaches the device. */
    fun mix(out: FloatArray, frames: Int, nowNanos: Long) {
        if (stopNotes) { stopNotes = false; comp.allNotesOff(); bass.allNotesOff(); compNotes.clear(); bassNote = -1 }
        if (!playing) {
            // Let the last notes ring out after a stop.
            renderInto(out, 0, frames)
            return
        }
        if (restart) { restart = false; samplePos = 0.0; nextEvent = 0; beatIndex = -1; barCount = 0; comp.allNotesOff(); bass.allNotesOff(); compNotes.clear(); bassNote = -1 }
        if (eventsForStyle !== style || eventsForBpm != bpm) { rebuildEvents(); nextEvent = events.indexOfFirst { it.sample >= samplePos }.let { if (it < 0) events.size else it } }
        val barSamples = stepSamples() * style.stepsPerBar
        var done = 0
        while (done < frames) {
            if (chordChanged) {
                chordChanged = false
                val c = pendingChord
                if (c != null && c != chord) { chord = c; retriggerComp() }
            }
            val untilEvent = if (nextEvent < events.size) events[nextEvent].sample - samplePos else barSamples - samplePos
            val untilBar = barSamples - samplePos
            val seg = minOf(frames - done, Math.ceil(minOf(untilEvent, untilBar)).toInt().coerceAtLeast(1))
            renderInto(out, done, seg)
            done += seg
            samplePos += seg
            while (nextEvent < events.size && events[nextEvent].sample <= samplePos) {
                fire(events[nextEvent], nowNanos + ((done - (samplePos - events[nextEvent].sample)) / sampleRate * 1e9).toLong())
                nextEvent++
            }
            if (samplePos >= barSamples) { samplePos -= barSamples; nextEvent = 0; barCount++ }
        }
    }

    private fun renderInto(out: FloatArray, offset: Int, frames: Int) {
        val v = volume
        var i = 0
        while (i < frames) {
            val n = minOf(frames - i, scratch.size)
            comp.render(scratch, n)
            for (k in 0 until n) out[offset + i + k] += scratch[k] * 0.55f * v
            bass.render(scratch, n)
            for (k in 0 until n) out[offset + i + k] += scratch[k] * 0.9f * v
            i += n
        }
        val before = out.copyOfRange(offset, offset + frames)
        java.util.Arrays.fill(out, offset, offset + frames, 0f)
        drums.render(out, offset, frames)
        for (k in 0 until frames) out[offset + k] = before[k] + out[offset + k] * 0.8f * v
    }

    private fun fire(e: Event, atNanos: Long) {
        val s = style
        if (e.step % s.stepsPerBeat == 0 && e.drum != null) {
            val beat = e.step / s.stepsPerBeat
            if (beat != beatIndex || e.step == 0) { beatIndex = beat; beatAtNanos = atNanos }
        }
        e.drum?.let { drums.trigger(it.drum, it.velocity) }
        val c = chord ?: return
        e.bass?.let { b ->
            if (bassNote >= 0) bass.noteOff(bassNote)
            bassNote = bassMidi(c, b.degree)
            bass.noteOn(bassNote, b.velocity)
        }
        e.comp?.let { h ->
            compNotes.forEach { comp.noteOff(it) }
            compNotes.clear()
            compNotes += voicing(c, h.voicing)
            compNotes.forEach { comp.noteOn(it, h.velocity) }
            compOffAt = samplePos + h.lengthSteps * stepSamples() - 1
        }
        // Comp notes end before the next hit so chords are not legato mush.
        if (compOffAt > 0 && samplePos >= compOffAt) { compNotes.forEach { comp.noteOff(it) }; compNotes.clear(); compOffAt = -1.0 }
    }
    private var compOffAt = -1.0

    /** On a chord change the comp restrikes the new chord at once; the bass waits for its next note. */
    private fun retriggerComp() {
        if (compNotes.isEmpty()) return
        compNotes.forEach { comp.noteOff(it) }
        val c = chord ?: return
        compNotes.clear()
        compNotes += voicing(c, if (style.comp.all { it.voicing == Voicing.POWER }) Voicing.POWER else Voicing.FULL)
        compNotes.forEach { comp.noteOn(it, 0.6f) }
    }

    companion object {
        /** Bass lives in the octave below middle C's octave: roots E1 to Eb2 region. */
        fun bassMidi(c: BandChord, degree: BassDegree): Int {
            val root = 36 + Math.floorMod(c.root - 4, 12) + 4 // 40 (E2) .. 51 (Eb3)
            return when (degree) {
                BassDegree.ROOT -> root
                BassDegree.THIRD -> root + c.third
                BassDegree.FIFTH -> root + c.fifth
                BassDegree.SIXTH -> root + 9
                BassDegree.SEVENTH -> root + c.seventh
                BassDegree.OCTAVE -> root + 12
                BassDegree.FIFTH_BELOW -> root - 5
            }
        }

        /** Comp sits around middle C, root between C3 and B3. */
        fun voicing(c: BandChord, v: Voicing): List<Int> {
            val root = 48 + Math.floorMod(c.root, 12)
            val tones = c.quality.intervals.map { root + it }
            return when (v) {
                Voicing.FULL -> tones
                Voicing.UPPER -> tones.drop(1) + (root + 12)
                Voicing.ARP0 -> listOf(tones[0])
                Voicing.ARP1 -> listOf(tones[1])
                Voicing.ARP2 -> listOf(tones[2])
                Voicing.ARP3 -> listOf(root + 12)
                Voicing.POWER -> listOf(root, root + 7, root + 12) // root, fifth, octave: no third
            }
        }
    }
}
