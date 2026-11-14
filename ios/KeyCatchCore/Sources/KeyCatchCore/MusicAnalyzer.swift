import Foundation

/// Everything the screen needs, as an immutable snapshot.
public struct AnalysisResult: Sendable {
    public var secondsHeard: Float = 0
    /// Input level 0..1 (roughly -60 dBFS .. 0 dBFS).
    public var level: Float = 0
    /// Per-pitch-class energy of recent audio, normalised so the loudest bin is 1.
    public var chroma: [Float] = Array(repeating: 0, count: 12)
    /// The same over only the last half second or so, unnormalised: what is being sung right now.
    public var recentChroma: [Float] = Array(repeating: 0, count: 12)
    public var key: Key? = nil
    public var keyConfidence: Float = 0
    /// The other key of `key`'s pair: its relative minor or major, which uses the same notes.
    public var relativeKey: Key? = nil
    /// False while the singing has not shown which of the pair is home; `key` is then only the
    /// lean, and the screen should offer both.
    public var modeDecided: Bool = false
    /// False while the singing does not clearly point to one pair of keys (a scattered or
    /// drifting singer): `key` is then a best guess. Always true without a tracked melody.
    public var pairDecided: Bool = false
    /// The major-or-minor score: positive leans to the major of the pair, negative to the minor.
    public var modeScore: Float = 0
    /// How long `key` has been the shown key without changing.
    public var keyHeldSeconds: Float = 0
    /// The shown key's own fit (correlation with its profile), for comparing against `keyAlternatives`.
    public var keyScore: Float = 0
    /// The next best guesses, best first.
    public var keyAlternatives: [(Key, Float)] = []
    /// Cents sharp (+) or flat (-) of A4 = 440 Hz. For a tracked melody this follows the
    /// singers as they drift, and may pass 50 when they have drifted most of a semitone.
    public var tuningCents: Float = 0
    public var tuningConfidence: Float = 0
    /// Notes sung so far, on the singer's grid, when the preset tracks the melody.
    public var sungNotes: [SungNote] = []
    /// Where untrained singers are now, when they have wandered from the centre key (`key` and
    /// `displayKey` are the centre: where the singing spent most of its time). Equal to the centre
    /// while they stay put.
    public var keyNow: Key? = nil
    /// The lowest and highest keys most of the singing sat in; equal to the centre when steady.
    public var keyRangeLow: Key? = nil
    public var keyRangeHigh: Key? = nil
    /// How the singers' key has been moving lately.
    public var keyMove: KeyLevelTracker.Move = .steady
    /// How far the singer's notes scatter around the grid, in cents: a few for a good singer, 25+ for a poor one.
    public var singerSpreadCents: Float = 0
    /// True when the shown key came from the melody rather than from chroma.
    public var keyFromMelody: Bool = false
    public var bpm: Float? = nil
    public var tempoStrength: Float = 0
    public var tempoLocked: Bool = false
    public var beatPeriodMillis: Float = 0
    /// Clock time (nanoseconds, the caller's clock) of the most recent beat; the UI extrapolates the pulse from this.
    public var lastBeatAtNanos: Int = 0
    public var meterLabel: String? = nil
    public var beatsPerBar: Int = 0
    public var meterCompound: Bool = false
    public var meterConfidence: Float = 0
    /// Bar position (0 = downbeat) of the beat at `lastBeatAtNanos`.
    public var lastBeatIndexInBar: Int = 0
    public var meterScores: [Int: Float] = [:]
    /// The key to show: the chord-based estimate once it has led convincingly for a while,
    /// the note-based estimate before that. Switching has hysteresis so it does not flip.
    public var displayKey: Key? = nil
    public var displayKeyConfidence: Float = 0
    public var displayKeyFromChords: Bool = false
    /// The triad sounding right now, if any (confirmed with look-ahead, so slightly late).
    public var currentChord: ChordLabel? = nil
    /// The tracker's best guess before look-ahead; faster but less reliable.
    public var provisionalChord: ChordLabel? = nil
    /// Chords heard since the song started (or the last reset), oldest first, no-chord gaps removed.
    public var chordSegments: [ChordSegment] = []
    /// Share of chord time per chord, largest first.
    public var chordShares: [(ChordLabel, Float)] = []
    /// Key suggested from the whole chord sequence, with its confidence and runner-up.
    public var songKey: Key? = nil
    public var songKeyConfidence: Float = 0
    public var songKeyCadences: Int = 0
    public var songKeyAlternative: Key? = nil
    /// Which melody pitch estimator runs, if any, and its average cost per frame in milliseconds.
    public var pitchModel: String? = nil
    public var pitchMillis: Float = 0
    /// What hears the notes for chords and key: "Basic Pitch", or nil for the spectral chroma.
    public var notesModel: String? = nil
    /// Its running cost in milliseconds per second of audio.
    public var notesMillisPerSecond: Float = 0
    /// What follows the beat: a Beat This! model, or nil for the standard tracker.
    public var beatModel: String? = nil
    /// Its running cost in milliseconds per second of audio.
    public var beatMillisPerSecond: Float = 0

    public init() {}
}

/// Feeds raw mono audio through the onset, chroma, key, tempo and meter stages and keeps a
/// current `AnalysisResult`. Plain Swift with no platform types, so it is testable on Linux.
/// Not thread-safe: call it from one thread (the audio thread) and read `result` from there,
/// or copy it out.
public final class MusicAnalyzer {
    public let sampleRate: Int
    public let preset: AnalysisPreset
    public let hopSize: Int

