package com.donfluids.piano

import kotlin.math.tanh

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

    /** Pitch classes the band's bass and chords are sounding (the drums have no pitch to speak of). */
    val soundingMask: Int get() = if (volume > 0.01f) comp.soundingMask or bass.soundingMask else 0

    @Volatile var style: Style = Style.WORSHIP_SLOW
        set(value) { field = value; comp.tone = value.compTone; restart = true }
    @Volatile var bpm: Int = 72
    /** Overall band level, 0..1. */
    @Volatile var volume: Float = 0.8f
    /** Play a drum fill into the next downbeat when the chord changes. */
    @Volatile var fillsOnChordChange: Boolean = true
    @Volatile var playing: Boolean = false
        set(value) { if (value && !field) restart = true; field = value; if (!value) { stopNotes = true; startAtNanos = 0L } }

    /** A start scheduled for a moment in the device's clock, so the band can enter on a downbeat. */
    @Volatile var startAtNanos: Long = 0L
        private set

    /** Starts the band at [atNanos] (System.nanoTime() base), bar 1 beat 1 on that instant. */
    fun startAt(atNanos: Long) { startAtNanos = atNanos }

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
    private class Event(val sample: Double, val step: Int, val drum: DrumHit?, val bass: BassNote?, val comp: CompHit?, val fill: Boolean = false)
    @Volatile private var fillRequested = false
    private var fillActiveThisBar = false
    private var crashNextDownbeat = false
    private var lastFillBar = -10L
    /** Fills played so far; for tests. */
    @Volatile var fillCount: Int = 0; private set
    private var events: List<Event> = emptyList()
    private var eventsForStyle: Style? = null
    private var eventsForBpm = 0
    private val compNotes = ArrayList<Int>()
    private var bassNote = -1
    private val scratch = FloatArray(4096)

    /** Sets the chord the band plays; takes effect within a few milliseconds. */
    fun setChord(c: BandChord?) {
        if (c == null) return
        if (playing && fillsOnChordChange && c != currentChord) fillRequested = true
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
        s.fill.forEach { list += Event(pos(it.step), it.step, it, null, null, fill = true) }
        events = list.sortedBy { it.sample }
        eventsForStyle = s
        eventsForBpm = bpm
    }

    /** Mixes the band into [out]; [nowNanos] is when the first sample reaches the device. */
    fun mix(out: FloatArray, frames: Int, nowNanos: Long) {
        if (stopNotes) { stopNotes = false; comp.allNotesOff(); bass.allNotesOff(); compNotes.clear(); bassNote = -1 }
        var done = 0
        if (!playing) {
            val at = startAtNanos
            val offset = if (at > 0L) ((at - nowNanos) / 1e9 * sampleRate).toLong() else Long.MAX_VALUE
            if (offset >= frames) {
                // Not yet (or not scheduled): let the last notes ring out.
                renderInto(out, 0, frames)
                return
            }
            // The scheduled instant falls inside this block: silence up to it, then bar 1 beat 1.
            val skip = offset.coerceAtLeast(0L).toInt()
            renderInto(out, 0, skip)
            done = skip
            startAtNanos = 0L
            playing = true
        }
        if (restart) { restart = false; samplePos = 0.0; nextEvent = 0; beatIndex = -1; barCount = 0; comp.allNotesOff(); bass.allNotesOff(); compNotes.clear(); bassNote = -1; fillRequested = false; fillActiveThisBar = false; crashNextDownbeat = false; lastFillBar = -10L }
        if (eventsForStyle !== style || eventsForBpm != bpm) { rebuildEvents(); nextEvent = events.indexOfFirst { it.sample >= samplePos }.let { if (it < 0) events.size else it } }
        val barSamples = stepSamples() * style.stepsPerBar
        val fillStartSample = style.fillStart * stepSamples()
        while (done < frames) {
            // A requested fill takes this bar's last beat if that beat is still ahead, else the
            // next bar's; never two fills within two bars.
            if (fillRequested && !fillActiveThisBar && samplePos < fillStartSample && barCount - lastFillBar >= 2) {
                fillRequested = false
                fillActiveThisBar = true
                lastFillBar = barCount
                fillCount++
            }
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
            if (samplePos >= barSamples) {
                samplePos -= barSamples; nextEvent = 0; barCount++
                if (fillActiveThisBar) { fillActiveThisBar = false; crashNextDownbeat = true }
            }
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
        // Drum hits peak far above their body. A gentle saturation rounds off just the first
        // instant of each hit, so the master limiter does not have to pull the whole band down on
        // every kick (which would pump); the overtones it adds help a kick on a small speaker.
        for (k in 0 until frames) out[offset + k] = before[k] + DRUM_KNEE * tanh(out[offset + k] * 0.8f / DRUM_KNEE).toFloat() * v
    }

    private fun fire(e: Event, atNanos: Long) {
        val s = style
        if (e.step % s.stepsPerBeat == 0 && e.drum != null) {
            val beat = e.step / s.stepsPerBeat
            if (beat != beatIndex || e.step == 0) { beatIndex = beat; beatAtNanos = atNanos }
        }
        e.drum?.let { hit ->
            // During a fill the pattern's drums in the fill region give way to the fill's hits.
            val inFillRegion = e.step >= s.fillStart
            when {
                e.fill -> if (fillActiveThisBar) drums.trigger(hit.drum, hit.velocity)
                inFillRegion && fillActiveThisBar -> Unit
                else -> drums.trigger(hit.drum, hit.velocity)
            }
            if (e.step == 0 && crashNextDownbeat && !e.fill) {
                crashNextDownbeat = false
                if (s.drums.none { it.step == 0 && it.drum == Drum.CRASH }) drums.trigger(Drum.CRASH, 0.6f)
            }
        }
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
        /** Drum level above which hits are rounded off. */
        const val DRUM_KNEE = 0.35f

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
