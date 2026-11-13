import Foundation

/// Estimates tempo and beat phase from a stream of onset-strength values.
///
/// Method (after Ellis, "Beat Tracking by Dynamic Programming", 2007, simplified):
///  1. keep the last few seconds of onset strength,
///  2. remove the slow trend so only the rhythmic part remains,
///  3. autocorrelate over the plausible range of beat periods,
///  4. weight by a log-normal tempo prior (people mostly clap between 60 and 160 BPM),
///  5. pick the best period, then find the phase that lines beats up with strong onsets.
public final class TempoTracker {
    public final class Estimate {
        public let bpm: Double
        /// Beat period in onset frames (fractional).
        public let periodFrames: Double
        /// How many frames before the newest frame the last beat fell.
        public let lastBeatFramesAgo: Double
        /// Normalised autocorrelation at the chosen period, roughly 0..1.
        public let strength: Double
        /// True when the last few estimates agree closely.
        public let locked: Bool

        public init(bpm: Double, periodFrames: Double, lastBeatFramesAgo: Double, strength: Double, locked: Bool) {
            self.bpm = bpm
            self.periodFrames = periodFrames
            self.lastBeatFramesAgo = lastBeatFramesAgo
            self.strength = strength
            self.locked = locked
        }
    }

    /// One shortlisted candidate from the last estimate, for diagnostics and tests.
    public final class Candidate {
        public let lag: Int
        public let bpm: Double
        public let acf: Double
        public let prior: Double
        public let score: Double
        public let salience: Double
        public let finalScore: Double

        public init(_ lag: Int, _ bpm: Double, _ acf: Double, _ prior: Double, _ score: Double, _ salience: Double, _ finalScore: Double) {
            self.lag = lag
            self.bpm = bpm
            self.acf = acf
            self.prior = prior
            self.score = score
            self.salience = salience
            self.finalScore = finalScore
        }
    }

    /// Onset frames per second (sample rate / hop size).
    public let framesPerSecond: Double
    private let priorCenterBpm: Double
    private let priorWidthOctaves: Double
    private let smoothingEstimates: Int

    private let capacity: Int
    private var ring: [Float]
    private var head = 0
    private var count = 0

    private let minLag: Int
    private let maxLag: Int
    /// Half-width of the moving average removed before autocorrelation; wide enough not to erase slow beats.
    private let trendHalfWidth: Int

    private var linear: [Float]
    /// Detrended onset history in time order (oldest first); valid up to `detrendedLength`.
    public private(set) var detrended: [Float]
    public private(set) var detrendedLength = 0
    // The autocorrelation runs to twice the slowest lag so every candidate can be checked
    // against its double; otherwise slow tempos are structurally disadvantaged.
    private var acf: [Double]
    private var score: [Double]
    private var lockedPeriod = 0.0
    private var octaveChallenges = 0
    /// Consecutive estimates an octave-related alternative must win before the tempo switches.
    private let octaveSwitchEstimates = 4
    private let prior: [Double]
    private var recentPeriods: [Double] = []

    /// Shortlist of the last estimate, best final score first.
    public private(set) var lastCandidates: [Candidate] = []

    /// Seconds of onset history currently held.
    public var secondsBuffered: Double { Double(count) / framesPerSecond }

    public init(
        _ framesPerSecond: Double,
        historySeconds: Double = 8.0,
        minBpm: Double = 40.0,
        maxBpm: Double = 220.0,
        priorCenterBpm: Double = 120.0,
        priorWidthOctaves: Double = 0.55,
        smoothingEstimates: Int = 5
    ) {
        self.framesPerSecond = framesPerSecond
        self.priorCenterBpm = priorCenterBpm
        self.priorWidthOctaves = priorWidthOctaves
        self.smoothingEstimates = smoothingEstimates
        let capacity = roundToInt(historySeconds * framesPerSecond)
        self.capacity = capacity
        ring = [Float](repeating: 0, count: capacity)
        minLag = max(2, Int(floor(framesPerSecond * 60.0 / maxBpm)))
        let maxLag = Int(ceil(framesPerSecond * 60.0 / minBpm)).coerceAtMost(capacity / 2)
        self.maxLag = maxLag
        trendHalfWidth = roundToInt(0.75 * framesPerSecond).coerceAtLeast(1)
        linear = [Float](repeating: 0, count: capacity)
        detrended = [Float](repeating: 0, count: capacity)
        acf = [Double](repeating: 0, count: 2 * maxLag + 1)
        score = [Double](repeating: 0, count: maxLag + 1)
        prior = (0...maxLag).map { lag in
            if lag == 0 { return 0.0 }
            let bpm = framesPerSecond * 60.0 / Double(lag)
            let octaves = log(bpm / priorCenterBpm) / log(2.0)
            return exp(-0.5 * (octaves / priorWidthOctaves) * (octaves / priorWidthOctaves))
        }
    }