    private let chromaExtractor: ChromaExtractor
    private let onsetDetector: OnsetDetector
    private let framesPerSecond: Double
    private let tempoTracker: TempoTracker
    private let meterDetector: MeterDetector
    private let chordTracker = ChordTracker()
    private let pitchTracker: PitchEstimator?
    private var pitchMillis = 0.0
    private let heard: HeardNotes?
    /// Beat This!, when it follows the beat; the standard tracker keeps running beside it for
    /// the chord grid, and takes over if the network fails.
    private let beatThis: BeatTracker?
    /// How far behind the audio the chord and key finders read when `heard` is used, in hops.
    private var readBehindHops = 0
    private func behindHops(_ h: HeardNotes) -> Int { Int((h.delaySeconds * Double(sampleRate) / Double(hopSize)).rounded(.up)) }
    private var heardFrame = [Float](repeating: 0, count: 12)
    private var heardAccum = [Float](repeating: 0, count: 12)
    private var heardWeight = 0.0
    /// Pitch-class energy for the key finders: the heard notes' memory, or the spectral one.
    private func keyChroma() -> [Float] { heard != nil ? heardAccum : chromaExtractor.accumulatedChroma }
    private func keyWeight() -> Double { heard != nil ? heardWeight : chromaExtractor.accumulatedWeight }
    /// Pitch classes this device is itself sounding in audio captured at the given time (bit n
    /// = pitch class n, C = 0), so an app that plays music while it listens can turn its own
    /// notes down in the pitch-class energy. Called on the analysis thread.
    public var ownSound: ((Int) -> Int)? = nil
    private var ownNow = 0

    private let singer = SingerModel()
    private let levelTracker = KeyLevelTracker()
    private var keyLevel: KeyLevelTracker.Level? = nil
    /// The key level, when the singers hold it steadily and the singer model's grid follows it.
    private var heldLevel: KeyLevelTracker.Level? = nil
    private var nowShiftShown = 0

    /// Sung notes for the key finders: moved back to the centre when the singers wander, and
    /// otherwise all on the singer's current grid. A note was segmented on the grid of its own
    /// time; once the grid has moved, an early note can sit a semitone from a late one of the
    /// same pitch, so each is re-read against the grid as it is now.
    private func keyNotes() -> [SungNote] {
        if let l = keyLevel { return l.notes }
        return segmenter.snapshot().map { n in
            n.gridCents == singer.offsetCents ? n
                : SungNote(pitch: n.concertPitch - singer.offsetCents / 100.0, startSeconds: n.startSeconds, durationSeconds: n.durationSeconds, gridCents: singer.offsetCents)
        }
    }
    /// The singer's spread for key decisions: the larger of the scatter around the wandering
    /// curve and the scatter around the plain semitone grid. The first can understate a very
    /// careless singer (a note-to-note change never exceeds half a semitone once wrapped); the
    /// second overstates a careful one who wanders. Decisions lean to caution.
    private func keySpreadCents() -> Double { max((keyLevel ?? heldLevel)?.spreadCents ?? 0.0, singer.spreadCents) }
    private func keySigma() -> Double { (keySpreadCents() / 100.0).coerceIn(0.1, 0.35) }
    private let segmenter = NoteSegmenter()
    private var keyFromMelody = false

    private let windowSize: Int
    private var window: [Float]
    private var pending: [Float]
    private var pendingCount = 0
    private var hopIndex = 0
    private var hopEndNanos = 0
    private let hopSeconds: Double

    private let chromaEveryHops = 4
    private let rhythmEveryHops: Int

    // Hysteresis so the display does not flicker between neighbours.
    private var keyCandidate: Key? = nil
    private var keyCandidateHits = 0
    private var shownKey: Key? = nil
    /// The singer model's relabelling already applied to the keys above.
    private var relabelApplied = 0
    private let keyHitsToSwitch = 12 // chroma frames, about half a second

    private var meterCandidate: String? = nil
    private var meterCandidateHits = 0
    private var shownMeter: MeterDetector.Result? = nil
    private let meterHitsToSwitch = 3

    private var tempoAbsentRuns = 0

    /// Absolute time of a tracked beat and the beat period, fixed when the estimate was made.
    private var beatAnchorNanos = 0
    private var beatPeriodNanos = 0.0

    // Song mode: the chord sequence since the last reset.
    private var segments: [ChordSegment] = []
    private var openChord: ChordLabel? = nil
    private var openStart: Float = 0
    private var openEnd: Float = 0
    private var currentChord: ChordLabel? = nil

    // Chord observations: chroma frames averaged per half beat (or per 4 frames without a beat).
    private var observationChroma = [Float](repeating: 0, count: 12)
    private var observationFrames = 0
    private var observationStart: Float = 0
    private var lastGridIndex = Int.min
    private var beatAnchorHop = 0.0
    private var beatPeriodHops = 0.0
    private var observationSpans: [(Float, Float)] = []
    private let framesPerObservationWithoutBeat = 4
    private let maxFramesPerObservation = 16
    private var songKey: Key? = nil
    private var songKeyConfidence: Float = 0
    private var songKeyCadences = 0
    private var songKeyAlternative: Key? = nil
    private var chordShares: [(ChordLabel, Float)] = []
    private var displayFromChords = false
    private var chordKeyLeadRuns = 0
    private var chordKeyTrailRuns = 0
    private let displaySwitchRuns = 4 // rhythm updates, about two seconds

    private var levelRms = 0.0
    private var levelSamples = 0
    private var recentChroma = [Float](repeating: 0, count: 12)
    private let recentChromaSeconds = 0.5

    public private(set) var result = AnalysisResult()

