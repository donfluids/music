import Foundation

/// Follows where untrained singers have taken the key, as one continuous position in cents.
///
/// Singers may start in C, wander up to C# and D, and come back. Tracking pitch within a
/// semitone cannot see that (a tune moved up exactly a semitone fits the new grid perfectly),
/// and counting all the notes mixes two keys into a third. Instead:
///
/// 1. Every note is compared with a pitch pattern placed at each of 120 positions around the
///    octave, 10 cents apart. A Viterbi pass links the notes in time: the position may drift a
///    few cents a second at little cost, a clean step of one or two semitones (a deliberate key
///    change) is allowed at a fixed cost, and anything bigger between two notes is ruled out. The
///    position never resets at a semitone, so the result is a single curve: C, C plus 40, C# plus
///    10, D, back to C.
/// 2. The pattern is the song itself. Many songs use only five notes (C D E G A), which fit
///    three seven-note scales; moved up a semitone they fit three shifted ones, two of them a
///    semitone apart in opposite directions, so a generic scale cannot tell which way the singers
///    went. The tune matches itself only at the true shift. The first pass uses the opening
///    notes, before the singers have had time to wander, blended with the scale that fits them;
///    then the notes, moved back along that curve, become the song's own pitch profile, and the
///    curve is decoded again against it, twice.
/// 3. From the curve: the centre (where the singing spent most of its time), now, the range, and
///    whether the singers are still moving or have settled somewhere new. An instant step and a
///    quick slide that then holds look alike once real voices blur the change, and to a player
///    they mean the same thing.
///
/// The position is of the major scale's tonic; a relative minor shares it. Which of the pair is
/// home is left to the key finders, which get the notes moved back to the centre.
public final class KeyLevelTracker {
    /// How fast the level may drift, as variance per second (cents squared): about a semitone in
    /// a minute comes easily, so wandering is followed, but a careless singer's note-to-note
    /// scatter is not mistaken for it.
    private let driftVariancePerSecond: Double
    /// Cost of a clean step of one or two semitones, in log-likelihood.
    private let stepCost: Double
    private let minNotes: Int
    private let maxNotes: Int

    public init(driftVariancePerSecond: Double = 150.0, stepCost: Double = 6.0, minNotes: Int = 8, maxNotes: Int = 400) {
        self.driftVariancePerSecond = driftVariancePerSecond
        self.stepCost = stepCost
        self.minNotes = minNotes
        self.maxNotes = maxNotes
    }

    /// How the level has been moving lately. [settled]: it moved most of a semitone or more in
    /// the last 25 seconds and has held the new level for at least five, whether by a deliberate
    /// key change or a quick slide: the singers are now in a new key, so follow them. [rising]
    /// and [falling]: still on the move.
    public enum Move: Hashable, Sendable { case steady, rising, falling, settled }

    public struct Level: Hashable {
        /// The notes moved back to the centre key and onto its exact semitones.
        public let notes: [SungNote]
        /// Centre and now, in cents at concert pitch, as positions of the scale's anchor. Their
        /// cents within a semitone and their differences are what count: for a five-note tune the
        /// whole-semitone part may sit on any of the scales that fit it, which cancels out.
        public let centreCents: Double
        public let nowCents: Double
        /// Where most of the singing sat (10th to 90th percentile), relative to the centre's nearest semitone.
        public let lowCents: Double
        public let highCents: Double
        public let move: Move
        /// How far notes sat from the scale once the wandering is removed, in cents.
        public let spreadCents: Double
        /// Where singers who have not wandered sit within the semitone, in cents from concert pitch
        /// (-50 to 50): the centre, placed more finely than the curve's 10-cent steps.
        public let steadyCents: Double

        public init(notes: [SungNote], centreCents: Double, nowCents: Double, lowCents: Double, highCents: Double, move: Move, spreadCents: Double, steadyCents: Double = 0.0) {
            self.steadyCents = steadyCents
            self.notes = notes
            self.centreCents = centreCents
            self.nowCents = nowCents
            self.lowCents = lowCents
            self.highCents = highCents
            self.move = move
            self.spreadCents = spreadCents
        }

