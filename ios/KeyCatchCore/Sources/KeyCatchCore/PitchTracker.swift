import Foundation

/// A monophonic pitch tracker for a single voice: normalised autocorrelation over the vocal
/// range with parabolic refinement. Returns the fundamental and a 0..1 clarity, or clarity 0
/// when the frame is unvoiced, too quiet, or polyphonic enough to have no clear period.
public final class PitchTracker: PitchEstimator {
    private let sampleRate: Int
    public let frameSize: Int
    private let minLevel: Double
    private let minClarity: Double
    private let lagMin: Int
    private let lagMax: Int
    private let window: [Float]
    private var x: [Float]
    private var acf: [Double]

    public private(set) var hz: Double = 0.0
    public private(set) var clarity: Double = 0.0
    /// Fractional MIDI note of [hz] against A4 = 440 Hz.
    public var midi: Double { 69.0 + 12.0 * log2(hz / 440.0) }
    public var samplesNeeded: Int { frameSize }

    public init(
        _ sampleRate: Int,
        frameSize: Int = 2048,
        minHz: Double = 70.0,
        maxHz: Double = 1000.0,
        minLevel: Double = 0.004,
        minClarity: Double = 0.55
    ) {
        self.sampleRate = sampleRate
        self.frameSize = frameSize
        self.minLevel = minLevel
        self.minClarity = minClarity
        lagMin = Int(Double(sampleRate) / maxHz).coerceAtLeast(2)
        lagMax = Int(Double(sampleRate) / minHz).coerceAtMost(frameSize / 2)
        window = Fft.hannWindow(frameSize)
        x = [Float](repeating: 0, count: frameSize)
        // Kotlin allocates this per frame; it is preallocated here, and every lag that is read is
        // written first in each call, so the results are the same.
        acf = [Double](repeating: 0, count: max(lagMax + 1, 0))
    }

    /// Analyses the [frameSize] samples ending at [end] (exclusive) in [buffer]. Returns true if voiced.
    public func process(_ buffer: [Float], end: Int) -> Bool {
        let start = end - frameSize
        let frameSize = self.frameSize
        var energy = 0.0
        x.withUnsafeMutableBufferPointer { xp in
            buffer.withUnsafeBufferPointer { bp in
                window.withUnsafeBufferPointer { wp in
                    for i in 0..<frameSize { xp[i] = bp[start + i] * wp[i]; energy += Double(xp[i]) * Double(xp[i]) }
                }
            }
        }
        let level = (energy / Double(frameSize)).squareRoot()
        clarity = 0.0
        if level < minLevel { return false }
        // Normalised autocorrelation, then the first strong peak above lagMin: the fundamental,
        // not an octave below it. A peak is accepted when within 0.85 of the global maximum.
        let lagMin = self.lagMin, lagMax = self.lagMax
        var best = -1; var bestVal = 0.0
        x.withUnsafeBufferPointer { xp in
            acf.withUnsafeMutableBufferPointer { ap in
                for i in 0..<ap.count { ap[i] = 0.0 }
                var lag = lagMin
                while lag <= lagMax {
                    var s = 0.0; var n0 = 0.0; var n1 = 0.0
                    for i in 0..<(frameSize - lag) { let a = Double(xp[i]); let b = Double(xp[i + lag]); s += a * b; n0 += a * a; n1 += b * b }
                    ap[lag] = (n0 > 0 && n1 > 0) ? s / (n0 * n1).squareRoot() : 0.0
                    if ap[lag] > bestVal { bestVal = ap[lag]; best = lag }
                    lag += 1
                }
            }
        }
        if best < 0 || bestVal < minClarity { return false }
        var chosen = best
        // Interior peaks only: acf[lagMin - 1] is never filled, so the shortest lag would always
        // look like a peak, and a low note (110 Hz and below) read as 1 kHz.
        var lag = lagMin + 1
        while lag < best {
            if acf[lag] >= 0.85 * bestVal && acf[lag] >= acf[lag - 1] && acf[lag] >= acf[lag + 1] { chosen = lag; break }
            lag += 1
        }
        let a = acf[(chosen - 1).coerceAtLeast(lagMin)]; let b = acf[chosen]; let c = acf[(chosen + 1).coerceAtMost(lagMax)]
        let denom = a - 2 * b + c
        let delta = denom != 0.0 ? (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) : 0.0
        hz = Double(sampleRate) / (Double(chosen) + delta)
        clarity = acf[chosen]
        return true
    }
}