    private var pendingKey: Key? = nil
    private var pendingKeyConfidence: Float = 0
    private var pendingKeyScore: Float = 0
    private var shownKeySince = 0.0
    private let modeTracker = RelativeModeTracker()
    private var pairOk = false
    private var heldIdentity: HeldIdentity? = nil
    private struct HeldIdentity: Equatable { let pair: Key; let decided: Key? }
    private var pendingAlternatives: [(Key, Float)] = []
    private var tempo: TempoTracker.Estimate? = nil
    private var shownMeterIndex = 0
    /// Tracked periods to move the anchor back so it sits on the counted grid (6/8 in two).
    private var shownAnchorShift = 0.0

    /// - Parameters:
    ///   - pitch: builds the melody pitch estimator for a sample rate, for presets that follow
    ///     the melody. The app passes CREPE; without one the autocorrelation tracker is used.
    ///   - notes: builds the note listener for a sample rate, for presets that read chords. When
    ///     given, chords and key come from the notes it hears instead of the spectral chroma.
    ///   - beats: builds the Beat This! tracker for a sample rate and hop size, for presets that
    ///     read the beat. When given, the tempo and time signature come from it.
    public init(sampleRate: Int, preset: AnalysisPreset = .band, hopSize: Int = 512,
                pitch: ((Int) -> PitchEstimator)? = nil, notes: ((Int) -> HeardNotes)? = nil,
                beats: ((Int, Int) -> BeatTracker)? = nil) {
        self.sampleRate = sampleRate
        self.preset = preset
        self.hopSize = hopSize
        chromaExtractor = ChromaExtractor(sampleRate, memorySeconds: preset.keyMemorySeconds, minHz: preset.chromaMinHz,
                                          maxHz: preset.chromaMaxHz, rollOffHz: preset.chromaRollOffHz)
        onsetDetector = OnsetDetector(sampleRate, minHz: preset.onsetMinHz, maxHz: preset.onsetMaxHz, gamma: preset.onsetGamma,
                                      lowBandMaxHz: preset.onsetLowBandMaxHz, lowBandWeight: preset.onsetLowBandWeight)
        framesPerSecond = Double(sampleRate) / Double(hopSize)
        tempoTracker = TempoTracker(framesPerSecond, priorCenterBpm: preset.tempoPriorCenterBpm, priorWidthOctaves: preset.tempoPriorWidthOctaves)
        meterDetector = MeterDetector(minBeats: preset.meterMinBeats)
        pitchTracker = preset.melodicKey ? (pitch?(sampleRate) ?? PitchTracker(sampleRate)) : nil
        heard = !preset.melodicKey ? notes?(sampleRate) : nil
        beatThis = preset.readsBeat ? beats?(sampleRate, hopSize) : nil
        windowSize = chromaExtractor.windowSamples
        window = [Float](repeating: 0, count: windowSize)
        pending = [Float](repeating: 0, count: hopSize)
        hopSeconds = Double(hopSize) / Double(sampleRate)
        rhythmEveryHops = max(1, roundToInt(framesPerSecond / 2))
        if let h = heard { readBehindHops = behindHops(h) }
        segmenter.onNote = { [singer] note in singer.observe(note) }
    }

    public func reset() {
        chromaExtractor.reset()
        heard?.reset()
        beatThis?.reset()
        heardFrame = [Float](repeating: 0, count: 12)
        heardAccum = [Float](repeating: 0, count: 12)
        heardWeight = 0
        onsetDetector.reset()
        tempoTracker.reset()
        window = [Float](repeating: 0, count: windowSize)
        pendingCount = 0
        hopIndex = 0
        keyCandidate = nil
        relabelApplied = 0
        keyCandidateHits = 0
        shownKey = nil
        meterCandidate = nil
        meterCandidateHits = 0
        shownMeter = nil
        tempoAbsentRuns = 0
        tempo = nil
        pendingKey = nil
        pendingKeyConfidence = 0
        pendingKeyScore = 0
        shownKeySince = 0
        modeTracker.reset()
        heldIdentity = nil
        pairOk = false
        singer.reset()
        pendingAlternatives = []
        shownMeterIndex = 0
        shownAnchorShift = 0
        beatAnchorHop = 0
        beatAnchorNanos = 0
        beatPeriodNanos = 0
        hopEndNanos = 0
        openEnd = 0
        levelRms = 0
        levelSamples = 0
        recentChroma = [Float](repeating: 0, count: 12)
        segmenter.reset()
        levelTracker.reset()
        heldLevel = nil
        keyLevel = nil
        nowShiftShown = 0
        keyFromMelody = false
        chordTracker.reset()
        segments.removeAll()
        openChord = nil
        openStart = 0
        currentChord = nil
        observationChroma = [Float](repeating: 0, count: 12)
        observationFrames = 0
        observationStart = 0
        lastGridIndex = Int.min
        beatPeriodHops = 0
        observationSpans.removeAll()
        songKey = nil
        songKeyConfidence = 0
        songKeyCadences = 0
        songKeyAlternative = nil
        chordShares = []
        displayFromChords = false
        chordKeyLeadRuns = 0
        chordKeyTrailRuns = 0
        result = AnalysisResult()
    }

    /// Push `count` samples from `samples` (mono, -1..1). `nowNanos` is the clock time at which
    /// the last of these samples was captured. Returns true if `result` changed.
    @discardableResult
    public func process(_ samples: [Float], count: Int, nowNanos: Int) -> Bool {
        var changed = false
        var i = 0
        while i < count {
            let take = min(hopSize - pendingCount, count - i)
            for k in 0..<take { pending[pendingCount + k] = samples[i + k] }
            pendingCount += take
            i += take
            if pendingCount == hopSize {
                let remaining = count - i
                hopEndNanos = nowNanos - Int(Double(remaining) / Double(sampleRate) * 1e9)
                if runHop() { changed = true }
                pendingCount = 0
            }
        }
        return changed
    }

