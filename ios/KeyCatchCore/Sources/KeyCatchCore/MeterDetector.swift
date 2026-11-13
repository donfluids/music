import Foundation

/// Guesses the time signature from how loud the onsets are on each beat.
///
/// Given the beat period and phase from `TempoTracker`, it samples the onset strength on a grid
/// of beats, folds that sequence into bars of 2–7 beats at every possible downbeat offset, and
/// correlates each folded bar with accent templates (strong 1, medium 3 for 4/4; strong 1 and 4
/// for 6/8; 2+2+3 and friends for 7/8).
///
/// Uneven meters cannot be a single tracked pulse: in 7/8 the tracker settles on a two-eighth
/// pulse, or on the whole bar, and a slow 6/8 is often tracked at two eighths too. So the odd
/// templates (5 and 7) and the six-eighth bar are also tried on finer grids:
/// the half-pulse, and the pulse divided by 5 or 7. The grid that wins decides the pulse the
/// screen counts in (`Result.pulseRatio`).
///
/// A separate check of energy halfway versus a third of the way between beats tells simple
/// from compound meter (6/8 felt in two, 12/8 felt in four).
public final class MeterDetector {
    /// Beats needed before an answer is given.
    private let minBeats: Int
    /// Ignore grids finer than this many frames (about 90 ms at the default hop).
    private let minGridFrames: Double

    /// A (bar length, grid divisor) hypothesis; stands in for the Kotlin `Pair<Int, Int>` map key.
    public struct GridKey: Hashable, Sendable {
        /// Bar length in grid steps.
        public let first: Int
        /// Divisor of the tracked period that gives the grid step.
        public let second: Int
        public init(_ first: Int, _ second: Int) { self.first = first; self.second = second }
    }

    /// Where the most recent tracked beat sits on a counted grid, and how to reach that grid.
    public final class Position {
        /// Bar position (0 = downbeat) of the counted pulse at or just before the tracked beat.
        public let index: Int
        /// Tracked periods to move the beat anchor back so it lands on the counted grid.
        public let anchorShift: Double

        public init(index: Int, anchorShift: Double) { self.index = index; self.anchorShift = anchorShift }
    }

    public final class Result {
        /// Beats per bar at the counted pulse, e.g. 4 for 4/4, 2 for 6/8 felt in two, 7 for 7/8.
        public let beatsPerBar: Int
        /// Counted pulse period as a multiple of the tracked beat period (0.5 for 7/8 tracked in twos, 1.5 for 6/8 tracked in twos).
        public let pulseRatio: Double
        /// True when each beat divides in three (6/8, 9/8, 12/8 felt at the dotted quarter).
        public let compound: Bool
        /// Position of the most recent tracked beat on the counted grid.
        public let position: Position
        /// Display label such as "4/4" or "6/8".
        public let label: String
        public let confidence: Double
        /// Best score for each candidate bar length, for the diagnostics display.
        public let scores: [Int: Double]
        /// The (bar length, grid divisor) hypothesis that won.
        public let grid: GridKey
        /// Counted-grid position for every hypothesis, so a kept bar length can follow a new beat phase.
        public let positions: [GridKey: Position]

        public init(
            beatsPerBar: Int, pulseRatio: Double, compound: Bool, position: Position, label: String,
            confidence: Double, scores: [Int: Double], grid: GridKey, positions: [GridKey: Position]
        ) {
            self.beatsPerBar = beatsPerBar
            self.pulseRatio = pulseRatio
            self.compound = compound
            self.position = position
            self.label = label
            self.confidence = confidence
            self.scores = scores
            self.grid = grid
            self.positions = positions
        }

        public var lastBeatIndexInBar: Int { position.index }
    }

    private struct Template {
        let beats: Int
        let accents: [Double]
        let prior: Double
        init(_ beats: Int, _ accents: [Double], _ prior: Double) { self.beats = beats; self.accents = accents; self.prior = prior }
    }

