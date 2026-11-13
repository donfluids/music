import Foundation

/// Suggests a key from the chords heard over a whole song.
///
/// Every key earns points for each second a chord that belongs to it was sounding (the tonic
/// most, then IV and V, then the other diatonic triads, small credit for common borrowings),
/// loses points for foreign chords, and earns a bonus for each V-to-I cadence and for opening
/// on its tonic. This is deliberately simple: it is meant to explain itself on screen.
public enum ChordKeyEstimator {

    public struct Suggestion: Hashable, Sendable {
        public let key: Key
        /// 0..1, roughly the share of the song that fits the key, after bonuses.
        public let score: Double
        /// Number of V-to-I (or V-to-i) cadences heard in this key.
        public let cadences: Int
        public init(key: Key, score: Double, cadences: Int) { self.key = key; self.score = score; self.cadences = cadences }
    }

    /// Weight of a chord by its interval above the tonic and quality, for a major key.
    private static func majorWeight(_ interval: Int, _ minor: Bool) -> Double {
        if interval == 0 && !minor { return 1.0 }    // I
        if interval == 5 && !minor { return 0.8 }    // IV
        if interval == 7 && !minor { return 0.8 }    // V
        if interval == 9 && minor { return 0.6 }     // vi
        if interval == 2 && minor { return 0.5 }     // ii
        if interval == 4 && minor { return 0.4 }     // iii
        if interval == 10 && !minor { return 0.25 }  // bVII, common in worship songs
        if interval == 5 && minor { return 0.15 }    // iv borrowed
        if interval == 2 && !minor { return 0.1 }    // II (V of V)
        if interval == 4 && !minor { return 0.0 }    // III (V of vi), common in gospel
        if interval == 9 && !minor { return 0.0 }    // VI (V of ii)
        return -0.3
    }

    /// Weight of a chord for a minor key (natural and harmonic minor both allowed).
    private static func minorWeight(_ interval: Int, _ minor: Bool) -> Double {
        if interval == 0 && minor { return 1.0 }     // i
        if interval == 5 && minor { return 0.7 }     // iv
        if interval == 7 && !minor { return 0.7 }    // V (harmonic minor)
        if interval == 7 && minor { return 0.5 }     // v
        if interval == 8 && !minor { return 0.6 }    // VI
        if interval == 3 && !minor { return 0.5 }    // III
        if interval == 10 && !minor { return 0.5 }   // VII
        if interval == 5 && !minor { return 0.15 }   // IV (dorian)
        if interval == 2 && minor { return 0.4 }     // ii (the diminished chord is heard as minor)
        return -0.3
    }

    public static func weight(_ key: Key, _ chord: ChordLabel) -> Double {
        let interval = floorMod(chord.root - key.tonic, 12)
        let base = key.mode == .major ? majorWeight(interval, chord.minor) : minorWeight(interval, chord.minor)
        // Suspended chords say less about mode; a dominant seventh on V says more.
        if chord.type.isSus { return base * 0.8 }
        if chord.type == .dominantSeventh && interval == 7 { return base + 0.1 }
        return base
    }

    private static func isTonic(_ key: Key, _ chord: ChordLabel) -> Bool {
        chord.root == key.tonic && chord.minor == (key.mode == .minor)
    }

    private static func isDominant(_ key: Key, _ chord: ChordLabel) -> Bool {
        floorMod(chord.root - key.tonic, 12) == 7 && (!chord.minor || key.mode == .minor)
    }

    /// Ranks all 24 keys for the chords in [segments], best first. Empty when nothing was heard.
    public static func suggest(_ segments: [ChordSegment]) -> [Suggestion] {
        let chords = segments.filter { $0.chord != nil && $0.duration > 0 }
        if chords.isEmpty { return [] }
        var total = 0.0
        for s in chords { total += Double(s.duration) }
        if total <= 0.0 { return [] }

        var out: [Suggestion] = []
        out.reserveCapacity(24)
        for key in Key.all() {
            var score = 0.0
            for s in chords { score += weight(key, s.chord!) * Double(s.duration) }
            score /= total

            var cadences = 0
            var previous: ChordLabel? = nil
            for s in chords {
                let c = s.chord!
                if let p = previous, p != c, isDominant(key, p), isTonic(key, c) { cadences += 1 }
                previous = c
            }
            // Cadences add a bounded bonus so a short excerpt with two of them is not swamped.
            score += min(Double(cadences) * 0.03, 0.15)
            if isTonic(key, chords.first!.chord!) { score += 0.05 }
            if isTonic(key, chords.last!.chord!) { score += 0.05 }
            out.append(Suggestion(key: key, score: score, cadences: cadences))
        }
        // Kotlin's sortByDescending is stable: equal scores keep their order.
        return out.enumerated().sorted { a, b in
            a.element.score != b.element.score ? a.element.score > b.element.score : a.offset < b.offset
        }.map { $0.element }
    }

    /// 0..1 confidence in the top suggestion: its fit and its lead over the runner-up.
    public static func confidence(_ ranked: [Suggestion]) -> Double {
        if ranked.count < 2 { return 0.0 }
        let best = ranked[0].score
        let second = ranked[1].score
        return (0.5 * best.coerceIn(0.0, 1.0) + 0.5 * ((best - second) * 4.0).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
    }
}
