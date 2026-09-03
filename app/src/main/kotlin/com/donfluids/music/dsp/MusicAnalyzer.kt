package com.donfluids.music.dsp

import com.donfluids.music.music.ChordLabel
import com.donfluids.music.music.ChordSegment
import com.donfluids.music.music.Key
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything the screen needs, as an immutable snapshot. */
data class AnalysisResult(
    val secondsHeard: Float = 0f,
    /** Input level 0..1 (roughly -60 dBFS .. 0 dBFS). */
    val level: Float = 0f,
    /** Per-pitch-class energy of recent audio, normalised so the loudest bin is 1. */
    val chroma: List<Float> = List(12) { 0f },
    val key: Key? = null,
    val keyConfidence: Float = 0f,
    /** The next best guesses, best first. */
    val keyAlternatives: List<Pair<Key, Float>> = emptyList(),
    /** Cents sharp (+) or flat (-) of A4 = 440 Hz. */
    val tuningCents: Float = 0f,
    val tuningConfidence: Float = 0f,
    val bpm: Float? = null,
    val tempoStrength: Float = 0f,
    val tempoLocked: Boolean = false,
    val beatPeriodMillis: Float = 0f,
    /** System.nanoTime() of the most recent beat; the UI extrapolates the pulse from this. */
    val lastBeatAtNanos: Long = 0L,
    val meterLabel: String? = null,
    val beatsPerBar: Int = 0,
    val meterCompound: Boolean = false,
    val meterConfidence: Float = 0f,
    /** Bar position (0 = downbeat) of the beat at [lastBeatAtNanos]. */
    val lastBeatIndexInBar: Int = 0,
    val meterScores: Map<Int, Float> = emptyMap(),
    /** The triad sounding right now, if any. */
    val currentChord: ChordLabel? = null,
    /** Chords heard since the song started (or the last reset), oldest first, no-chord gaps removed. */
    val chordSegments: List<ChordSegment> = emptyList(),
    /** Share of chord time per chord, largest first. */
    val chordShares: List<Pair<ChordLabel, Float>> = emptyList(),
    /** Key suggested from the whole chord sequence, with its confidence and runner-up. */
    val songKey: Key? = null,
    val songKeyConfidence: Float = 0f,
    val songKeyCadences: Int = 0,
    val songKeyAlternative: Key? = null,
)

/**
 * Feeds raw mono audio through the onset, chroma, key, tempo and meter stages and keeps a
 * current [AnalysisResult]. Pure Kotlin: no Android types, so it is testable on the JVM.
 */