        /// Semitones from the centre's nearest key to where the singers are now.
        public var nowShift: Int { roundToInt(nowCents / 100.0) - roundToInt(centreCents / 100.0) }
        public var lowShift: Int { roundToInt(lowCents / 100.0) }
        public var highShift: Int { roundToInt(highCents / 100.0) }
    }

    private var cachedCount = -1
    private var cachedFirst: SungNote?
    private var cachedPath: [Double] = []
    private var cachedSettle = 0.0
    private var spreadForTemplate = 30.0

    /// Nil until there are enough notes to place the scale.
    public func analyse(_ notes: [SungNote]) -> Level? {
        let use = notes.count > maxNotes ? Array(notes[(notes.count - maxNotes)...]) : notes
        if use.count < minNotes { return nil }
        // The tracker must never assume a tighter singer than the notes show, or it would
        // explain careless notes away as wandering.
        floorSpread = noteToNoteSpread(use)
        // The note still being sung changes every frame; the path is decoded over closed notes
        // and reused until another note closes.
        if use.count != cachedCount || use.first! != cachedFirst {
            cachedPath = refine(use)
            cachedSettle = settle(use, cachedPath)
            cachedCount = use.count
            cachedFirst = use.first!
        }
        let path = cachedPath
        let n = use.count

        // Centre: the duration-weighted median of the curve.
        let weights = use.map { $0.durationSeconds.coerceIn(0.1, 1.5) }
        let centre = weightedPercentile(path, weights, 0.5)
        let centreGrid = 100.0 * Double(roundToInt(centre / 100.0))
        let low = weightedPercentile(path, weights, 0.1) - centreGrid
        let high = weightedPercentile(path, weights, 0.9) - centreGrid

        // Now: the last few notes.
        let tail = max(1, min(4, n / 4))
        var nowSum = 0.0; var nowW = 0.0
        for i in (n - tail)..<n { nowSum += path[i] * weights[i]; nowW += weights[i] }
        let now = nowSum / nowW

        // Each note moved back by however far the level had wandered from the centre's semitone,
        // so the key finders see one steady key, exactly in tune.
        let moved = use.enumerated().map { i, note in
            SungNote(pitch: note.concertPitch - (path[i] - centreGrid) / 100.0, startSeconds: note.startSeconds, durationSeconds: note.durationSeconds, gridCents: 0.0)
        }

        // Spread: distance from the nearest scale note once the wandering is removed.
        let dists = (0..<n).map { i in distanceToScale(use[i].concertPitch * 100.0, path[i]) }
        let spread = max(1.4826 * weightedPercentile(dists, weights, 0.5), floorSpread)
        spreadForTemplate = spread.coerceIn(15.0, 45.0)

        let steady = centre + cachedSettle
        return Level(notes: moved, centreCents: centre, nowCents: now, lowCents: low, highCents: high, move: classify(use, path), spreadCents: spread,
                     steadyCents: steady - 100.0 * Double(kotlinRound(steady / 100.0)))
    }

    private func classify(_ notes: [SungNote], _ path: [Double]) -> Move {
        let n = notes.count
        let endT = notes.last!.startSeconds
        // Settled somewhere new: in the last 25 seconds the level moved most of a semitone or more
        // within about six seconds, then held for at least five. A wobble does not hold; a creep
        // is too slow to move that far that fast.
        for i in stride(from: n - 1, through: 1, by: -1) {
            let at = notes[i].startSeconds
            if endT - at > 25.0 { break }
            if endT - at < 5.0 { continue }
            let held = Array(path[i..<n])
            let ones = [Double](repeating: 1.0, count: held.count)
            let heldLevel = weightedPercentile(held, ones, 0.5)
            if weightedPercentile(held, ones, 0.9) - weightedPercentile(held, ones, 0.1) >= 40.0 { continue }
            let earlier = (0..<i).filter { (0.0...6.0).contains(at - notes[$0].startSeconds) }
            if earlier.contains(where: { abs(heldLevel - path[$0]) >= 70.0 }) { return .settled }
        }
        // Otherwise the slope over the last 15 seconds.
        let recent = (0..<n).filter { endT - notes[$0].startSeconds <= 15.0 }
        if recent.count < 4 { return .steady }
        let ts = recent.map { notes[$0].startSeconds }
        let ys = recent.map { path[$0] }
        let tm = KeyLevelTracker.average(ts); let ym = KeyLevelTracker.average(ys)
        var num = 0.0; var den = 0.0
        for k in ts.indices { num += (ts[k] - tm) * (ys[k] - ym); den += (ts[k] - tm) * (ts[k] - tm) }
        let slope = den > 1e-9 ? num / den : 0.0
        if slope > 0.8 { return .rising }
        if slope < -0.8 { return .falling }
        return .steady
    }

