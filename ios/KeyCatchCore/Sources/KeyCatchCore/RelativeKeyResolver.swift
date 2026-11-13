import Foundation

/// Major or minor? A major key and its relative minor (C major and A minor) use the same seven
/// notes, so counting notes only finds the pair. This decides which note of the pair is home.
///
/// Each piece of evidence is a score in log-odds style: positive leans to the major key,
/// negative to the minor. They are summed with a small prior toward major, and the sum is
/// compared with a threshold; between the thresholds the answer is "undecided". Weights are
/// set by hand from music theory, not learned, and want tuning against real recordings.
public enum RelativeKeyResolver {

    public struct Evidence: Hashable, Sendable {
        /// Which notes phrases end on and are held: home is where a phrase comes to rest.
        public let endings: Double
        /// The raised seventh of the minor (G# in A minor): minor keys use it, the major never does.
        public let leadingTone: Double
        /// How the cadence notes are approached: B to C and G to C point to C; G# to A, E to A point to A.
        public let approaches: Double
        /// Tonic chord time, the minor's major dominant (E major), and V to I cadences.
        public let chords: Double
        /// The key-profile fit of each, from the note-based or chroma-based finder.
        public let profile: Double
        public let prior: Double

        public init(endings: Double, leadingTone: Double, approaches: Double, chords: Double, profile: Double, prior: Double) {
            self.endings = endings
            self.leadingTone = leadingTone
            self.approaches = approaches
            self.chords = chords
            self.profile = profile
            self.prior = prior
        }

        public var total: Double { endings + leadingTone + approaches + chords + profile + prior }
    }

    /// The pair a key belongs to: its major and its relative minor.
    public static func pair(_ key: Key) -> (first: Key, second: Key) {
        let major = key.mode == .major ? key : key.relative
        return (major, major.relative)
    }

    public static let PRIOR_MAJOR = 0.25
    public static let THRESHOLD = 1.5

    /// Seconds of singing (or of sound, without notes) before the evidence counts in full.
    public static let FULL_EVIDENCE_SECONDS = 8.0

    /// Evidence for [key]'s pair. [notes] are sung notes (empty when not tracking a melody),
    /// [chroma] the long-term pitch-class energy, [segments] the chords heard so far,
    /// [heardSeconds] how long the analyser has listened and [nowSeconds] the current time,
    /// so a last note counts as a resting note only once silence has followed it.
    ///
    /// Evidence from a few notes is weak, so the note and profile terms grow with the amount
    /// heard; a G# or an E major chord is telling at any time and is not scaled. [sigma] is the
    /// singer's spread in semitones: a note between two keys counts partly as each.
    public static func evidence(
        _ key: Key, _ notes: [SungNote], _ chroma: [Float]?, _ segments: [ChordSegment],
        heardSeconds: Double = Double.infinity, nowSeconds: Double = Double.infinity,
        sigma: Double = 0.0
    ) -> Evidence {
        let (major, minor) = pair(key)
        let M = major.tonic
        let m = minor.tonic
        var material = heardSeconds
        if !notes.isEmpty {
            material = 0.0
            for n in notes { material += n.durationSeconds }
        }
        let amount = (material / FULL_EVIDENCE_SECONDS).coerceIn(0.0, 1.0)
        return Evidence(
            endings: amount * endings(notes, M, m, nowSeconds, sigma),
            leadingTone: leadingTone(notes, chroma, M, m, sigma),
            approaches: amount * approaches(notes, M, m, nowSeconds),
            chords: chords(segments, M, m),
            profile: amount * profile(notes, chroma, major, minor, sigma),
            prior: PRIOR_MAJOR
        )
    }

    private static func pc(_ n: SungNote) -> Int { floorMod(n.midi, 12) }