    private func runHop() -> Bool {
        window.withUnsafeMutableBufferPointer { w in
            let p = w.baseAddress!
            p.update(from: p + hopSize, count: windowSize - hopSize)
            for k in 0..<hopSize { p[windowSize - hopSize + k] = pending[k] }
        }
        hopIndex += 1

        var sq = 0.0
        for v in pending { sq += Double(v) * Double(v) }
        levelRms += sq
        levelSamples += hopSize

        let onset = onsetDetector.process(window, windowSize - onsetDetector.fftSize)
        tempoTracker.push(onset)
        heard?.push(pending, 0, hopSize)
        if let b = beatThis {
            b.pushOnset(onset)
            b.push(pending, 0, hopSize)
        }

        var changed = false
        if hopIndex % chromaEveryHops == 0 {
            ownNow = ownSound?(hopEndNanos) ?? 0
            // A sounding note's third overtone lands on its fifth: leave that out too.
            chromaExtractor.ownMask = (ownNow | ((ownNow << 7) | (ownNow >> 5))) & 0xFFF
            chromaExtractor.process(window, windowSize, hopSeconds * Double(chromaEveryHops))
            if let h = heard {
                // The model hears the music moved onto concert pitch by the same grid the chroma uses.
                h.tuningCents = chromaExtractor.gridOffsetCents
                // A slow phone makes the listener later; read further behind (it never shrinks).
                readBehindHops = max(readBehindHops, behindHops(h))
                // What was played one tick, read far enough behind that the model has heard it.
                let tick = hopSeconds * Double(chromaEveryHops)
                let to = Double(hopIndex - readBehindHops) * hopSeconds
                for i in 0..<12 { heardFrame[i] = 0 }
                _ = h.pitchClasses(fromSeconds: to - tick, toSeconds: to, out: &heardFrame)
                let decay = Float(exp(-tick / preset.keyMemorySeconds))
                var sum = 0.0
                for i in 0..<12 { heardAccum[i] = heardAccum[i] * decay + heardFrame[i]; sum += Double(heardFrame[i]) }
                heardWeight = heardWeight * Double(decay) + sum
            }
            do {
                let decay = Float(exp(-(hopSeconds * Double(chromaEveryHops)) / recentChromaSeconds))
                let frame = chromaExtractor.frameChroma
                for i in 0..<12 { recentChroma[i] = recentChroma[i] * decay + frame[i] }
            }
            updateMelody()
            updateKey()
            updateChords()
            changed = true
        }
        if hopIndex % rhythmEveryHops == 0 {
            updateRhythm()
            updateSongKey()
            changed = true
        }
        if changed { publish() }
        return changed
    }

    /// Tracks the sung pitch on the singer's own grid and segments it into notes.
    private func updateMelody() {
        guard let tracker = pitchTracker else { return }
        let now = Double(hopIndex) * hopSeconds
        // The device's own notes are not removed here: a singer in tune with the piano reads
        // exactly like it, and dropping those readings lost most of the melody when playing along.
        let started = DispatchTime.now().uptimeNanoseconds
        let voiced = tracker.process(window, end: windowSize)
        // A running average of the cost, shown in Details so it can be checked on a real phone.
        pitchMillis = 0.9 * pitchMillis + 0.1 * Double(DispatchTime.now().uptimeNanoseconds - started) / 1e6
        if !voiced {
            segmenter.push(.nan, now)
            return
        }
        // On the singer's own grid, which follows them as they drift.
        segmenter.push(tracker.midi - singer.offsetCents / 100.0, now, gridCents: singer.offsetCents)
    }