/// A sung note. [pitch] is where the note sat on the grid the app was using for this singer
/// at the time, fractional (60.3 is 30 cents above middle C); [gridCents] is that grid's offset
/// from concert pitch, so [concertPitch] is what was actually sung. [midi] is the nearest
/// semitone on the grid, derived from [pitch] so the two can never disagree.
public struct SungNote: Hashable, Sendable {
    public var pitch: Double
    public var startSeconds: Double
    public var durationSeconds: Double
    public var gridCents: Double

    public init(pitch: Double, startSeconds: Double, durationSeconds: Double, gridCents: Double = 0.0) {
        self.pitch = pitch
        self.startSeconds = startSeconds
        self.durationSeconds = durationSeconds
        self.gridCents = gridCents
    }

    /// A note sung exactly on a semitone at concert pitch.
    public init(midi: Int, startSeconds: Double, durationSeconds: Double) {
        self.init(pitch: Double(midi), startSeconds: startSeconds, durationSeconds: durationSeconds, gridCents: 0.0)
    }

    public var midi: Int { kotlinRound(pitch) }

    /// The pitch as sung, against A = 440 Hz.
    public var concertPitch: Double { pitch + gridCents / 100.0 }
}

/// How a pitch splits between the semitones either side of it, given the singer's spread
/// [sigma] in semitones: a note 45 cents above C is mostly C and partly C#, not a full vote
/// for either. With [sigma] near zero this is plain rounding. Returns (pitch class, share) pairs.
public func softShares(_ pitch: Double, _ sigma: Double) -> [(Int, Double)] {
    if sigma < 0.05 { return [(floorMod(kotlinRound(pitch), 12), 1.0)] }
    let lo = Int(pitch.rounded(.down))
    let ws = ((lo - 1)...(lo + 2)).map { k -> (Int, Double) in (k, exp(-(pitch - Double(k)) * (pitch - Double(k)) / (2 * sigma * sigma))) }
    var total = 0.0
    for w in ws { total += w.1 }
    return ws.filter { $0.1 / total > 0.01 }.map { (floorMod($0.0, 12), $0.1 / total) }
}

/// Turns a stream of pitch readings into notes. A note continues while the pitch stays within
/// [holdSemitones] of the note's running centre, so vibrato and a note sung between two keys
/// do not break it up. Each reading is weighted by how still the pitch is over about one
/// vibrato cycle: held pitch counts, a slide or scoop counts for little. A run that is mostly
/// slide is dropped.
public final class NoteSegmenter {
    private let minNoteSeconds: Double
    private let gapSeconds: Double
    private let holdSemitones: Double
    private let steadyWindowSeconds: Double
    private let minSteadyShare: Double

    public private(set) var notes: [SungNote] = []
    /// Called with each note as it closes, for the singer model.
    public var onNote: ((SungNote) -> Void)?

    private var runStart = -1.0
    private var runEnd = -1.0
    private var runWeightedSum = 0.0
    private var runWeight = 0.0
    private var runFrames = 0
    private var runSteadyFrames = 0
    private var runPlainSum = 0.0
    private var runGridSum = 0.0
    /// (time, midi) readings over the last vibrato cycle; short, so a plain array serves as the deque.
    private var history: [(Double, Double)] = []

    public init(
        minNoteSeconds: Double = 0.12,
        gapSeconds: Double = 0.08,
        holdSemitones: Double = 0.6,
        steadyWindowSeconds: Double = 0.2,
        minSteadyShare: Double = 0.25
    ) {
        self.minNoteSeconds = minNoteSeconds
        self.gapSeconds = gapSeconds
        self.holdSemitones = holdSemitones
        self.steadyWindowSeconds = steadyWindowSeconds
        self.minSteadyShare = minSteadyShare
    }

