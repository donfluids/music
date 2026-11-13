import Foundation

/// Chord templates: 12 roots × 7 types, compared with a chroma vector by cosine similarity.
/// Extensions carry a small penalty so a plain triad with a little overtone energy on the
/// seventh is not read as a seventh chord.
public final class ChordTemplates {
    public let chords: [ChordLabel] = ChordType.allCases.flatMap { t in (0..<12).map { ChordLabel($0, t) } }
    private let weights: [[Float]]
    private let norms: [Float]

    public init() {
        weights = chords.map { c in
            var w = [Float](repeating: 0, count: 12)
            for (k, interval) in c.type.intervals.enumerated() {
                let v: Float
                switch k { case 0: v = 1.0; case 1: v = 0.8; case 2: v = 0.9; default: v = 0.7 }
                w[floorMod(c.root + interval, 12)] = v
            }
            return w
        }
        norms = weights.map { w in
            var sum = 0.0
            for x in w { sum += Double(x * x) }
            return Float(sum.squareRoot())
        }
    }

    /// Cosine similarity of [chroma] with every template, written into [out]. Returns the chroma norm.
    @discardableResult
    public func similarities(_ chroma: [Float], _ out: inout [Float]) -> Float {
        var norm: Float = 0
        for v in chroma { norm += v * v }
        norm = norm.squareRoot()
        if norm <= 0 {
            for i in out.indices { out[i] = 0 }
            return 0
        }
        weights.withUnsafeBufferPointer { weights in
            norms.withUnsafeBufferPointer { norms in
                chroma.withUnsafeBufferPointer { chroma in
                    out.withUnsafeMutableBufferPointer { out in
                        for i in 0..<weights.count {
                            weights[i].withUnsafeBufferPointer { w in
                                var dot: Float = 0
                                for pc in 0..<12 { dot += chroma[pc] * w[pc] }
                                out[i] = dot / (norm * norms[i])
                            }
                        }
                    }
                }
            }
        }
        return norm
    }

    /// Best template for a chroma vector, or nil when nothing fits well enough.
    public func match(_ chroma: [Float], minSimilarity: Float = 0.6) -> (first: ChordLabel, second: Float)? {
        var sims = [Float](repeating: 0, count: chords.count)
        if similarities(chroma, &sims) <= 0 { return nil }
        var best = -1
        var bestScore = -Float.infinity
        for i in chords.indices {
            let s = sims[i] - ChordTemplates.penalty(chords[i].type)
            if s > bestScore { bestScore = s; best = i }
        }
        return best >= 0 && sims[best] >= minSimilarity ? (chords[best], sims[best]) : nil
    }

    /// Similarity handicap for extended chords, in similarity units.
    public static func penalty(_ type: ChordType) -> Float {
        switch type {
        case .major, .minor: return 0
        case .sus4, .sus2: return 0.05
        default: return 0.04
        }
    }
}

/// Tracks chords over time with a hidden Markov model decoded by fixed-lag Viterbi.
///
/// States are the 84 chords plus "no chord". Each observation (an averaged chroma vector) scores
/// every state by template similarity. The model prefers to stay on
/// the current chord, with a stickiness that depends on how much time the observation spans,
/// and when a key is known it moves more readily to chords that belong to it. The decision for
/// an observation is made [lag] observations later, once the following context is known, which
/// removes the one-off misreads a frame-by-frame detector produces.
public final class ChordTracker {
    /// Observations of delay before a decision is made.
    public let lag: Int
    /// Sharpness of the emission model: log-probability per unit of similarity.
    private let beta: Double
    /// Extra log-probability for moving to a chord that belongs to the current key.
    private let keyAffinity: Double
    /// Expected chord duration in seconds; sets the self-transition probability.
    private let chordSeconds: Double
    /// Similarity credited to the no-chord state; a flat chroma scores about 0.5 by chance.
    private let noChordSimilarity: Double
    /// Mean chroma energy below which the observation counts as silence.
    private let minEnergy: Float

    private let templates = ChordTemplates()
    private let stateCount: Int
    private let noChord: Int
    private var sims: [Float]

    private var delta: [Double]
    private var nextDelta: [Double]
    private var emissions: [Double]
    private var switchLog: [Double]
    private var key: Key?

    /// Chord the model currently favours before any look-ahead, for a fast provisional display.
    public private(set) var provisional: ChordLabel?
    private var backPointers: [[Int]]
    private var ring = 0
    private var observations = 0

    /// Chord decided for the observation [lag] steps ago; nil means no chord.
    public private(set) var decided: ChordLabel?

    /// True after [observe] produced a decision (false during the first [lag] observations).
    public private(set) var hasDecision = false

    /// Index of the observation the latest decision refers to.
    public private(set) var decidedIndex = -1

