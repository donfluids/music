import Foundation

/// The CREPE network itself: on iPhone it runs through Core ML.
public protocol CrepeNetwork: AnyObject {
    /// [frame16k] is [CrepePitch.FRAME] (1024) samples at 16 kHz, already normalised to zero mean
    /// and unit variance (see CrepePitch's normalise). Returns the [CrepePitch.BINS] (360) sigmoid
    /// activations, one per 20 cents from C1 to B7.
    func activations(frame16k: [Float]) -> [Float]
}

/// CREPE pitch estimation (Kim, Salamon, Li and Bello, ICASSP 2018; MIT licence), "tiny" size.
/// The network runs behind [CrepeNetwork] (Core ML on the phone); everything around it is here.
///
/// The newest 64 ms are resampled to 16 kHz, normalised, and passed through the network to 360
/// activations, one per 20 cents from C1 to B7. The pitch is the activation-weighted
/// average of the bins around the strongest, and the strongest activation is the confidence.
///
/// On 40 solo singers (Vocadito) it found the right note in 95% of sung frames against 94% for
/// the autocorrelation tracker, 93% against 87% with noise at 10 dB SNR, and made a third as many
/// octave errors with a band behind the singer. Its estimate is a few cents coarser, so when
/// [refine] is on, the autocorrelation peak nearest its answer sets the exact frequency; and a
/// frame counts as voiced only when that peak shows real periodicity, which keeps a chord on a
/// keyboard from being read as a tune.
public final class CrepePitch: PitchEstimator {
    public static let FRAME = 1024
    public static let BINS = 360
    public static func centsOf(_ bin: Int) -> Double { 1997.3794084376191 + 7180.0 * Double(bin) / Double(BINS - 1) }

    private let network: CrepeNetwork
    private let sampleRate: Int
    private let minConfidence: Double
    private let minLevel: Double
    private let refine: Bool
    /// Voiced only when the sound is also this periodic at CREPE's pitch (0 turns the check off).
    private let minPeriodicity: Double

    public private(set) var hz: Double = 0.0
    public private(set) var clarity: Double = 0.0

    /// The 360 activations of the last voiced or unvoiced frame that passed the level gate.
    public private(set) var activations = [Float](repeating: 0, count: CrepePitch.BINS)

    /// Milliseconds the last [process] call took, for the on-screen diagnostics.
    public private(set) var lastMillis: Double = 0.0

    // ---- input: the level gate and the resampler -------------------------------------------

    private let levelFrame = 2048
    private let levelWindow: [Float]
    private let ratio: Double
    private let cutoff: Double                 // cycles per input sample
    private let halfTaps: Int
    // Output sample j sits (FRAME - 1 - j) * ratio input samples before the newest one, plus a
    // delay of halfTaps + 1 so its filter never needs a sample that has not arrived. Its taps
    // (offsets from the newest sample, all <= 0) and their weights never change.
    private let tapStart: [Int]
    private let tapWeights: [[Float]]
    public let samplesNeeded: Int

    private var frame = [Float](repeating: 0, count: CrepePitch.FRAME)

    // ---- refinement and the periodicity check ---------------------------------------------

    private let refineFrame = 2048
    private let refineWindow: [Float]
    private var rx: [Double]
    /// The Hann window's own autocorrelation, normalised: what a taper alone does to each lag.
    private let windowAcf: [Double]
    /// Input samples from the newest one back to the centre of CREPE's window.
    private let crepeCentreBack: Int

    /// How periodic the audio is at the last voiced pitch, 0..1 (the autocorrelation peak).
    public private(set) var periodicity: Double = 0.0