    public func reset() {
        head = 0
        count = 0
        detrendedLength = 0
        recentPeriods.removeAll()
        lockedPeriod = 0.0
        octaveChallenges = 0
    }

    /// Forget the locked tempo, e.g. after a stretch of silence; hysteresis restarts at the next lock.
    public func releaseLock() {
        lockedPeriod = 0.0
        octaveChallenges = 0
        recentPeriods.removeAll()
    }

    public func push(_ onsetStrength: Float) {
        ring[head] = onsetStrength
        head = (head + 1) % capacity
        if count < capacity { count += 1 }
    }

    /// Returns nil until at least about three seconds of onsets have been heard.
    public func estimate() -> Estimate? {
        let n = count
        if Double(n) < 3.0 * framesPerSecond || n <= 2 * minLag { return nil }

        // Unroll the ring into time order, smoothing each onset over three frames so a beat that
        // falls between two frames still correlates with itself at the next beat.
        let start = (head - n + capacity) % capacity
        for i in 0..<n {
            let a = ring[(start + max(0, i - 1)) % capacity]
            let b = ring[(start + i) % capacity]
            let c = ring[(start + min(n - 1, i + 1)) % capacity]
            linear[i] = 0.25 * a + 0.5 * b + 0.25 * c
        }

        // Remove the slow trend (moving average) so the autocorrelation sees rhythm, not loudness.
        var sum = 0.0
        var lo = 0
        var hi = -1
        for i in 0..<n {
            let newHi = min(n - 1, i + trendHalfWidth)
            while hi < newHi { hi += 1; sum += Double(linear[hi]) }
            let newLo = max(0, i - trendHalfWidth)
            while lo < newLo { sum -= Double(linear[lo]); lo += 1 }
            detrended[i] = linear[i] - Float(sum / Double(hi - lo + 1))
        }
        detrendedLength = n

        // Autocorrelation over the candidate lags and their doubles.
        let usableMaxLag = min(maxLag, n / 2)
        let acfMaxLag = min(2 * maxLag, n / 2)
        var r0 = 0.0
        for i in 0..<n { r0 += Double(detrended[i]) * Double(detrended[i]) }
        if r0 < 1e-9 { return nil }
        detrended.withUnsafeBufferPointer { d in
            for lag in 0...acfMaxLag {
                var s = 0.0
                for i in lag..<n { s += Double(d[i]) * Double(d[i - lag]) }
                acf[lag] = s / Double(n - lag) / (r0 / Double(n))
            }
        }

        // Score = prior * acf(lag) * (1 + half the acf at twice the lag). A candidate has to be
        // periodic in its own right; a periodic double then strengthens it, as a real beat's
        // bar level does, but cannot rescue a lag that is not itself a pulse.
        for lag in 0...usableMaxLag {
            let own = acf[lag].coerceAtLeast(0.0)
            let double = 2 * lag <= acfMaxLag ? acf[2 * lag].coerceAtLeast(0.0) : 0.0
            score[lag] = prior[lag] * own * (1.0 + 0.5 * double)
        }
        // Candidate periods are the local maxima of the score. Re-rank the strongest few by
        // beat salience: how loud the onsets are when sampled on that period's best-phase grid.
        // This separates a 6/8 dotted-quarter pulse from a pair of its eighths, which have
        // similar autocorrelation but very different loudness on the grid.
        var peaks: [Int] = []
        if minLag <= usableMaxLag - 1 {
            for lag in minLag...(usableMaxLag - 1) {
                if score[lag] >= score[lag - 1] && score[lag] > score[lag + 1] && score[lag] > 0 { peaks.append(lag) }
            }
        }
        if peaks.isEmpty { return nil }
        // Stable sort, best score first, as Kotlin's sortByDescending.
        peaks = peaks.enumerated().sorted { x, y in
            score[x.element] != score[y.element] ? score[x.element] > score[y.element] : x.offset < y.offset
        }.map { $0.element }
        let shortlist = Array(peaks.prefix(5))
        var maxSalience = 0.0
        let salience: [Double] = shortlist.map { lag in
            let s = phaseAlignedMean(n, Double(lag))
            if s > maxSalience { maxSalience = s }
            return s
        }
        var best = -1
        var bestScore = -Double.infinity
        var candidates: [Candidate] = []
        candidates.reserveCapacity(shortlist.count)
        for (i, lag) in shortlist.enumerated() {
            let s = score[lag] * (maxSalience > 0 ? salience[i] / maxSalience : 1.0)
            candidates.append(Candidate(lag, framesPerSecond * 60.0 / Double(lag), acf[lag], prior[lag], score[lag], salience[i], s))
            if s > bestScore { bestScore = s; best = lag }
        }
        lastCandidates = candidates.enumerated().sorted { x, y in
            x.element.finalScore != y.element.finalScore ? x.element.finalScore > y.element.finalScore : x.offset < y.offset
        }.map { $0.element }
        if best < 0 { return nil }

        // Octave hysteresis: once locked, a double, half, triple or third of the current
        // period has to keep winning for a while before the tempo is allowed to jump.
        if lockedPeriod > 0.0 {
            let ratio = Double(best) / lockedPeriod
            let related = TempoTracker.octaveRatios.contains { abs(ratio / $0 - 1.0) < 0.06 }
            let same = abs(ratio - 1.0) < 0.06
            if same {
                octaveChallenges = 0
            } else if related {
                let familiar = shortlist.first { abs(Double($0) / lockedPeriod - 1.0) < 0.06 }
                var keep = false
                if let familiar {
                    octaveChallenges += 1
                    if octaveChallenges < octaveSwitchEstimates { best = familiar; keep = true }
                }
                if !keep {
                    // The alternative has won long enough, or the old pulse has faded from the
                    // shortlist: let it through and start over; the new tempo must lock afresh.
                    releaseLock()
                }
            }
        }

        // Parabolic interpolation of the peak for a fractional period.
        let a = score[best - 1]
        let b = score[best]
        let c = score[best + 1]
        let denom = a - 2 * b + c
        let delta = denom != 0.0 ? (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) : 0.0
        let rawPeriod = Double(best) + delta

        recentPeriods.append(rawPeriod)
        while recentPeriods.count > smoothingEstimates { recentPeriods.removeFirst() }
        let sorted = recentPeriods.sorted()
        let period = sorted[sorted.count / 2]
        let locked = recentPeriods.count >= smoothingEstimates && sorted.last! / sorted.first! < 1.04
        if locked { lockedPeriod = period }

        let strength = acf[best].coerceIn(0.0, 1.0)
        let phase = bestPhase(n, period)
        return Estimate(
            bpm: framesPerSecond * 60.0 / period,
            periodFrames: period,
            lastBeatFramesAgo: phase,
            strength: strength,
            locked: locked
        )
    }