    private let templates: [Template] = [
        Template(2, [1.0, 0.2], 0.90),
        Template(3, [1.0, 0.3, 0.3], 1.00),
        Template(4, [1.0, 0.2, 0.6, 0.2], 1.05),
        Template(5, [1.0, 0.2, 0.6, 0.2, 0.2], 0.85), // 2+3
        Template(5, [1.0, 0.2, 0.2, 0.6, 0.2], 0.85), // 3+2
        Template(6, [1.0, 0.25, 0.25, 0.55, 0.25, 0.25], 0.95),
        Template(7, [1.0, 0.2, 0.6, 0.2, 0.6, 0.2, 0.2], 0.85), // 2+2+3
        Template(7, [1.0, 0.2, 0.2, 0.6, 0.2, 0.6, 0.2], 0.85), // 3+2+2
        Template(7, [1.0, 0.2, 0.6, 0.2, 0.2, 0.6, 0.2], 0.85), // 2+3+2
    ].map { Template($0.beats, MeterDetector.zScore($0.accents), $0.prior) }

    /// Grids to try: divisor of the tracked period, prior, and which bar lengths apply.
    private struct Grid {
        let divisor: Int
        let prior: Double
        let beats: Set<Int>
        init(_ divisor: Int, _ prior: Double, _ beats: Set<Int>) { self.divisor = divisor; self.prior = prior; self.beats = beats }
    }

    private let grids: [Grid] = [
        Grid(1, 1.00, [2, 3, 4, 5, 6, 7]),
        Grid(2, 1.05, [5, 6, 7]), // 6: a slow 6/8 tracked at the two-eighth pulse
        Grid(5, 0.90, [5]),
        Grid(7, 0.90, [7]),
    ]

    /// - Parameters:
    ///   - minBeats: beats needed before an answer is given.
    ///   - minGridFrames: ignore grids finer than this many frames (about 90 ms at the default hop).
    public init(minBeats: Int = 8, minGridFrames: Double = 7.5) {
        self.minBeats = minBeats
        self.minGridFrames = minGridFrames
    }

    /// A finer grid only deserves its prior when there is real onset energy between the tracked
    /// pulses; on a song with nothing between beats it is discounted, which keeps 5/4 and 7/8
    /// from appearing on ordinary music.
    private func gridPrior(_ grid: Grid, _ strengths: [Double]) -> Double {
        if grid.divisor == 1 { return grid.prior }
        var on = 0.0; var onCount = 0
        var off = 0.0; var offCount = 0
        let count = strengths.count
        for i in strengths.indices {
            if (count - 1 - i) % grid.divisor == 0 { on += strengths[i]; onCount += 1 } else { off += strengths[i]; offCount += 1 }
        }
        if onCount == 0 || offCount == 0 || on <= 0.0 { return 0.6 }
        let ratio = (off / Double(offCount)) / (on / Double(onCount))
        return 0.6 + (grid.prior - 0.6) * (ratio / 0.35).coerceIn(0.0, 1.0)
    }