    public init(
        _ network: CrepeNetwork,
        _ sampleRate: Int,
        minConfidence: Double = 0.5,
        minLevel: Double = 0.004,
        refine: Bool = true,
        minPeriodicity: Double = 0.5
    ) {
        precondition(sampleRate >= 16000, "CREPE needs at least 16 kHz audio")
        self.network = network
        self.sampleRate = sampleRate
        self.minConfidence = minConfidence
        self.minLevel = minLevel
        self.refine = refine
        self.minPeriodicity = minPeriodicity

        levelWindow = Fft.hannWindow(levelFrame)
        let ratio = Double(sampleRate) / 16000.0
        let cutoff = 7400.0 / Double(sampleRate)
        let halfTaps = Int(16 * ratio) + 1
        self.ratio = ratio
        self.cutoff = cutoff
        self.halfTaps = halfTaps
        let FRAME = CrepePitch.FRAME
        var tapStart = [Int](repeating: 0, count: FRAME)
        var tapWeights: [[Float]] = []
        tapWeights.reserveCapacity(FRAME)
        for j in 0..<FRAME {
            let back = Double(FRAME - 1 - j) * ratio + Double(halfTaps) + 1
            let first = Int((-back - Double(halfTaps)).rounded(.down))
            tapStart[j] = first
            tapWeights.append((0..<(2 * halfTaps + 2)).map { k -> Float in
                let t = Double(first + k) + back                       // input offset from the ideal point
                let u = t / Double(halfTaps + 1)
                if abs(u) >= 1.0 { return 0 }
                let window = 0.42 + 0.5 * cos(Double.pi * u) + 0.08 * cos(2 * Double.pi * u)
                let x = 2.0 * cutoff * t
                let sinc = abs(x) < 1e-9 ? 1.0 : sin(Double.pi * x) / (Double.pi * x)
                return Float(2.0 * cutoff * sinc * window)
            })
        }
        self.tapStart = tapStart
        self.tapWeights = tapWeights
        samplesNeeded = max(levelFrame, Int(Double(FRAME - 1) * ratio) + 2 * halfTaps + 6, 2048 + 1500)

        let refineFrame = self.refineFrame
        let refineWindow = Fft.hannWindow(refineFrame)
        self.refineWindow = refineWindow
        rx = [Double](repeating: 0, count: refineFrame)
        var rw = [Double](repeating: 0, count: refineFrame / 2 + 2)
        for lag in 0..<rw.count { var s = 0.0; for i in 0..<(refineFrame - lag) { s += Double(refineWindow[i]) * Double(refineWindow[i + lag]) }; rw[lag] = s }
        let zero = rw[0]
        for lag in 0..<rw.count { rw[lag] /= zero }
        windowAcf = rw
        crepeCentreBack = Int(Double(FRAME - 1) / 2.0 * ratio + Double(halfTaps) + 1)
    }

    public func process(_ buffer: [Float], end: Int) -> Bool {
        let started = DispatchTime.now().uptimeNanoseconds
        hz = 0.0
        clarity = 0.0
        // The same loudness gate as the autocorrelation tracker, so silence costs nothing.
        var energy = 0.0
        let gateStart = end - levelFrame
        for k in 0..<levelFrame { let v = buffer[gateStart + k] * levelWindow[k]; energy += Double(v) * Double(v) }
        if (energy / Double(levelFrame)).squareRoot() < minLevel { lastMillis = Double(DispatchTime.now().uptimeNanoseconds - started) / 1e6; return false }

        resample(buffer, end)
        normalise()
        forward()
        decode()
        var voiced = clarity >= minConfidence
        if voiced && (refine || minPeriodicity > 0.0) {
            let refined = refineWithAutocorrelation(buffer, end, hz)
            if refine { hz = refined }
            if periodicity < minPeriodicity { voiced = false }
        }
        lastMillis = Double(DispatchTime.now().uptimeNanoseconds - started) / 1e6
        return voiced
    }

    private func resample(_ buffer: [Float], _ end: Int) {
        let newest = end - 1
        buffer.withUnsafeBufferPointer { bp in
            frame.withUnsafeMutableBufferPointer { fp in
                for j in 0..<CrepePitch.FRAME {
                    tapWeights[j].withUnsafeBufferPointer { w in
                        let base = newest + tapStart[j]
                        var s: Float = 0
                        for k in 0..<w.count {
                            let idx = base + k
                            if idx >= 0 && idx <= newest { s += bp[idx] * w[k] }
                        }
                        fp[j] = s
                    }
                }
            }
        }
    }

    /// The network alone, on a 16 kHz frame, then decoded into [hz] and [clarity]: a test hook.
    func activationsOf(frame16k: [Float]) -> [Float] {
        for k in 0..<CrepePitch.FRAME { frame[k] = frame16k[k] }
        normalise()
        forward()
        decode()
        return activations
    }

