import Foundation

/// The major and minor key profiles a chroma vector is matched against, tonic first.
public enum KeyProfile: Sendable {
    /// Krumhansl & Kessler (1982) probe-tone ratings.
    case krumhanslKessler
    /// Temperley (1999), as in MelodicKeyFinder. On GuitarSet heard through Basic Pitch it finds
    /// the key of solo lines far more often than Krumhansl & Kessler (49 % against 35 %) and
    /// strummed takes about as often (tools/keyeval).
    case temperley

    public var major: [Double] {
        switch self {
        case .krumhanslKessler: return [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88]
        case .temperley: return [5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0]
        }
    }

    public var minor: [Double] {
        switch self {
        case .krumhanslKessler: return [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]
        case .temperley: return [5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0]
        }
    }
}

/// Krumhansl–Schmuckler key finding: correlate a 12-bin chroma vector with the 24
/// major/minor key profiles and pick the best match.
public enum KeyDetector {

    public struct Candidate: Hashable, Sendable {
        public let key: Key
        public let correlation: Double
        public init(key: Key, correlation: Double) { self.key = key; self.correlation = correlation }
    }

    /// All 24 keys ranked by correlation with [chroma] under [profiles], best first.
    public static func rank(_ chroma: [Float], _ profiles: KeyProfile = .krumhanslKessler) -> [Candidate] {
        precondition(chroma.count == 12)
        let x = chroma.map { Double($0) }
        let xMean = average(x)
        var xVar = 0.0
        for v in x { xVar += (v - xMean) * (v - xMean) }
        if xVar < 1e-12 {
            // Silence or a flat profile: nothing to say.
            return Key.all().map { Candidate(key: $0, correlation: 0.0) }
        }
        let majorProfile = profiles.major
        let minorProfile = profiles.minor
        var out: [Candidate] = []
        out.reserveCapacity(24)
        for mode in Mode.allCases {
            let profile = mode == .major ? majorProfile : minorProfile
            let pMean = average(profile)
            var pVar = 0.0
            for v in profile { pVar += (v - pMean) * (v - pMean) }
            for tonic in 0..<12 {
                var cov = 0.0
                for i in 0..<12 {
                    let p = profile[floorMod(i - tonic, 12)]
                    cov += (x[i] - xMean) * (p - pMean)
                }
                out.append(Candidate(key: Key(tonic, mode), correlation: cov / (xVar * pVar).squareRoot()))
            }
        }
        // Kotlin's sortByDescending is stable: equal correlations keep their order.
        return out.enumerated().sorted { a, b in
            a.element.correlation != b.element.correlation ? a.element.correlation > b.element.correlation : a.offset < b.offset
        }.map { $0.element }
    }

    /// A 0..1 confidence for the top candidate: how well it fits, and how clearly it beats
    /// the runner-up. Both halves are heuristics; the number is for the display, not for science.
    public static func confidence(_ ranked: [Candidate]) -> Double {
        if ranked.count < 2 { return 0.0 }
        let best = ranked[0].correlation
        let second = ranked[1].correlation
        let fit = best.coerceIn(0.0, 1.0)
        let margin = ((best - second) * 4.0).coerceIn(0.0, 1.0)
        return 0.5 * fit + 0.5 * margin
    }

    /// Kotlin's DoubleArray.average(): the sum in order, divided by the count.
    private static func average(_ values: [Double]) -> Double {
        var sum = 0.0
        for v in values { sum += v }
        return sum / Double(values.count)
    }
}