    /// - Parameters:
    ///   - onset: detrended onset strength, oldest first, valid for `length` entries
    ///   - periodFrames: tracked beat period in frames
    ///   - lastBeatFramesAgo: frames between the newest entry and the last tracked beat
    public func analyze(_ onset: [Float], _ length: Int, _ periodFrames: Double, _ lastBeatFramesAgo: Double) -> Result? {
        if periodFrames < 4.0 || Double(length) < periodFrames * Double(minBeats) { return nil }
        let newestBeat = Double(length - 1) - lastBeatFramesAgo
        // Decide simple vs compound first: it changes which bar lengths are common.
        // In compound time a bar of two (6/8) is the usual case, not four (12/8).
        let compoundPulse = isCompound(onset, length, newestBeat, periodFrames)

        var bestScore = -Double.infinity
        var bestBeats = 0
        var bestDivisor = 1
        var bestFit = 0.0
        var scores: [Int: Double] = [:]
        var indexByGrid: [GridKey: Int] = [:]
        // Best folded pattern and its fit per (bar length, grid), for the six-versus-three check.
        var foldedByGrid: [GridKey: [Double]] = [:]
        var fitByGrid: [GridKey: Double] = [:]
        var scoreByGrid: [GridKey: Double] = [:]

        for grid in grids {
            let step = periodFrames / Double(grid.divisor)
            if step < minGridFrames { continue }
            guard let strengths = gridStrengths(onset, length, newestBeat, step) else { continue }
            let z = MeterDetector.zScore(strengths)
            let count = strengths.count
            let gridPrior = gridPrior(grid, strengths)
            for template in templates {
                let m = template.beats
                if !grid.beats.contains(m) || count < 2 * m { continue }
                let periodicity = max(0.0, autocorrelation(z, m))
                let prior = compoundPulse && grid.divisor == 1 && m <= 4 ? MeterDetector.compoundPriors[m]! : template.prior
                var gridBest = -Double.infinity
                var gridOffset = 0
                let key = GridKey(m, grid.divisor)
                for offset in 0..<m {
                    var folded = [Double](repeating: 0, count: m)
                    var counts = [Int](repeating: 0, count: m)
                    for i in z.indices {
                        let pos = floorMod(i - offset, m)
                        folded[pos] += z[i]
                        counts[pos] += 1
                    }
                    for j in 0..<m { folded[j] /= Double(counts[j]) }
                    let fit = MeterDetector.dot(MeterDetector.zScore(folded), template.accents) / Double(m)
                    let score = gridPrior * prior * (0.65 * fit + 0.35 * periodicity)
                    if score > gridBest {
                        gridBest = score; gridOffset = offset
                        foldedByGrid[key] = folded; fitByGrid[key] = fit
                    }
                    if score > bestScore {
                        bestScore = score
                        bestBeats = m
                        bestDivisor = grid.divisor
                        bestFit = fit
                    }
                }
                let previous = indexByGrid[key]
                if previous == nil || gridBest > (scores[m] ?? -Double.infinity) {
                    indexByGrid[key] = floorMod(count - 1 - gridOffset, m)
                }
                scoreByGrid[key] = max(scoreByGrid[key] ?? -Double.infinity, gridBest)
                scores[m] = max(scores[m] ?? -Double.infinity, gridBest)
            }
        }
        if bestBeats == 0 { return nil }

        // Three versus six on the tracked pulse: two bars of three fold into a six whose fourth
        // pulse is as strong as its first, whereas a real 6/8 has a weaker secondary accent
        // there. When the six-fold also fits well, prefer it. The same accent structure at the
        // three level is invisible, because any two-level pattern matches the three template.
        if bestBeats == 3 && bestDivisor == 1 {
            // The six may live on the tracked pulse (a slow 6/8 tracked at the eighth) or on the
            // half grid (a 6/8 tracked at two eighths, whose secondary accent falls between pulses).
            for divisor in [1, 2] {
                guard let six = foldedByGrid[GridKey(6, divisor)] else { continue }
                let sixFit = fitByGrid[GridKey(6, divisor)] ?? 0.0
                if sixFit >= 0.9 && six[3] < 0.8 * six[0] && six[3] > six[1] && six[3] > six[5] {
                    bestBeats = 6
                    bestDivisor = divisor
                    bestFit = sixFit
                    bestScore = scoreByGrid[GridKey(6, divisor)] ?? bestScore
                    break
                }
            }
        }

        let compound = bestDivisor == 1 && bestBeats <= 4 && compoundPulse
        let runnerUp = scores.filter { $0.key != bestBeats }.values.max() ?? 0.0
        let margin = bestScore > 0 ? ((bestScore - runnerUp) / bestScore).coerceIn(0.0, 1.0) : 0.0
        let confidence = (0.5 * bestFit.coerceIn(0.0, 1.0) + 0.5 * margin).coerceIn(0.0, 1.0)

        let positions = indexByGrid.mapValuesWithKeys { grid, fineIndex in position(grid.first, grid.second, fineIndex) }
        let grid = GridKey(bestBeats, bestDivisor)
        let sixInTwo = bestBeats == 6 && bestDivisor <= 2
        return Result(
            beatsPerBar: sixInTwo ? 2 : bestBeats,
            pulseRatio: pulseRatio(bestBeats, bestDivisor),
            compound: compound || sixInTwo,
            position: positions[grid] ?? Position(index: 0, anchorShift: 0.0),
            label: sixInTwo ? "6/8" : label(bestBeats, compound),
            confidence: confidence,
            scores: scores,
            grid: grid,
            positions: positions
        )
    }

    /// Counted period over tracked period for a hypothesis; six eighths are always counted in two.
    private func pulseRatio(_ beats: Int, _ divisor: Int) -> Double {
        if beats == 6 && divisor == 2 { return 1.5 } // tracked at two eighths, counted at three
        if beats == 6 && divisor == 1 { return 3.0 } // tracked at the eighth, counted at three
        return 1.0 / Double(divisor)
    }