    private func normalise() {
        let FRAME = CrepePitch.FRAME
        var mean = 0.0
        for v in frame { mean += Double(v) }
        mean /= Double(FRAME)
        var variance = 0.0
        for v in frame { variance += (Double(v) - mean) * (Double(v) - mean) }
        let sd = max((variance / Double(FRAME)).squareRoot(), 1e-8)
        for k in 0..<FRAME { frame[k] = Float((Double(frame[k]) - mean) / sd) }
    }

    /// Runs the network on the normalised [frame] into [activations].
    private func forward() {
        let out = network.activations(frame16k: frame)
        precondition(out.count == CrepePitch.BINS, "expected \(CrepePitch.BINS) pitch bins, got \(out.count)")
        activations = out
    }

    private func decode() {
        let BINS = CrepePitch.BINS
        var best = 0
        for k in 1..<BINS where activations[k] > activations[best] { best = k }
        let from = max(0, best - 4); let to = min(BINS, best + 5)
        var sum = 0.0; var weight = 0.0
        for k in from..<to { sum += Double(activations[k]) * CrepePitch.centsOf(k); weight += Double(activations[k]) }
        let cents = sum / weight
        hz = 10.0 * pow(2.0, cents / 1200.0)
        clarity = Double(activations[best])
    }

    /// Kotlin's Double.toInt(): NaN is 0 and out-of-range values saturate.
    private static func kotlinToInt(_ x: Double) -> Int {
        if x.isNaN { return 0 }
        if x >= Double(Int.max) { return Int.max }
        if x <= Double(Int.min) { return Int.min }
        return Int(x)
    }

    /// CREPE picks the note; the autocorrelation peak within 60 cents of its period sets the
    /// exact frequency and says how periodic the sound really is there. A chord has no single
    /// period, so it scores low even where CREPE confidently names one of its notes.
    ///
    /// The frame is Hann-windowed and centred where CREPE's window is centred, and its
    /// autocorrelation is divided by the window's own (Boersma 1993): without that, the taper
    /// pulls long lags sharp, 15 cents at 87 Hz.
    private func refineWithAutocorrelation(_ buffer: [Float], _ end: Int, _ estimate: Double) -> Double {
        periodicity = 0.0
        let refineFrame = self.refineFrame
        let start = end - crepeCentreBack - refineFrame / 2
        if start < 0 { return estimate }
        var mean = 0.0
        for i in 0..<refineFrame { mean += Double(buffer[start + i]) }
        mean /= Double(refineFrame)
        for i in 0..<refineFrame { rx[i] = (Double(buffer[start + i]) - mean) * Double(refineWindow[i]) }
        var r0 = 0.0
        for v in rx { r0 += v * v }
        if r0 <= 0.0 { return estimate }
        let target = Double(sampleRate) / estimate
        let lo = max(2, CrepePitch.kotlinToInt((target * pow(2.0, -60.0 / 1200)).rounded(.down)))
        let hi = min(refineFrame / 2 - 1, CrepePitch.kotlinToInt((target * pow(2.0, 60.0 / 1200)).rounded(.up)))
        if hi <= lo { return estimate }
        let windowAcf = self.windowAcf
        let values: [Double] = rx.withUnsafeBufferPointer { xp in
            func acf(_ lag: Int) -> Double {
                var s = 0.0
                for i in 0..<(refineFrame - lag) { s += xp[i] * xp[i + lag] }
                return s / r0 / windowAcf[lag]
            }
            return (0..<(hi - lo + 3)).map { acf(lo - 1 + $0) }   // lags lo-1 .. hi+1
        }
        var best = 1
        for k in 2...(hi - lo + 1) where values[k] > values[best] { best = k }
        let b = values[best]
        periodicity = b.coerceIn(0.0, 1.0)
        if b < 0.5 || b < values[best - 1] || b < values[best + 1] { return estimate }
        let a = values[best - 1]; let c = values[best + 1]
        let den = a - 2 * b + c
        let delta = den != 0.0 ? (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) : 0.0
        return Double(sampleRate) / (Double(lo - 1 + best) + delta)
    }
}