    public func reset() { notes.removeAll(); runStart = -1.0; history.removeAll() }

    private func runCentre() -> Double { runWeight > 0.2 ? runWeightedSum / runWeight : runPlainSum / Double(runFrames) }

    /// [midi] is fractional, already on the singer's grid, which sits [gridCents] from concert
    /// pitch; pass NaN for an unvoiced frame.
    public func push(_ midi: Double, _ timeSeconds: Double, gridCents: Double = 0.0) {
        if midi.isNaN {
            history.removeAll()
            if runStart >= 0 && timeSeconds - runEnd > gapSeconds { close() }
            return
        }
        // Steadiness: how far the pitch has moved over the last vibrato cycle. Vibrato returns
        // to where it was; a slide does not.
        history.append((timeSeconds, midi))
        while history.count > 1 && timeSeconds - history[0].0 > steadyWindowSeconds + 1e-6 { history.removeFirst() }
        let moved = (history.count > 1 && timeSeconds - history[0].0 >= steadyWindowSeconds * 0.75) ? abs(midi - history[0].1) : 0.0
        let steadiness = 1.0 / (1.0 + (moved / 0.3) * (moved / 0.3))

        if runStart >= 0 {
            let away = abs(midi - runCentre())
            if away > holdSemitones || timeSeconds - runEnd > gapSeconds { close() }
        }
        if runStart < 0 {
            runStart = timeSeconds; runWeightedSum = 0.0; runWeight = 0.0; runFrames = 0; runSteadyFrames = 0; runPlainSum = 0.0; runGridSum = 0.0
        }
        runEnd = timeSeconds
        runWeightedSum += midi * steadiness; runWeight += steadiness
        runPlainSum += midi; runGridSum += gridCents; runFrames += 1
        if steadiness > 0.5 { runSteadyFrames += 1 }
    }

    private func openNote() -> SungNote? {
        if runStart < 0 || runEnd - runStart < minNoteSeconds { return nil }
        if Double(runSteadyFrames) < minSteadyShare * Double(runFrames) { return nil }
        let centre = runCentre()
        return SungNote(pitch: centre, startSeconds: runStart, durationSeconds: runEnd - runStart, gridCents: runGridSum / Double(runFrames))
    }

    public func close() {
        if let it = openNote() { notes.append(it); onNote?(it) }
        runStart = -1.0
    }

    /// The closed notes plus the note still being sung, if it is already long enough to count.
    public func snapshot() -> [SungNote] {
        guard let open = openNote() else { return notes }
        return notes + [open]
    }
}

/// What the app learns about the singer, one note at a time.
///
/// Offset: where the singer's "in tune" sits, in cents from A = 440 Hz. Each note's distance
/// from its nearest semitone wraps around every 100 cents, like an angle, so the offset is a
/// circular average: notes consistently 45 cents sharp all point the same way and move the
/// grid to meet them; notes scattered at random point every way and average to nothing.
/// Older notes fade over [memorySeconds], so a slow drift flat or sharp is followed. How
/// strongly the grid moves depends on how consistent the notes are (the mean resultant
/// length, 0 to 1), counting only what exceeds the consistency random notes show by chance.
///
/// Spread: how far notes scatter around the grid once the offset is removed, in cents. A few
/// cents for a good singer, 25 or more for a poor one. It sets how softly notes are assigned
/// and how much evidence a decision needs.
public final class SingerModel {
    private let memorySeconds: Double