    /// Notes where the singing comes to rest: before a breath, the last note once silence follows, the longest note.
    ///
    /// Each entry carries the note's index: the Kotlin compares the longest note by identity,
    /// which for a Swift struct is its position in the list.
    private static func restingNotes(_ notes: [SungNote], _ nowSeconds: Double, phraseGapSeconds: Double = 0.4) -> [(index: Int, note: SungNote, weight: Double)] {
        if notes.isEmpty { return [] }
        var out: [(index: Int, note: SungNote, weight: Double)] = []
        for (i, n) in notes.enumerated() {
            let end = n.startSeconds + n.durationSeconds
            let nextStart = i + 1 < notes.count ? notes[i + 1].startSeconds : nowSeconds
            let w = n.durationSeconds.coerceAtMost(1.5)
            // The very last note, once the singing stops, is the strongest resting point of all.
            if nextStart - end > phraseGapSeconds { out.append((i, n, i == notes.count - 1 ? 2 * w : w)) }
        }
        // maxBy: the first of the longest notes.
        var longest = 0
        for i in 1..<notes.count where notes[i].durationSeconds > notes[longest].durationSeconds { longest = i }
        if !out.contains(where: { $0.index == longest }) {
            out.append((longest, notes[longest], notes[longest].durationSeconds.coerceAtMost(1.5)))
        }
        return out
    }

    private static func endings(_ notes: [SungNote], _ M: Int, _ m: Int, _ nowSeconds: Double, _ sigma: Double) -> Double {
        let resting = restingNotes(notes, nowSeconds)
        if resting.isEmpty { return 0.0 }
        var sum = 0.0
        var weight = 0.0
        for (_, n, w) in resting {
            weight += w
            for (p, share) in softShares(n.pitch, sigma) {
                let v: Double
                if p == M {
                    v = 1.0
                } else if p == m {
                    v = -1.0
                } else if p == floorMod(M + 7, 12) {
                    v = 0.3   // the major's fifth: a half cadence
                } else if p == floorMod(M + 4, 12) {
                    v = -0.15 // the minor's fifth, but also the major's third
                } else {
                    v = 0.0
                }
                sum += w * share * v
            }
        }
        return 2.0 * sum / weight
    }

    private static func leadingTone(_ notes: [SungNote], _ chroma: [Float]?, _ M: Int, _ m: Int, _ sigma: Double) -> Double {
        let raised = floorMod(M + 8, 12)
        if !notes.isEmpty {
            var count = 0.0
            for (i, n) in notes.enumerated() {
                // A G sung sharp is only partly a G#.
                guard let share = softShares(n.pitch, sigma).first(where: { $0.0 == raised })?.1 else { continue }
                // A held G# is aimed; a blip of a tenth of a second may be a slide or a mix-up.
                let held = (n.durationSeconds / 0.4).coerceAtMost(1.0)
                // Resolving up to the minor's tonic is the textbook use; any G# still counts.
                let resolves = i + 1 < notes.count && pc(notes[i + 1]) == m
                count += share * held * (resolves ? 1.0 : 0.5)
            }
            return -(1.2 * count).coerceAtMost(2.5)
        }
        guard let chroma = chroma else { return 0.0 }
        let natural = chroma[floorMod(M + 7, 12)]
        let r = chroma[raised] / (natural + chroma[raised] + 1e-6 as Float)
        return -2.5 * ((Double(r) - 0.15) / 0.5).coerceIn(0.0, 1.0)
    }

    private static func approaches(_ notes: [SungNote], _ M: Int, _ m: Int, _ nowSeconds: Double) -> Double {
        let resting = Set(restingNotes(notes, nowSeconds).map { $0.note })
        var sum = 0.0
        var count = 0
        for i in stride(from: 1, to: notes.count, by: 1) {
            let to = notes[i]
            if !resting.contains(to) { continue }
            count += 1
            let from = notes[i - 1]
            let leap = abs(to.midi - from.midi)
            let f = floorMod(from.midi - M, 12)
            let p = pc(to)
            if p == M {
                if f == 11 && leap == 1 { sum += 0.6 }                         // B up to C
                else if f == 2 && leap == 2 { sum += 0.4 }                     // D down to C
                else if f == 7 && (leap == 5 || leap == 7) { sum += 0.6 }      // G to C
            } else if p == m {
                if f == 8 && leap == 1 { sum += -1.0 }                         // G# up to A
                else if f == 11 && leap == 2 { sum += -0.4 }                   // B down to A
                else if f == 4 && (leap == 5 || leap == 7) { sum += -0.6 }     // E to A
                else if f == 7 && leap == 2 { sum += -0.3 }                    // G up to A
            }
        }
        if count == 0 { return 0.0 }
        return (2.5 * sum / Double(max(4, count))).coerceIn(-2.0, 2.0)
    }