    private func updateKey() {
        // The singers drifted past half a semitone and the note labels moved one step: the keys
        // named from them move too, at once, so the name on screen and the tuning beside it
        // always refer to the same key.
        let relabel = singer.relabelSemitones - relabelApplied
        if relabel != 0 {
            relabelApplied = singer.relabelSemitones
            shownKey = shownKey?.transposed(relabel)
            keyCandidate = keyCandidate?.transposed(relabel)
            pendingKey = pendingKey?.transposed(relabel)
        }
        if pitchTracker != nil {
            let notes = segmenter.snapshot()
            // The wandering model only takes over once there is enough singing to trust it and the
            // singers have actually wandered: most of a semitone across most of the singing, or
            // that far from the centre right now. A short, scattered clip can otherwise be read as
            // sitting halfway between two keys, and moving every note by that half flips them.
            let level = levelTracker.analyse(notes)
            let enough = notes.count >= 16 && notes.reduce(0.0, { $0 + $1.durationSeconds }) >= 6.0
            keyLevel = level.flatMap { l in
                (enough && (l.highCents - l.lowCents >= 70.0 || abs(l.nowCents - l.centreCents) >= 70.0)) ? l : nil
            }
            // Singers who stay put but sit well off the grid: a scattered singer's notes, one at a
            // time, barely move the singer model (30 cents of scatter 39 cents sharp leaves it near
            // 0, and a third of the notes then read a semitone high). The key level reads the whole
            // tune against a scale and sees where they sit; the grid takes that.
            // Only for singing that holds one level (most of it within 40 cents, and there now):
            // a singer on the move is the singer model's to follow. And only for a singer scattering
            // 25 cents or less around it: beyond that the notes say little about where in the
            // semitone they sit, and a poor singer drifting flat was then shown a wrong key as
            // certain (SingerRobustnessTest; 30 and 35 cents both failed it).
            heldLevel = level.flatMap { l in
                (keyLevel == nil && enough && l.spreadCents <= 25.0 &&
                    l.highCents - l.lowCents <= 40.0 && abs(l.nowCents - l.centreCents) <= 30.0) ? l : nil
            }
            if let l = heldLevel {
                var away = (l.steadyCents - singer.offsetCents + 50.0).truncatingRemainder(dividingBy: 100.0)
                if away < 0 { away += 100.0 }
                away -= 50.0
                // Not to a level near the boundary between two keys: a level read just past 50 would
                // carry every note across it (a hymn sung 45 cents sharp was then shown D flat as
                // certain). The singer model's own grid crosses there only on its own evidence.
                // Nor to a level within 20 cents of concert pitch: the level reads in-tune scattered
                // singers up to about 20 cents off, and moving their grid that little only changed
                // which wrong mode or pair they were shown as certain.
                let steady = abs(l.steadyCents)
                if abs(away) >= 10.0 && steady >= 20.0 && steady <= 40.0 { singer.adopt(l.steadyCents) }
            }
            if keyLevel == nil { nowShiftShown = 0 }
            if let l = keyLevel {
                // Where the singers are now, relative to the centre's key, with a little hysteresis
                // so a level hovering halfway between two keys does not flicker.
                let rel = l.nowCents - 100.0 * Double(kotlinRound(l.centreCents / 100.0))
                if abs(rel - 100.0 * Double(nowShiftShown)) > 65.0 { nowShiftShown = kotlinRound(rel / 100.0) }
            }
        }
        updateKeyInner()
        // The finders settle the pair of keys (the seven notes); which of the pair is home is
        // decided separately, from where phrases rest, the leading tone, cadence approaches
        // and chords.
        if let found = pendingKey {
            let notes = pitchTracker != nil ? keyNotes() : []
            let now = Double(hopIndex) * hopSeconds
            let sigma = pitchTracker != nil ? keySigma() : 0.0
            // A lone voice's overtones read as chords (a sung E looks like an E major chord), so
            // with a tracked melody the chord evidence is left out: the notes speak for themselves.
            let chords = pitchTracker != nil ? [] : currentSegments()
            let evidence = RelativeKeyResolver.evidence(found, notes, keyChroma(), chords, heardSeconds: now, nowSeconds: now, sigma: sigma, profiles: preset.keyProfile)
            pendingKey = modeTracker.update(found, evidence.total, RelativeKeyResolver.THRESHOLD * evidenceNeeded())
            pairOk = pitchTracker == nil || pairIsClear(notes)
            // A scattered singer gives weaker evidence: the shown confidence says so.
            pendingKeyConfidence *= Float(1.0 / evidenceNeeded())
        }
        // The held time restarts when the pair or the decision changes, not when an undecided lean wobbles.
        let identity = pendingKey.map { HeldIdentity(pair: RelativeKeyResolver.pair($0).0, decided: modeTracker.decided) }
        if identity != heldIdentity { heldIdentity = identity; shownKeySince = Double(hopIndex) * hopSeconds }
    }

    private func updateKeyInner() {
        // A lone melody: once enough notes are in, the melodic finder decides and chroma only
        // breaks ties. Its evidence (phrase endings, the longest note) is what a listener uses.
        if pitchTracker != nil {
            let notes = keyNotes()
            let melodic = notes.count >= 8 ? MelodicKeyFinder.rank(notes, sigma: keySigma()) : []
            if melodic.count >= 2 {
                let best = melodic[0]
                let margin = ((best.score - melodic[1].score) * 4.0).coerceIn(0.0, 1.0)
                let confidence = Float(0.5 * best.score.coerceIn(0.0, 1.0) + 0.5 * margin)
                keyFromMelody = true
                // A challenger must lead the shown key clearly, and keep leading for about
                // a second and a half, before the display moves: near-ties flip every note.
                let shownScore = shownKey.flatMap { k in melodic.first { $0.key == k }?.score } ?? -Double.infinity
                let leadsClearly = best.score - shownScore > 0.04
                if best.key == keyCandidate && leadsClearly { keyCandidateHits += 1 } else { keyCandidate = best.key; keyCandidateHits = leadsClearly ? 1 : 0 }
                if shownKey == nil || keyCandidateHits >= keyHitsToSwitch * 3 { shownKey = best.key }
                let shown = shownKey ?? best.key
                pendingKey = shown
                pendingKeyConfidence = shown == best.key ? confidence : confidence * 0.5
                pendingKeyScore = Float(melodic.first { $0.key == shown }?.score ?? 0.0).coerceIn(0, 1)
                pendingAlternatives = melodic.filter { $0.key != shown }.prefix(3).map { ($0.key, Float($0.score).coerceIn(0, 1)) }
                return
            }
        }
        let ranked = KeyDetector.rank(keyChroma(), preset.keyProfile)
        let confidence = Float(KeyDetector.confidence(ranked))
        let best = ranked[0].key
        // Nothing is shown from the first moments: a single slide or breath is not a key.
        let enoughSound = keyWeight() > 1e-4 && Double(hopIndex - readBehindHops) * hopSeconds >= 1.0
        if !enoughSound || confidence < 0.15 {
            keyCandidateHits = 0
            return
        }
        let shownCorrelation = shownKey.flatMap { k in ranked.first { $0.key == k }?.correlation } ?? -Double.infinity
        let leadsClearly = ranked[0].correlation - shownCorrelation > 0.05
        if best == keyCandidate && leadsClearly { keyCandidateHits += 1 } else { keyCandidate = best; keyCandidateHits = leadsClearly ? 1 : 0 }
        if shownKey == nil || keyCandidateHits >= keyHitsToSwitch { shownKey = best }
        guard let shown = shownKey else { return }
        let shownRank = ranked.first { $0.key == shown }!
        pendingKey = shown
        pendingKeyConfidence = shown == best ? confidence : Float(shownRank.correlation).coerceIn(0, 1) * 0.5
        pendingKeyScore = Float(shownRank.correlation).coerceIn(0, 1)
        pendingAlternatives = ranked.filter { $0.key != shown }.prefix(3).map { ($0.key, Float($0.correlation).coerceIn(0, 1)) }
    }