    public private(set) var offsetCents = 0.0
    /// Where the singer sits now, in cents from A = 440 Hz: the grid plus the full circular mean
    /// of the recent notes' leftover lean. The grid itself moves cautiously, by a share of that
    /// lean that depends on how consistent the notes are; the display must not be damped the
    /// same way, or a scattered singer 40 cents sharp would read as 15. The grid stays within
    /// half a semitone of concert pitch, so the key is named after the nearest concert key and
    /// this number says how far from it the singers sit; it may pass 50 while they drift.
    public private(set) var currentCents = 0.0
    /// The leftover lean: the notes' circular mean distance from the grid, within half a semitone.
    private var leanCents = 0.0
    private var sumCos = 0.0
    private var sumSin = 0.0
    private var sumWeight = 0.0
    private var sumWeightSq = 0.0
    private var lastUpdate = Double.nan
    private var residuals: [(Double, Double)] = [] // |cents from grid|, weight
    public private(set) var notesSeen = 0
    /// Semitones the note labels have moved since the start: +1 each time the grid steps down a
    /// semitone to stay within half a semitone of concert pitch (every note then reads one higher),
    /// -1 for a step up. Whatever names keys from those labels must move with it.
    public private(set) var relabelSemitones = 0

    public init(memorySeconds: Double = 8.0) { self.memorySeconds = memorySeconds }

    /// Spread in cents: the weighted median distance from the grid, scaled to match a standard
    /// deviation (x1.48). The median ignores the odd glitch that would inflate a mean. A middling
    /// 20 until a few notes are in.
    public var spreadCents: Double {
        if residuals.count < 4 { return 20.0 }
        // Stable sort by distance, as Kotlin's sortedBy.
        let sorted = residuals.enumerated().sorted { $0.element.0 != $1.element.0 ? $0.element.0 < $1.element.0 : $0.offset < $1.offset }.map { $0.element }
        var weightSum = 0.0
        for s in sorted { weightSum += s.1 }
        let half = weightSum / 2
        var acc = 0.0
        for (r, w) in sorted { acc += w; if acc >= half { return 1.4826 * r } }
        return 1.4826 * sorted[sorted.count - 1].0
    }

    /// The soft-assignment width in semitones for this singer.
    public var sigmaSemitones: Double { (spreadCents / 100.0).coerceIn(0.1, 0.35) }

    /// [note] was segmented on the grid shifted by the offset at the time; its leftover distance
    /// from the nearest semitone is what the offset (and the spread) learn from.
    public func observe(_ note: SungNote) {
        let residual = (note.pitch - Double(kotlinRound(note.pitch))) * 100.0
        let t = note.startSeconds + note.durationSeconds
        if !lastUpdate.isNaN {
            let fade = exp(-(t - lastUpdate).coerceAtLeast(0.0) / memorySeconds)
            sumCos *= fade; sumSin *= fade; sumWeight *= fade; sumWeightSq *= fade * fade
        }
        lastUpdate = t
        let w = note.durationSeconds.coerceIn(0.1, 1.5)
        let angle = 2 * Double.pi * residual / 100.0
        sumCos += w * cos(angle); sumSin += w * sin(angle); sumWeight += w; sumWeightSq += w * w
        residuals.append((abs(residual), note.durationSeconds.coerceAtMost(1.5)))
        notesSeen += 1
        let resultant = (sumCos * sumCos + sumSin * sumSin).squareRoot() / sumWeight
        let meanCents = atan2(sumSin, sumCos) * 100.0 / (2 * Double.pi)
        // Even random notes line up a little by chance, about 1/sqrt(n) for n notes; only the
        // consistency beyond that counts, so a scattered singer does not walk the grid away.
        let chance = sumWeight > 0 ? sumWeightSq.squareRoot() / sumWeight : 1.0
        let excess = chance < 1.0 ? ((resultant - chance) / (1 - chance)).coerceIn(0.0, 1.0) : 0.0
        // The shown value is the whole lean, grid plus leftover. The leftover is read within half
        // a semitone of the grid, the same rounding that names each note, so the number always
        // describes the notes as labelled. Holding a reading past 50 so it would not flip sign
        // was tried: a singer sitting near 50 then read a semitone off the notes' own names.
        leanCents = meanCents
        currentCents = offsetCents + leanCents
        // A few notes' worth of evidence before the grid moves at all.
        if sumWeight < 1.0 { return }
        // Consistent notes move the grid most of the way; scattered ones barely at all.
        let step = 0.8 * excess * excess * meanCents
        offsetCents += step
        leanCents -= step
        // The remembered notes are now that much nearer the grid: turn their average to match.
        let turn = -2 * Double.pi * step / 100.0
        let c = cos(turn); let s = sin(turn)
        let nc = sumCos * c - sumSin * s
        sumSin = sumCos * s + sumSin * c
        sumCos = nc
        // Singers more than half a semitone from concert pitch are nearer the next key: name
        // that one. Notes are read against the current grid (see MusicAnalyzer.keyNotes), so
        // every note changes label together, and the shown lean moves a semitone with them.
        if offsetCents > 50.0 { offsetCents -= 100.0; relabelSemitones += 1 }
        else if offsetCents <= -50.0 { offsetCents += 100.0; relabelSemitones -= 1 }
        currentCents = offsetCents + leanCents
    }