class MusicAnalyzer(
    val sampleRate: Int,
    val preset: AnalysisPreset = AnalysisPreset.BAND,
    val hopSize: Int = 512,
) {
    private val chromaExtractor = ChromaExtractor(
        sampleRate,
        memorySeconds = preset.keyMemorySeconds,
        minHz = preset.chromaMinHz,
        maxHz = preset.chromaMaxHz,
        rollOffHz = preset.chromaRollOffHz,
    )
    private val onsetDetector = OnsetDetector(
        sampleRate,
        minHz = preset.onsetMinHz,
        maxHz = preset.onsetMaxHz,
        gamma = preset.onsetGamma,
        lowBandMaxHz = preset.onsetLowBandMaxHz,
        lowBandWeight = preset.onsetLowBandWeight,
    )
    private val framesPerSecond = sampleRate.toDouble() / hopSize
    private val tempoTracker = TempoTracker(
        framesPerSecond,
        priorCenterBpm = preset.tempoPriorCenterBpm,
        priorWidthOctaves = preset.tempoPriorWidthOctaves,
    )
    private val meterDetector = MeterDetector(minBeats = preset.meterMinBeats)
    private val chordDetector = ChordDetector()

    private val windowSize = chromaExtractor.fftSize
    private val window = FloatArray(windowSize)
    private val pending = FloatArray(hopSize)
    private var pendingCount = 0
    private var hopIndex = 0L
    private var hopEndNanos = 0L
    private val hopSeconds = hopSize.toDouble() / sampleRate

    private val chromaEveryHops = 4
    private val rhythmEveryHops = (framesPerSecond / 2).roundToInt().coerceAtLeast(1)

    // Hysteresis so the display does not flicker between neighbours.
    private var keyCandidate: Key? = null
    private var keyCandidateHits = 0
    private var shownKey: Key? = null
    private val keyHitsToSwitch = 12 // chroma frames, about half a second

    private var meterCandidate: String? = null
    private var meterCandidateHits = 0
    private var shownMeter: MeterDetector.Result? = null
    private val meterHitsToSwitch = 3

    private var tempoAbsentRuns = 0

    // Song mode: the chord sequence since the last reset.
    private val segments = ArrayList<ChordSegment>()
    private var openChord: ChordLabel? = null
    private var openStart = 0f
    private var songKey: Key? = null
    private var songKeyConfidence = 0f
    private var songKeyCadences = 0
    private var songKeyAlternative: Key? = null
    private var chordShares: List<Pair<ChordLabel, Float>> = emptyList()

    private var levelRms = 0.0
    private var levelSamples = 0

    @Volatile
    var result: AnalysisResult = AnalysisResult()
        private set

    fun reset() {
        chromaExtractor.reset()
        onsetDetector.reset()
        tempoTracker.reset()
        window.fill(0f)
        pendingCount = 0
        hopIndex = 0
        keyCandidate = null
        keyCandidateHits = 0
        shownKey = null
        meterCandidate = null
        meterCandidateHits = 0
        shownMeter = null
        tempoAbsentRuns = 0
        chordDetector.reset()
        segments.clear()
        openChord = null
        openStart = 0f
        songKey = null
        songKeyConfidence = 0f
        songKeyCadences = 0
        songKeyAlternative = null
        chordShares = emptyList()
        result = AnalysisResult()
    }

    /**
     * Push [count] samples from [samples] (mono, -1..1). [nowNanos] is the System.nanoTime()
     * at which the last of these samples was captured. Returns true if [result] changed.
     */
    fun process(samples: FloatArray, count: Int, nowNanos: Long): Boolean {
        var changed = false
        var i = 0
        while (i < count) {
            val take = minOf(hopSize - pendingCount, count - i)
            System.arraycopy(samples, i, pending, pendingCount, take)
            pendingCount += take
            i += take
            if (pendingCount == hopSize) {
                val remaining = count - i
                hopEndNanos = nowNanos - (remaining.toDouble() / sampleRate * 1e9).toLong()
                if (runHop()) changed = true
                pendingCount = 0
            }
        }
        return changed
    }

    private fun runHop(): Boolean {
        System.arraycopy(window, hopSize, window, 0, windowSize - hopSize)
        System.arraycopy(pending, 0, window, windowSize - hopSize, hopSize)
        hopIndex++

        var sq = 0.0
        for (v in pending) sq += v.toDouble() * v
        levelRms += sq
        levelSamples += hopSize

        tempoTracker.push(onsetDetector.process(window, windowSize - onsetDetector.fftSize))

        var changed = false
        if (hopIndex % chromaEveryHops == 0L) {
            chromaExtractor.process(window, 0, hopSeconds * chromaEveryHops)
            updateKey()
            updateChords()
            changed = true
        }
        if (hopIndex % rhythmEveryHops == 0L) {
            updateRhythm()
            updateSongKey()
            changed = true
        }
        if (changed) publish()
        return changed
    }

    private var pendingKey: Key? = null
    private var pendingKeyConfidence = 0f
    private var pendingAlternatives: List<Pair<Key, Float>> = emptyList()

    private fun updateKey() {
        val ranked = KeyDetector.rank(chromaExtractor.accumulatedChroma)
        val confidence = KeyDetector.confidence(ranked).toFloat()
        val best = ranked.first().key
        val enoughSound = chromaExtractor.accumulatedWeight > 1e-4
        if (!enoughSound || confidence < 0.15f) {
            keyCandidateHits = 0
            return
        }
        if (best == keyCandidate) keyCandidateHits++ else { keyCandidate = best; keyCandidateHits = 1 }
        if (shownKey == null || keyCandidateHits >= keyHitsToSwitch) shownKey = best
        val shown = shownKey ?: return
        pendingKey = shown
        pendingKeyConfidence = if (shown == best) confidence
        else ranked.first { it.key == shown }.correlation.toFloat().coerceIn(0f, 1f) * 0.5f
        pendingAlternatives = ranked.filter { it.key != shown }.take(3)
            .map { it.key to it.correlation.toFloat().coerceIn(0f, 1f) }
    }

    /** Extends or starts a chord segment from the latest chroma frame. */
    private fun updateChords() {
        val now = (hopIndex * hopSeconds).toFloat()
        val chord = chordDetector.process(chromaExtractor.frameChroma)
        if (chord != openChord) {
            closeSegment(now)
            openChord = chord
            openStart = now
        }
    }

    private fun closeSegment(now: Float) {
        val chord = openChord
        if (chord != null && now > openStart) {
            val last = segments.lastOrNull()
            if (last != null && last.chord == chord && openStart - last.endSeconds < 0.05f) {
                segments[segments.size - 1] = ChordSegment(chord, last.startSeconds, now)
            } else {
                segments += ChordSegment(chord, openStart, now)
            }
        }
    }

    /** The chord sequence including the chord still sounding. */
    private fun currentSegments(): List<ChordSegment> {
        val now = (hopIndex * hopSeconds).toFloat()
        val chord = openChord
        if (chord == null || now <= openStart) return segments
        return segments + ChordSegment(chord, openStart, now)
    }

    private fun updateSongKey() {
        val all = currentSegments()
        val ranked = ChordKeyEstimator.suggest(all)
        if (ranked.isEmpty()) return
        val total = all.sumOf { it.duration.toDouble() }
        // A few seconds of chords are needed before a whole-song suggestion means anything.
        if (total < 4.0) return
        songKey = ranked[0].key
        songKeyCadences = ranked[0].cadences
        songKeyConfidence = ChordKeyEstimator.confidence(ranked).toFloat()
        songKeyAlternative = ranked[1].key
        chordShares = all.groupBy { it.chord!! }
            .map { (chord, segs) -> chord to (segs.sumOf { it.duration.toDouble() } / total).toFloat() }
            .sortedByDescending { it.second }
    }

    private var tempo: TempoTracker.Estimate? = null

    private fun updateRhythm() {
        val estimate = tempoTracker.estimate()
        if (estimate == null || estimate.strength < 0.12) {
            // Keep the last tempo briefly through a quiet bar, then let go.
            if (++tempoAbsentRuns > 6) { tempo = null; shownMeter = null; meterCandidate = null }
            return
        }
        tempoAbsentRuns = 0
        tempo = estimate

        val meter = meterDetector.analyze(
            tempoTracker.detrended, tempoTracker.detrendedLength,
            estimate.periodFrames, estimate.lastBeatFramesAgo,
        ) ?: return
        if (meter.label == meterCandidate) meterCandidateHits++ else { meterCandidate = meter.label; meterCandidateHits = 1 }
        val old = shownMeter
        if (old == null || meterCandidateHits >= meterHitsToSwitch) {
            shownMeter = meter
        } else {
            // Keep the displayed bar length, but take the downbeat position for that bar length
            // from the fresh analysis so the beat count stays in step with the new beat phase.
            shownMeterIndex = meter.lastBeatIndexByGrid[old.beatsPerBar to old.pulseDivisor] ?: shownMeterIndex
        }
        if (shownMeter === meter) shownMeterIndex = meter.lastBeatIndexInBar
    }
    private var shownMeterIndex = 0

    private fun publish() {
        val rms = if (levelSamples > 0) sqrt(levelRms / levelSamples) else 0.0
        levelRms = 0.0
        levelSamples = 0
        val level = if (rms > 0) ((20.0 * log10(rms) + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat() else 0f

        val chroma = chromaExtractor.accumulatedChroma
        val maxChroma = chroma.max().coerceAtLeast(1e-9f)
        val t = tempo
        val m = shownMeter
        // For uneven meters the counted pulse is a subdivision of the tracked beat.
        val divisor = m?.pulseDivisor ?: 1
        result = AnalysisResult(
            secondsHeard = (hopIndex * hopSeconds).toFloat(),
            level = level,
            chroma = chroma.map { it / maxChroma },
            key = pendingKey,
            keyConfidence = pendingKeyConfidence,
            keyAlternatives = pendingAlternatives,
            tuningCents = chromaExtractor.tuningOffsetCents().toFloat(),
            tuningConfidence = chromaExtractor.tuningConfidence().toFloat(),
            bpm = t?.let { (it.bpm * divisor).toFloat() },
            tempoStrength = t?.strength?.toFloat() ?: 0f,
            tempoLocked = t?.locked ?: false,
            beatPeriodMillis = t?.let { (it.periodFrames / divisor * hopSeconds * 1000.0).toFloat() } ?: 0f,
            lastBeatAtNanos = t?.let { hopEndNanos - (it.lastBeatFramesAgo * hopSeconds * 1e9).toLong() } ?: 0L,
            meterLabel = m?.label,
            beatsPerBar = m?.beatsPerBar ?: 0,
            meterCompound = m?.compound ?: false,
            meterConfidence = m?.confidence?.toFloat() ?: 0f,
            lastBeatIndexInBar = if (m != null) shownMeterIndex else 0,
            meterScores = m?.scores?.mapValues { it.value.toFloat() } ?: emptyMap(),
            currentChord = chordDetector.current,
            chordSegments = currentSegments(),
            chordShares = chordShares,
            songKey = songKey,
            songKeyConfidence = songKeyConfidence,
            songKeyCadences = songKeyCadences,
            songKeyAlternative = songKeyAlternative,
        )
    }
}