    /// Adds the latest chroma frame to the current observation and, at each half-beat boundary
    /// (or every few frames when no beat is known), hands the averaged observation to the chord
    /// tracker. Decisions arrive a few observations late and are turned into timed segments.
    private func updateChords() {
        // With heard notes, the chords are read behind the audio and dated to when they were played.
        let chordHop = hopIndex - readBehindHops
        let now = Float(Double(chordHop) * hopSeconds)
        let frame = heard != nil ? heardFrame : chromaExtractor.frameChroma
        if observationFrames == 0 || now < observationStart { observationStart = now - Float(hopSeconds * Double(chromaEveryHops)) }
        for i in 0..<12 { observationChroma[i] += frame[i] }
        observationFrames += 1

        let boundary: Bool
        if beatPeriodHops > 0.0 {
            // Half beats, or quarter beats when a half beat would exceed 0.35 s, so decisions
            // (made four observations late) never lag by more than about a second.
            let divisions = beatPeriodHops * hopSeconds / 2.0 > 0.35 ? 4 : 2
            let gridIndex = floorDiv(Int(Double(chordHop) - beatAnchorHop) * divisions, max(1, Int(beatPeriodHops)))
            let crossed = lastGridIndex != Int.min && gridIndex != lastGridIndex
            lastGridIndex = gridIndex
            boundary = crossed
        } else {
            boundary = observationFrames >= framesPerObservationWithoutBeat
        }
        if !boundary && observationFrames < maxFramesPerObservation { return }

        for i in 0..<12 { observationChroma[i] /= Float(observationFrames) }
        observationSpans.append((observationStart, now))
        while observationSpans.count > chordTracker.lag + 1 { observationSpans.removeFirst() }
        if chordTracker.observe(observationChroma, Double(now - observationStart)) {
            // The decision refers to the observation `lag - 1` spans back from the newest.
            let idx = observationSpans.count - chordTracker.lag
            if idx >= 0 {
                let (start, end) = observationSpans[idx]
                applyDecision(chordTracker.decided, start, end)
            }
        }
        for i in 0..<12 { observationChroma[i] = 0 }
        observationFrames = 0
    }

    private func applyDecision(_ chord: ChordLabel?, _ start: Float, _ end: Float) {
        currentChord = chord
        if chord != openChord {
            closeSegment(start)
            openChord = chord
            openStart = start
        }
        openEnd = end
    }

    private func closeSegment(_ now: Float) {
        let end = min(now, openEnd).coerceAtLeast(openStart)
        if let chord = openChord, end > openStart {
            if let last = segments.last, last.chord == chord, openStart - last.endSeconds < 0.05 {
                segments[segments.count - 1] = ChordSegment(chord, last.startSeconds, end)
            } else {
                segments.append(ChordSegment(chord, openStart, end))
            }
        }
    }

    /// The chord sequence including the chord still sounding.
    private func currentSegments() -> [ChordSegment] {
        guard let chord = openChord, openEnd > openStart else { return segments }
        return segments + [ChordSegment(chord, openStart, openEnd)]
    }

    private func updateSongKey() {
        let all = currentSegments()
        let ranked = ChordKeyEstimator.suggest(all)
        if ranked.isEmpty { return }
        let total = all.reduce(0.0) { $0 + Double($1.duration) }
        // A few seconds of chords are needed before a whole-song suggestion means anything.
        if total < 4.0 { return }
        songKey = ranked[0].key
        songKeyCadences = ranked[0].cadences
        songKeyConfidence = Float(ChordKeyEstimator.confidence(ranked))
        songKeyAlternative = ranked[1].key
        // Promote the chord-based key only after it has been confident for a couple of seconds;
        // demote it only after it has been weak for as long. The tracker follows the shown key.
        if songKeyConfidence >= 0.4 { chordKeyLeadRuns += 1; chordKeyTrailRuns = 0 } else if songKeyConfidence < 0.25 { chordKeyTrailRuns += 1; chordKeyLeadRuns = 0 }
        if !displayFromChords && chordKeyLeadRuns >= displaySwitchRuns { displayFromChords = true }
        if displayFromChords && chordKeyTrailRuns >= displaySwitchRuns { displayFromChords = false }
        chordTracker.setKey(displayFromChords ? songKey : pendingKey)
        var shares: [ChordLabel: Double] = [:]
        var order: [ChordLabel] = []
        for s in all { guard let c = s.chord else { continue }; if shares[c] == nil { order.append(c) }; shares[c, default: 0] += Double(s.duration) }
        // Stable like Kotlin's sortedByDescending: ties keep first-heard order.
        chordShares = order.enumerated().sorted { a, b in
            let x = shares[a.element]!, y = shares[b.element]!
            return x != y ? x > y : a.offset < b.offset
        }.map { ($0.element, Float(shares[$0.element]! / total)) }
    }

    /// How much more evidence than usual a decision needs, from the singer's spread: 1 for a
    /// singer within about 20 cents, rising to 2 for one scattering 40 cents or more.
    private func evidenceNeeded() -> Double {
        if pitchTracker == nil || singer.notesSeen < 4 { return 1.0 }
        return (1.0 + (keySpreadCents() - 20.0) / 20.0).coerceIn(1.0, 2.0)
    }