    public func reset() {
        offsetCents = 0.0; currentCents = 0.0; leanCents = 0.0; relabelSemitones = 0; sumCos = 0.0; sumSin = 0.0; sumWeight = 0.0; sumWeightSq = 0.0; lastUpdate = Double.nan
        residuals.removeAll(); notesSeen = 0
    }
}

/// Key finding for a lone melody: duration-weighted scale-degree histogram against Temperley's
/// melodic key profiles, with extra weight on phrase-final notes and the longest note, which
/// for a sung tune are the strongest tonic cues.
public enum MelodicKeyFinder {
    private static let MAJOR: [Double] = [5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0]
    private static let MINOR: [Double] = [5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0]

    public struct Candidate: Hashable, Sendable {
        public let key: Key
        public let score: Double
        public init(key: Key, score: Double) { self.key = key; self.score = score }
    }

    /// [sigma] is the singer's spread in semitones: notes between two keys share their weight.
    public static func rank(_ notes: [SungNote], phraseGapSeconds: Double = 0.4, sigma: Double = 0.0) -> [Candidate] {
        if notes.isEmpty { return [] }
        var weight = [Double](repeating: 0, count: 12)
        // The first longest note, as Kotlin's maxByOrNull (which the Kotlin then matches by identity).
        var longest = 0
        for i in 1..<notes.count where notes[i].durationSeconds > notes[longest].durationSeconds { longest = i }
        for (i, n) in notes.enumerated() {
            var w = n.durationSeconds
            let next: SungNote? = i + 1 < notes.count ? notes[i + 1] : nil
            let phraseEnd = next == nil || next!.startSeconds - (n.startSeconds + n.durationSeconds) > phraseGapSeconds
            if phraseEnd { w += 0.6 }
            if i == longest { w += 0.4 }
            for (pc, share) in softShares(n.pitch, sigma) { weight[pc] += w * share }
        }
        let mean = average(weight)
        var vx = 0.0; for v in weight { vx += (v - mean) * (v - mean) }
        if vx < 1e-9 { return [] }
        var out: [Candidate] = []
        for mode in Mode.allCases {
            let p = mode == .major ? MAJOR : MINOR
            let pm = average(p); var vp = 0.0; for v in p { vp += (v - pm) * (v - pm) }
            for tonic in 0..<12 {
                var cov = 0.0
                for i in 0..<12 { cov += (weight[i] - mean) * (p[floorMod(i - tonic, 12)] - pm) }
                out.append(Candidate(key: Key(tonic, mode), score: cov / (vx * vp).squareRoot()))
            }
        }
        // Stable, as Kotlin's sortByDescending: equal scores keep their order.
        return out.enumerated().sorted { $0.element.score != $1.element.score ? $0.element.score > $1.element.score : $0.offset < $1.offset }.map { $0.element }
    }

    private static func average(_ a: [Double]) -> Double {
        var s = 0.0
        for v in a { s += v }
        return s / Double(a.count)
    }
}