    /// Maps a fine-grid bar index to the counted grid, moving the anchor back onto it if needed.
    private func position(_ beats: Int, _ divisor: Int, _ fineIndex: Int) -> Position {
        if beats == 6 && divisor <= 2 { return Position(index: fineIndex / 3, anchorShift: Double(fineIndex % 3) / Double(divisor)) }
        return Position(index: fineIndex, anchorShift: 0.0)
    }

    /// Onset strength sampled on a grid ending at `newest` with spacing `step`, oldest first.
    private func gridStrengths(_ onset: [Float], _ length: Int, _ newest: Double, _ step: Double) -> [Double]? {
        let count = Int((newest - step / 2.0) / step) + 1
        if count < minBeats { return nil }
        let window = step / 8.0
        return (0..<count).map { i in windowMax(onset, length, newest - Double(count - 1 - i) * step, window) }
    }

    /// Is the space between tracked beats split in three rather than two? Both thirds must carry
    /// energy: a swung eighth sits at two thirds alone and is duple, not compound.
    private func isCompound(_ onset: [Float], _ length: Int, _ newest: Double, _ period: Double) -> Bool {
        let sub = period / 20.0
        var half = 0.0
        var firstThird = 0.0
        var secondThird = 0.0
        var beat = 0.0
        var t = newest
        while t - period >= 0 {
            let start = t - period
            beat += windowMax(onset, length, start, period / 8.0)
            half += windowMax(onset, length, start + period / 2.0, sub)
            firstThird += windowMax(onset, length, start + period / 3.0, sub)
            secondThird += windowMax(onset, length, start + 2.0 * period / 3.0, sub)
            t -= period
        }
        let third = 0.5 * (firstThird + secondThird)
        let bothThirds = min(firstThird, secondThird) > 0.35 * max(firstThird, secondThird)
        return bothThirds && third > 1.3 * half && third > 0.25 * beat
    }

    private static let compoundPriors: [Int: Double] = [2: 1.00, 3: 0.95, 4: 0.95]

    private func label(_ beats: Int, _ compound: Bool) -> String {
        switch true {
        case compound && beats == 2: return "6/8"
        case compound && beats == 3: return "9/8"
        case compound && beats == 4: return "12/8"
        case beats == 2: return "2/4"
        case beats == 3: return "3/4"
        case beats == 4: return "4/4"
        case beats == 5: return "5/4"
        case beats == 6: return "6/8"
        case beats == 7: return "7/8"
        default: return "\(beats)/4"
        }
    }

    private func windowMax(_ x: [Float], _ length: Int, _ center: Double, _ halfWidth: Double) -> Double {
        let lo = roundToInt(center - halfWidth).coerceAtLeast(0)
        let hi = roundToInt(center + halfWidth).coerceAtMost(length - 1)
        if lo > hi { return 0.0 }
        var m = -Float.infinity
        for i in lo...hi where x[i] > m { m = x[i] }
        return Double(max(0, m))
    }

    private func autocorrelation(_ z: [Double], _ lag: Int) -> Double {
        var num = 0.0
        var den = 0.0
        for i in z.indices { den += z[i] * z[i] }
        if den < 1e-12 { return 0.0 }
        if lag < z.count { for i in lag..<z.count { num += z[i] * z[i - lag] } }
        return num / den * Double(z.count) / Double(z.count - lag)
    }

    private static func dot(_ a: [Double], _ b: [Double]) -> Double {
        var s = 0.0
        for i in a.indices { s += a[i] * b[i] }
        return s
    }

    private static func zScore(_ x: [Double]) -> [Double] {
        // Kotlin's DoubleArray.average(): sum in order, divided by the count.
        var total = 0.0
        for e in x { total += e }
        let mean = total / Double(x.count)
        var v = 0.0
        for e in x { v += (e - mean) * (e - mean) }
        let sd = (v / Double(x.count)).squareRoot()
        return sd < 1e-9 ? [Double](repeating: 0, count: x.count) : x.map { ($0 - mean) / sd }
    }
}

private extension Dictionary {
    func mapValuesWithKeys<T>(_ transform: (Key, Value) -> T) -> [Key: T] {
        var out: [Key: T] = [:]
        out.reserveCapacity(count)
        for (k, v) in self { out[k] = transform(k, v) }
        return out
    }
}