    /// Whether the sung notes clearly point to one pair of keys. Two tests, both scaled to the
    /// singer: the leading pair must beat the next pair by a margin that grows with the
    /// singer's scatter, and the latest notes must agree with the ones before them. A drift
    /// the app failed to follow, or a singer too scattered to read, fails one or the other.
    private func pairIsClear(_ notes: [SungNote]) -> Bool {
        if notes.count < 16 { return false }
        let sigma = keySigma()
        let ranked = MelodicKeyFinder.rank(notes, sigma: sigma)
        if ranked.isEmpty { return false }
        let bestPair = RelativeKeyResolver.pair(ranked[0].key).0
        guard let other = ranked.first(where: { RelativeKeyResolver.pair($0.key).0 != bestPair }) else { return false }
        let need = 0.05 + 0.006 * (keySpreadCents() - 10.0).coerceAtLeast(0.0)
        if ranked[0].score - other.score < need { return false }
        let n = min(16, notes.count / 2)
        guard let recent = MelodicKeyFinder.rank(Array(notes[(notes.count - n)...]), sigma: sigma).first?.key,
              let before = MelodicKeyFinder.rank(Array(notes[(notes.count - 2 * n)..<(notes.count - n)]), sigma: sigma).first?.key
        else { return false }
        return RelativeKeyResolver.pair(recent).0 == bestPair && RelativeKeyResolver.pair(before).0 == bestPair
    }

    private func samePair(_ a: Key?, _ b: Key?) -> Bool {
        guard let a, let b else { return false }
        return RelativeKeyResolver.pair(a).0 == RelativeKeyResolver.pair(b).0
    }

    private func updateRhythm() {
        guard let estimate = tempoTracker.estimate(), estimate.strength >= 0.12 else {
            // Keep the last tempo briefly through a quiet bar, then let go.
            tempoAbsentRuns += 1
            if tempoAbsentRuns > 6 {
                tempo = nil; shownMeter = nil; meterCandidate = nil; beatPeriodHops = 0; lastGridIndex = Int.min
                tempoTracker.releaseLock()
            }
            return
        }
        tempoAbsentRuns = 0
        tempo = estimate
        let previousAnchor = countedAnchorNanos()
        let previousCountedPeriod = beatPeriodNanos * (shownMeter?.pulseRatio ?? 1.0)
        beatAnchorNanos = hopEndNanos - Int(estimate.lastBeatFramesAgo * hopSeconds * 1e9)
        beatPeriodNanos = estimate.periodFrames * hopSeconds * 1e9
        // Carry the bar position forward to the new anchor, so it stays right even if the
        // meter analysis below has nothing new to say.
        if let m0 = shownMeter, previousCountedPeriod > 0.0 {
            let advanced = kotlinRound(Double(countedAnchorNanos() - previousAnchor) / previousCountedPeriod)
            shownMeterIndex = floorMod(shownMeterIndex + advanced, m0.beatsPerBar)
        }
        if estimate.locked {
            beatAnchorHop = Double(hopIndex) - estimate.lastBeatFramesAgo
            beatPeriodHops = estimate.periodFrames
        }

        guard let meter = meterDetector.analyze(tempoTracker.detrended, tempoTracker.detrendedLength,
                                                estimate.periodFrames, estimate.lastBeatFramesAgo) else { return }
        if meter.label == meterCandidate { meterCandidateHits += 1 } else { meterCandidate = meter.label; meterCandidateHits = 1 }
        var shownIsNew = false
        if let old = shownMeter, meterCandidateHits < meterHitsToSwitch {
            // Keep the displayed bar length, but take the downbeat position for that bar length
            // from the fresh analysis so the beat count stays in step with the new beat phase.
            if let p = meter.positions[old.grid] { shownMeterIndex = p.index; shownAnchorShift = p.anchorShift }
        } else {
            shownMeter = meter
            shownIsNew = true
        }
        if shownIsNew { shownMeterIndex = meter.position.index; shownAnchorShift = meter.position.anchorShift }
    }

    /// The beat anchor moved onto the counted grid.
    private func countedAnchorNanos() -> Int { beatAnchorNanos - Int(shownAnchorShift * beatPeriodNanos) }