    /// Decodes against a first pattern built from the song's opening notes (where the singers
    /// have not yet had time to wander), blended with the seven-note scale that best fits them;
    /// then twice against the song's own pitch profile, built from all the notes moved back along
    /// the previous curve.
    private func refine(_ notes: [SungNote]) -> [Double] {
        let STATES = KeyLevelTracker.STATES, STEP = KeyLevelTracker.STEP
        let sigma = max(spreadForTemplate, floorSpread)
        let weights = notes.map { $0.durationSeconds.coerceIn(0.1, 1.5) }
        var opening = notes.indices.filter { notes[$0].startSeconds - notes[0].startSeconds <= 8.0 }
        if opening.isEmpty { opening = Array(notes.indices) }
        // The scale position that best fits the opening.
        var fit = [Double](repeating: 0, count: STATES)
        for i in opening { let e = scaleEmission(notes[i], sigma); for s in 0..<STATES { fit[s] += e[s] } }
        let scaleAt = Double(KeyLevelTracker.firstArgMax(fit)) * STEP
        var first = [Double](repeating: 0, count: STATES)
        addToProfile(&first, opening.map { notes[$0].concertPitch * 100.0 }, opening.map { weights[$0] }, sigma)
        normalise(&first)
        for b in 0..<STATES {
            let d = distanceToScale(Double(b) * STEP, scaleAt)
            first[b] = 0.5 * first[b] + 0.5 * exp(-d * d / (2 * sigma * sigma))
        }
        // The opening profile is in concert terms, its level at the fitted scale position: decoding
        // against it keeps the curve in concert cents.
        let opener = first
        var path = decode(notes) { note in self.profileEmission(note, opener, scaleAt) }
        for _ in 0..<2 {
            let ref = weightedPercentile(path, weights, 0.5)
            var profile = [Double](repeating: 0, count: STATES)
            // Each note as if sung at the reference level.
            let current = path
            addToProfile(&profile, notes.indices.map { notes[$0].concertPitch * 100.0 - (current[$0] - ref) }, weights, sigma)
            normalise(&profile)
            let built = profile
            path = decode(notes) { note in self.profileEmission(note, built, ref) }
        }
        // Whole octaves are arbitrary: put the centre within half an octave of C.
        let centre = weightedPercentile(path, weights, 0.5)
        let octaves = 1200.0 * ((centre + 600.0) / 1200.0).rounded(.down)
        for i in path.indices { path[i] -= octaves }
        return path
    }

    private func addToProfile(_ profile: inout [Double], _ cents: [Double], _ weights: [Double], _ sigma: Double) {
        let STATES = KeyLevelTracker.STATES, STEP = KeyLevelTracker.STEP
        for (k, c) in cents.enumerated() {
            for b in 0..<STATES {
                var d = Double(floorMod(kotlinRound(c - Double(b) * STEP), 1200))
                if d > 600.0 { d = 1200.0 - d }
                profile[b] += weights[k] * exp(-d * d / (2 * sigma * sigma))
            }
        }
    }

    private func normalise(_ profile: inout [Double]) {
        let peak = profile.max()!.coerceAtLeast(1e-9)
        for b in profile.indices { profile[b] /= peak }
    }