    private static func chords(_ segments: [ChordSegment], _ M: Int, _ m: Int) -> Double {
        let heard = segments.filter { $0.chord != nil }
        var total = 0.0
        for s in heard { total += Double(s.duration) }
        if total < 2.0 { return 0.0 }
        var tonicMajor = 0.0
        var tonicMinor = 0.0
        var majorDominantOfMinor = 0.0
        var cadences = 0.0
        let E = floorMod(M + 4, 12)
        let G = floorMod(M + 7, 12)
        var previous: ChordLabel? = nil
        for s in heard {
            let c = s.chord!
            let d = Double(s.duration)
            if c.root == M && !c.minor {
                tonicMajor += d
            } else if c.root == m && c.minor {
                tonicMinor += d
            } else if c.root == E && !c.minor {
                majorDominantOfMinor += d
            }
            if let p = previous, p != c {
                if p.root == G && !p.minor && c.root == M && !c.minor { cadences += 0.4 }
                if p.root == E && c.root == m && c.minor { cadences -= p.minor ? 0.2 : 0.4 }
            }
            previous = c
        }
        let share = 2.0 * (tonicMajor - tonicMinor) / total
        let dominant = -1.5 * (majorDominantOfMinor / 2.0).coerceAtMost(1.0)
        return (share + dominant + cadences).coerceIn(-2.5, 2.5)
    }

    private static func profile(_ notes: [SungNote], _ chroma: [Float]?, _ major: Key, _ minor: Key, _ sigma: Double) -> Double {
        if notes.count >= 8 {
            let ranked = MelodicKeyFinder.rank(notes, sigma: sigma)
            guard let a = ranked.first(where: { $0.key == major })?.score else { return 0.0 }
            guard let b = ranked.first(where: { $0.key == minor })?.score else { return 0.0 }
            return (5.0 * (a - b)).coerceIn(-1.2, 1.2)
        }
        guard let chroma = chroma else { return 0.0 }
        let ranked = KeyDetector.rank(chroma)
        let a = ranked.first(where: { $0.key == major })!.correlation
        let b = ranked.first(where: { $0.key == minor })!.correlation
        return (6.0 * (a - b)).coerceIn(-1.2, 1.2)
    }
}

/// Holds the major-or-minor decision steady. It decides once the score clears the threshold,
/// keeps the decision while the score stays on its side, and lets go to "undecided" only when
/// the evidence has clearly faded. A different pair starts afresh.
public final class RelativeModeTracker {
    private let threshold: Double
    public private(set) var decided: Key?
    public private(set) var score: Double = 0.0
    private var pairMajor: Key?
    private var lean: Key?

    public init(threshold: Double = RelativeKeyResolver.THRESHOLD) { self.threshold = threshold }

    /// Returns the key to show: the decision, or the lean when undecided. The lean has its own
    /// small dead band so an undecided display does not swap between the pair on every note.
    /// [threshold] is raised for a scattered singer, whose evidence is weaker; nil uses the
    /// tracker's own (the Kotlin default argument `this.threshold`).
    @discardableResult
    public func update(_ key: Key, _ score: Double, _ threshold: Double? = nil) -> Key {
        let threshold = threshold ?? self.threshold
        let (major, minor) = RelativeKeyResolver.pair(key)
        if major != pairMajor { pairMajor = major; decided = nil; lean = nil }
        self.score = score
        if score > threshold {
            decided = major
        } else if score < -threshold {
            decided = minor
        } else if decided == major && score > threshold / 3 {
            decided = major
        } else if decided == minor && score < -threshold / 3 {
            decided = minor
        } else {
            decided = nil
        }
        if let d = decided {
            lean = d
        } else if lean == nil {
            lean = score >= 0 ? major : minor
        } else if lean == major && score < -RelativeModeTracker.LEAN_BAND {
            lean = minor
        } else if lean == minor && score > RelativeModeTracker.LEAN_BAND {
            lean = major
        }
        return lean!
    }

    public func reset() { decided = nil; score = 0.0; pairMajor = nil; lean = nil }

    private static let LEAN_BAND = 0.5
}