    public init(
        lag: Int = 4,
        beta: Double = 12.0,
        keyAffinity: Double = 1.5,
        chordSeconds: Double = 1.5,
        noChordSimilarity: Double = 0.58,
        minEnergy: Float = 1e-4
    ) {
        self.lag = lag
        self.beta = beta
        self.keyAffinity = keyAffinity
        self.chordSeconds = chordSeconds
        self.noChordSimilarity = noChordSimilarity
        self.minEnergy = minEnergy
        stateCount = templates.chords.count + 1
        noChord = stateCount - 1
        sims = [Float](repeating: 0, count: templates.chords.count)
        delta = [Double](repeating: 0, count: stateCount)
        nextDelta = [Double](repeating: 0, count: stateCount)
        emissions = [Double](repeating: 0, count: stateCount)
        switchLog = [Double](repeating: 0, count: stateCount)
        backPointers = [[Int]](repeating: [Int](repeating: 0, count: stateCount), count: lag)
    }

    public func reset() {
        for s in delta.indices { delta[s] = 0.0 }
        observations = 0
        ring = 0
        decided = nil
        provisional = nil
        hasDecision = false
        decidedIndex = -1
        key = nil
    }

    /// Tells the tracker which key the song is in, or nil when unknown.
    public func setKey(_ key: Key?) {
        self.key = key
    }

    /// Feeds one averaged chroma vector spanning [seconds]. Returns true when a decision was
    /// made for the observation [lag] steps back; read it from [decided] and [decidedIndex].
    @discardableResult
    public func observe(_ chroma: [Float], _ seconds: Double) -> Bool {
        let norm = templates.similarities(chroma, &sims)
        var energy: Float = 0
        for v in chroma { energy += v }
        energy /= 12
        let silent = norm <= 0 || energy < minEnergy

        let stay = exp(-seconds.coerceAtLeast(0.01) / chordSeconds)
        let logStay = log(stay)
        computeEmissions(silent)
        computeSwitchCosts(1.0 - stay)
        let stateCount = self.stateCount
        let ringNow = ring

        if observations == 0 {
            for s in 0..<stateCount { nextDelta[s] = emissions[s]; backPointers[ringNow][s] = s }
        } else {
            // The switch cost depends only on the destination, so the best predecessor for a
            // switch is the globally best state (or the runner-up when that is the state itself).
            var best = 0
            var second = -1
            for s in 1..<stateCount {
                if delta[s] > delta[best] { second = best; best = s } else if second < 0 || delta[s] > delta[second] { second = s }
            }
            delta.withUnsafeBufferPointer { delta in
                nextDelta.withUnsafeMutableBufferPointer { nextDelta in
                    emissions.withUnsafeBufferPointer { emissions in
                        switchLog.withUnsafeBufferPointer { switchLog in
                            backPointers[ringNow].withUnsafeMutableBufferPointer { psi in
                                for s in 0..<stateCount {
                                    let fromSelf = delta[s] + logStay
                                    let from = best != s ? best : second
                                    let fromOther = delta[from] + switchLog[s]
                                    if fromSelf >= fromOther { nextDelta[s] = fromSelf; psi[s] = s } else { nextDelta[s] = fromOther; psi[s] = from }
                                    nextDelta[s] += emissions[s]
                                }
                            }
                        }
                    }
                }
            }
        }
        // Keep the numbers bounded.
        var max = -Double.infinity
        var argMax = 0
        for s in 0..<stateCount where nextDelta[s] > max { max = nextDelta[s]; argMax = s }
        for s in 0..<stateCount { nextDelta[s] -= max }
        swap(&delta, &nextDelta)
        provisional = argMax == noChord ? nil : templates.chords[argMax]
        observations += 1
        ring = (ring + 1) % lag

        if observations < lag { hasDecision = false; return false }
        // Backtrack lag - 1 steps from the current best state.
        var state = 0
        for s in 1..<stateCount where delta[s] > delta[state] { state = s }
        var r = ring
        for _ in 0..<(lag - 1) {
            r = (r - 1 + lag) % lag
            state = backPointers[r][state]
        }
        decided = state == noChord ? nil : templates.chords[state]
        decidedIndex = observations - lag
        hasDecision = true
        return true
    }

    /// Log-emission per state: scaled similarity, with the extension penalty and a silence state.
    private func computeEmissions(_ silent: Bool) {
        for s in 0..<stateCount {
            if s == noChord {
                emissions[s] = beta * noChordSimilarity + (silent ? 5.0 : 0.0)
            } else if silent {
                emissions[s] = 0.0
            } else {
                emissions[s] = beta * Double(sims[s] - ChordTemplates.penalty(templates.chords[s].type))
            }
        }
    }

    /// Log-probability of switching to each state; in-key chords share more of the switch mass.
    private func computeSwitchCosts(_ switchMass: Double) {
        let k = key
        var total = 0.0
        for s in 0..<stateCount {
            let w: Double
            if let k = k, s != noChord {
                w = inKey(k, templates.chords[s]) ? exp(keyAffinity) : 1.0
            } else {
                w = 1.0
            }
            switchLog[s] = w
            total += w
        }
        let logMass = log(switchMass.coerceAtLeast(1e-9))
        for s in 0..<stateCount { switchLog[s] = logMass + log(switchLog[s] / total) }
    }

    private func inKey(_ key: Key, _ chord: ChordLabel) -> Bool {
        guard let degree = key.scale.firstIndex(of: chord.root) else { return false }
        // The diminished degree (vii in major, ii in minor) is heard as a minor triad.
        let diatonicMinor = key.chords[degree].quality != .major
        return chord.minor == diatonicMinor || chord.type.isSus
    }
}