    /// State s places the profile's reference level [ref] at position s: the note is looked up relative to it.
    private func profileEmission(_ note: SungNote, _ profile: [Double], _ ref: Double) -> [Double] {
        let STATES = KeyLevelTracker.STATES, STEP = KeyLevelTracker.STEP
        let c = note.concertPitch * 100.0
        let w = note.durationSeconds.coerceIn(0.1, 1.5) / 0.5
        return (0..<STATES).map { s in
            let bin = floorMod(kotlinRound((c - (Double(s) * STEP - ref)) / STEP), STATES)
            return w * log(profile[bin] + KeyLevelTracker.OUT_OF_SCALE)
        }
    }

    /// Viterbi over 120 positions with the given per-note emission; returns the unwrapped curve in cents.
    private func decode(_ notes: [SungNote], _ emission: (SungNote) -> [Double]) -> [Double] {
        let STATES = KeyLevelTracker.STATES, STEP = KeyLevelTracker.STEP, BAND = KeyLevelTracker.BAND
        let n = notes.count
        let emit = notes.map { emission($0) }
        var score = emit[0]
        var back = [[Int]](repeating: [Int](repeating: 0, count: STATES), count: n)
        var next = [Double](repeating: 0, count: STATES)
        // The transition cost depends only on the offset k, so it is worked out once per note pair.
        var moves = [Double](repeating: 0, count: 2 * BAND + 1)
        for i in stride(from: 1, to: n, by: 1) {
            let dt = (notes[i].startSeconds - notes[i - 1].startSeconds).coerceAtLeast(0.05)
            let variance = driftVariancePerSecond * dt + 5.0 * 5.0
            for k in -BAND...BAND {
                let cents = Double(abs(k)) * STEP
                let drift = -(cents * cents) / (2 * variance)
                let step = (60.0...240.0).contains(cents) ? -stepCost : -Double.infinity
                moves[k + BAND] = max(drift, step)
            }
            score.withUnsafeBufferPointer { score in
                emit[i].withUnsafeBufferPointer { e in
                    back[i].withUnsafeMutableBufferPointer { backRow in
                        for s in 0..<STATES {
                            var best = -Double.infinity; var arg = s
                            for k in -BAND...BAND {
                                let from = floorMod(s - k, STATES)
                                let v = score[from] + moves[k + BAND]
                                if v > best { best = v; arg = from }
                            }
                            next[s] = best + e[s]
                            backRow[s] = arg
                        }
                    }
                }
            }
            for s in 0..<STATES { score[s] = next[s] }
        }
        var s = KeyLevelTracker.firstArgMax(score)
        var states = [Int](repeating: 0, count: n)
        for i in stride(from: n - 1, through: 0, by: -1) {
            states[i] = s
            if i > 0 { s = back[i][s] }
        }
        // Unwrap: each step is the short way round, so the curve never resets at a semitone.
        var path = [Double](repeating: 0, count: n)
        path[0] = Double(states[0]) * STEP
        for i in stride(from: 1, to: n, by: 1) {
            var d = states[i] - states[i - 1]
            if d > STATES / 2 { d -= STATES }
            if d < -STATES / 2 { d += STATES }
            path[i] = path[i - 1] + Double(d) * STEP
        }
        return path
    }

    /// Log-likelihood of [note] for the major scale's tonic at each position, weighted by duration.
    private func scaleEmission(_ note: SungNote, _ sigma: Double) -> [Double] {
        let cents = note.concertPitch * 100.0
        let w = note.durationSeconds.coerceIn(0.1, 1.5) / 0.5
        return (0..<KeyLevelTracker.STATES).map { s in
            let d = distanceToScale(cents, Double(s) * KeyLevelTracker.STEP)
            return w * log(exp(-d * d / (2 * sigma * sigma)) + KeyLevelTracker.OUT_OF_SCALE)
        }
    }

    /// The curve moves in 10-cent steps, and for a scattered singer the decoding leans toward half
    /// a semitone, where notes between two scale steps cost least. Sitting 39 cents sharp then often
    /// reads as 50, the boundary between two keys. The notes' mean signed distance from the scale,
    /// along the curve, is how far to move a steady level to place it within a few cents. Notes far
    /// from every scale step (chromatic or careless) are left out of the mean.
    private func settle(_ notes: [SungNote], _ path: [Double]) -> Double {
        let limit = 1.5 * max(spreadForTemplate, floorSpread, 20.0)
        var sw = 0.0; var sd = 0.0
        for i in notes.indices {
            let d = signedDistanceToScale(notes[i].concertPitch * 100.0, path[i])
            if abs(d) > limit { continue }
            let w = notes[i].durationSeconds.coerceIn(0.1, 1.5)
            sw += w; sd += w * d
        }
        return sw > 0.0 ? sd / sw : 0.0
    }