    private func publish() {
        let rms = levelSamples > 0 ? (levelRms / Double(levelSamples)).squareRoot() : 0.0
        levelRms = 0
        levelSamples = 0
        let level = rms > 0 ? Float(((20.0 * log10(rms) + 60.0) / 60.0).coerceIn(0.0, 1.0)) : 0

        let chroma = keyChroma()
        let maxChroma = (chroma.max() ?? 0).coerceAtLeast(1e-9)
        let t = tempo
        let m = shownMeter
        // The counted pulse may be a subdivision (7/8 in eighths) or a grouping (6/8 in dotted
        // quarters) of the tracked beat.
        let ratio = m?.pulseRatio ?? 1.0
        let countedPeriodNanos = beatPeriodNanos * ratio
        // The beat anchor is fixed when the tempo was estimated; step forward from it in whole
        // beats so the published beat is always the latest one at or before now, and the bar
        // position advances with it.
        var lastBeatAt = 0
        var beatsSinceAnchor = 0
        let anchor = countedAnchorNanos()
        if t != nil && countedPeriodNanos > 0.0 {
            beatsSinceAnchor = floorDiv(hopEndNanos - anchor, max(1, Int(countedPeriodNanos)))
            lastBeatAt = anchor + Int(Double(beatsSinceAnchor) * countedPeriodNanos)
        }
        let beatIndex = m.map { floorMod(shownMeterIndex + beatsSinceAnchor, $0.beatsPerBar) } ?? 0
        // Chords outrank the notes only when they point at a different pair of keys, and never
        // over a tracked melody, where the "chords" may be one voice's overtones.
        let chordsOverride = displayFromChords && pitchTracker == nil && !samePair(songKey, pendingKey)
        let shownDisplay = chordsOverride ? songKey : pendingKey
        var r = AnalysisResult()
        r.secondsHeard = Float(Double(hopIndex) * hopSeconds)
        r.level = level
        r.chroma = chroma.map { $0 / maxChroma }
        r.recentChroma = recentChroma
        r.key = pendingKey
        r.keyConfidence = pendingKeyConfidence
        r.keyHeldSeconds = pendingKey != nil ? Float(Double(hopIndex) * hopSeconds - shownKeySince) : 0
        r.keyScore = pendingKeyScore
        r.relativeKey = shownDisplay?.relative
        r.pairDecided = chordsOverride || pairOk
        r.modeDecided = chordsOverride || (pairOk && modeTracker.decided != nil)
        r.modeScore = Float(modeTracker.score)
        r.keyAlternatives = pendingAlternatives
        if let l = keyLevel {
            // How far the singers sit from the key shown as "now".
            r.tuningCents = Float(l.nowCents - 100.0 * Double(kotlinRound(l.centreCents / 100.0) + nowShiftShown))
        } else if pitchTracker != nil && singer.notesSeen >= 4 {
            r.tuningCents = Float(singer.currentCents)
        } else {
            r.tuningCents = Float(chromaExtractor.tuningOffsetCents())
        }
        r.singerSpreadCents = pitchTracker != nil && singer.notesSeen >= 4 ? Float(keySpreadCents()) : 0
        let level2 = chordsOverride ? nil : keyLevel
        r.keyNow = shownDisplay.map { k in level2 != nil ? k.transposed(nowShiftShown) : k }
        r.keyRangeLow = shownDisplay.map { k in level2.map { k.transposed($0.lowShift) } ?? k }
        r.keyRangeHigh = shownDisplay.map { k in level2.map { k.transposed($0.highShift) } ?? k }
        r.keyMove = level2?.move ?? .steady
        r.tuningConfidence = Float(chromaExtractor.tuningConfidence())
        r.sungNotes = pitchTracker != nil ? segmenter.snapshot() : []
        r.keyFromMelody = keyFromMelody
        r.bpm = t.map { Float($0.bpm / ratio) }
        r.tempoStrength = Float(t?.strength ?? 0)
        r.tempoLocked = t?.locked ?? false
        r.beatPeriodMillis = t != nil ? Float(countedPeriodNanos / 1e6) : 0
        r.lastBeatAtNanos = lastBeatAt
        r.meterLabel = m?.label
        r.beatsPerBar = m?.beatsPerBar ?? 0
        r.meterCompound = m?.compound ?? false
        r.meterConfidence = Float(m?.confidence ?? 0)
        r.lastBeatIndexInBar = beatIndex
        r.meterScores = m?.scores.mapValues { Float($0) } ?? [:]
        r.displayKey = shownDisplay
        r.displayKeyConfidence = chordsOverride ? songKeyConfidence : pendingKeyConfidence
        r.displayKeyFromChords = chordsOverride
        r.currentChord = currentChord
        r.provisionalChord = chordTracker.provisional
        r.chordSegments = currentSegments()
        r.chordShares = chordShares
        r.songKey = songKey
        r.songKeyConfidence = songKeyConfidence
        r.songKeyCadences = songKeyCadences
        r.songKeyAlternative = songKeyAlternative
        r.pitchModel = pitchTracker == nil ? nil : (pitchTracker is CrepePitch ? "CREPE-tiny" : "autocorrelation")
        r.pitchMillis = Float(pitchMillis)
        r.notesModel = heard != nil ? "Basic Pitch" : nil
        r.notesMillisPerSecond = Float(heard?.millisPerSecond ?? 0)
        if let b = beatThis, !b.failed { publishBeat(b, &r) }
        result = r
    }

    /// The tempo and time signature from Beat This!'s newest reading, in place of the standard
    /// tracker's. The reading dates its newest beat in seconds of audio; the published beat is
    /// the latest one at or before now, stepped on from there by whole periods.
    private func publishBeat(_ b: BeatTracker, _ r: inout AnalysisResult) {
        r.beatModel = b.name
        r.beatMillisPerSecond = Float(b.millisPerSecond)
        let reading = b.latest
        r.bpm = reading?.bpm.map { Float($0) }
        r.tempoStrength = Float(reading?.strength ?? 0)
        r.tempoLocked = reading?.locked ?? false
        r.beatPeriodMillis = 0
        r.lastBeatAtNanos = 0
        r.meterLabel = nil
        r.beatsPerBar = 0
        r.meterCompound = false
        r.meterConfidence = 0
        r.lastBeatIndexInBar = 0
        r.meterScores = [:]
        guard let reading, reading.bpm != nil, let last = reading.lastBeatSeconds, let period = reading.periodSeconds, period > 0 else { return }
        let now = Double(hopIndex) * hopSeconds
        let beatsSince = max(0, Int(((now - last) / period).rounded(.down)))
        r.beatPeriodMillis = Float(period * 1000)
        r.lastBeatAtNanos = hopEndNanos - Int((now - last - Double(beatsSince) * period) * 1e9)
        guard let bpb = reading.beatsPerBar else { return }
        r.meterLabel = reading.meterLabel
        r.beatsPerBar = bpb
        r.meterCompound = reading.compound
        r.meterConfidence = Float(reading.meterConfidence)
        r.lastBeatIndexInBar = floorMod(reading.lastBeatIndexInBar + beatsSince, bpb)
        r.meterScores = reading.barScores.mapValues { Float($0) }
    }
}