    private static let octaveRatios: [Double] = [2.0, 0.5, 3.0, 1.0 / 3.0, 1.5, 2.0 / 3.0]

    /// Among all offsets within one period of the newest frame, find the one where beats spaced
    /// `period` apart line up with the strongest (positive) onsets over the last eight beats.
    private func bestPhase(_ n: Int, _ period: Double) -> Double { phaseSearch(n, period).0 }

    /// Mean onset strength per beat on the best-phase grid for `period`.
    private func phaseAlignedMean(_ n: Int, _ period: Double) -> Double { phaseSearch(n, period).1 }

    private func phaseSearch(_ n: Int, _ period: Double) -> (Double, Double) {
        let maxPhase = Int(ceil(period))
        let beats = min(8, Int(floor(Double(n - 1) / period)))
        if beats <= 0 { return (0.0, 0.0) }
        var bestPhase = 0
        var bestSum = -Double.infinity
        var bestCount = 1
        detrended.withUnsafeBufferPointer { detrended in
            for phase in 0..<maxPhase {
                var s = 0.0
                var used = 0
                for k in 0..<beats {
                    let idx = roundToInt(Double(n - 1 - phase) - Double(k) * period)
                    if idx < 0 { break }
                    // Tolerate one frame of jitter on either side.
                    var v = detrended[idx]
                    if idx > 0 { v = max(v, detrended[idx - 1]) }
                    if idx < n - 1 { v = max(v, detrended[idx + 1]) }
                    // Squared, so accented beats count for much more than passing notes: the log
                    // compression in the onset detector otherwise leaves too little contrast.
                    let p = Double(max(0, v))
                    s += p * p
                    used += 1
                }
                if s > bestSum { bestSum = s; bestPhase = phase; bestCount = max(1, used) }
            }
        }
        return (Double(bestPhase), bestSum / Double(bestCount))
    }
}