    /// Like `distanceToScale`, with the sign: positive when the note is above the nearest scale step.
    private func signedDistanceToScale(_ cents: Double, _ tonicCents: Double) -> Double {
        let rel = Double(floorMod(kotlinRound((cents - tonicCents) * 10), 12000)) / 10.0
        var best = 600.0
        for deg in KeyLevelTracker.MAJOR_DEGREES {
            var d = rel - Double(deg) * 100.0
            if d > 600.0 { d -= 1200.0 }
            if d < -600.0 { d += 1200.0 }
            if abs(d) < abs(best) { best = d }
        }
        return best
    }

    private func distanceToScale(_ cents: Double, _ tonicCents: Double) -> Double {
        let rel = Double(floorMod(kotlinRound(cents - tonicCents), 1200))
        var best = 600.0
        for deg in KeyLevelTracker.MAJOR_DEGREES {
            var d = abs(rel - Double(deg) * 100.0)
            if d > 600.0 { d = 1200.0 - d }
            if d < best { best = d }
        }
        return best
    }

    /// The singer's scatter from one note to the next, in cents. Each note sits some way off the
    /// nearest semitone; a wandering singer carries that offset along, moving it a few cents per
    /// note, while a careless one lands somewhere new each time. The median change between
    /// neighbouring notes (wrapped to a semitone, divided by the square root of two because both
    /// notes scatter) measures the scatter and ignores the wandering, like differencing a trend.
    private func noteToNoteSpread(_ notes: [SungNote]) -> Double {
        var diffs: [Double] = []
        var w: [Double] = []
        for i in stride(from: 1, to: notes.count, by: 1) {
            if notes[i].startSeconds - notes[i - 1].startSeconds > 3.0 { continue }
            let a = notes[i].concertPitch * 100.0
            let b = notes[i - 1].concertPitch * 100.0
            var d = Double(floorMod(kotlinRound(a - b), 100))
            if d > 50.0 { d = 100.0 - d }
            diffs.append(d)
            w.append(min(notes[i].durationSeconds, notes[i - 1].durationSeconds).coerceIn(0.1, 1.5))
        }
        if diffs.count < 4 { return 0.0 }
        return 1.4826 * weightedPercentile(diffs, w, 0.5) / (2.0).squareRoot()
    }

    private func weightedPercentile(_ values: [Double], _ weights: [Double], _ q: Double) -> Double {
        // Kotlin's sortedBy is stable: equal values keep their order.
        let order = values.indices.sorted { a, b in values[a] != values[b] ? values[a] < values[b] : a < b }
        var total = 0.0
        for v in weights { total += v }
        var acc = 0.0
        for i in order { acc += weights[i]; if acc >= q * total { return values[i] } }
        return values[order.last!]
    }

    private var floorSpread = 0.0

    public func reset() { cachedCount = -1; cachedFirst = nil; spreadForTemplate = 30.0; floorSpread = 0.0 }

    private static let STATES = 120
    private static let STEP = 10.0
    /// Up to three semitones between two notes; more than that is not a wander.
    private static let BAND = 30
    /// A note off the scale is unlikely but not impossible: a passing chromatic note.
    private static let OUT_OF_SCALE = 0.05
    private static let MAJOR_DEGREES = [0, 2, 4, 5, 7, 9, 11]

    /// Kotlin's maxByOrNull over indices: the first index holding the largest value.
    private static func firstArgMax(_ values: [Double]) -> Int {
        var best = 0
        for i in 1..<values.count where values[i] > values[best] { best = i }
        return best
    }

    /// Kotlin's List<Double>.average(): the sum in order, divided by the count.
    private static func average(_ values: [Double]) -> Double {
        var sum = 0.0
        for v in values { sum += v }
        return sum / Double(values.count)
    }
}
