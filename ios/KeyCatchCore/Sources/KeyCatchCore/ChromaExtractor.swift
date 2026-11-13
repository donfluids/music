import Foundation

/// Turns a block of audio into a 12-bin pitch-class profile ("chroma") and accumulates it over time.
///
/// Two spectra are used. A long window ([bassFftSize], about 370 ms) resolves the bass, where
/// neighbouring semitones are only a few hertz apart; a shorter one ([fftSize], about 190 ms)
/// follows chord changes above [bassCrossoverHz]. Only spectral peaks are used, with their
/// frequency refined by parabolic interpolation, so a note lands on one pitch class instead of
/// smearing across two bins.
///
/// The same peaks give a tuning estimate: how many cents the singers are sharp or flat of
/// A4 = [referenceHz].
public final class ChromaExtractor {
    private let sampleRate: Int
    public let fftSize: Int
    public let bassFftSize: Int
    private let bassCrossoverHz: Double
    private let referenceHz: Double
    /// Time constant (seconds) of the exponential memory used for key estimation.
    private let memorySeconds: Double
    private let minHz: Double
    private let maxHz: Double
    /// Peaks above this frequency are weighted down in proportion, to tame high overtones.
    private let rollOffHz: Double
    /// Follow the singers' own reference: once the tuning estimate is consistent and clearly
    /// off the grid, move the grid onto it so notes fall into one pitch class instead of two.
    private let adaptiveTuning: Bool

    /// Cents by which the pitch-class grid has been moved from [referenceHz].
    public private(set) var gridOffsetCents: Double = 0.0
    private var framesSinceGridMove = 0
    /// Samples the caller must provide before the analysis point.
    public var windowSamples: Int { max(fftSize, bassFftSize) }

    private final class Spectrum {
        let size: Int
        let fft: Fft
        let window: [Float]
        var re: [Float]
        var im: [Float]
        var mag: [Float]
        let binHz: Double
        private var sortScratch: [Float]

        init(_ size: Int, sampleRate: Int) {
            self.size = size
            fft = Fft(size)
            window = Fft.hannWindow(size)
            re = [Float](repeating: 0, count: size)
            im = [Float](repeating: 0, count: size)
            mag = [Float](repeating: 0, count: size / 2 + 1)
            binHz = Double(sampleRate) / Double(size)
            sortScratch = [Float](repeating: 0, count: size / 2 + 1)
        }

        func compute(_ buffer: [Float], _ end: Int) {
            let start = end - size
            let size = self.size
            buffer.withUnsafeBufferPointer { buf in
                window.withUnsafeBufferPointer { win in
                    re.withUnsafeMutableBufferPointer { re in
                        im.withUnsafeMutableBufferPointer { im in
                            for i in 0..<size { re[i] = buf[start + i] * win[i]; im[i] = 0 }
                            fft.transform(re, im)
                            let scale: Float = 2 / Float(size)
                            mag.withUnsafeMutableBufferPointer { mag in
                                for k in 0...(size / 2) { mag[k] = (re[k] * re[k] + im[k] * im[k]).squareRoot() * scale }
                            }
                        }
                    }
                }
            }
        }

        /// Median magnitude between two bins: a cheap noise-floor estimate.
        func noiseFloor(_ minBin: Int, _ maxBin: Int) -> Float {
            let n = maxBin - minBin + 1
            if n <= 0 { return 0 }
            return mag.withUnsafeBufferPointer { mag in
                sortScratch.withUnsafeMutableBufferPointer { scratch in
                    for i in 0..<n { scratch[i] = mag[minBin + i] }
                    var part = UnsafeMutableBufferPointer(rebasing: scratch[0..<n])
                    part.sort()
                    return part[n / 2]
                }
            }
        }
    }

    private let main: Spectrum
    private let bass: Spectrum

    /// Chroma of the most recent frame only.
    public private(set) var frameChroma = [Float](repeating: 0, count: 12)

    /// Exponentially-decayed chroma used for key finding.
    public private(set) var accumulatedChroma = [Float](repeating: 0, count: 12)

    private var tuningX = 0.0
    private var tuningY = 0.0
    private var frameX = 0.0
    private var frameY = 0.0
    private var frameWeight = 0.0

    /// Sum of frame energy seen so far (decayed). Used to judge whether we have heard anything.
    public private(set) var accumulatedWeight = 0.0

    public init(
        _ sampleRate: Int,
        fftSize: Int = 8192,
        bassFftSize: Int = 16384,
        bassCrossoverHz: Double = 260.0,
        referenceHz: Double = 440.0,
        memorySeconds: Double = 8.0,
        minHz: Double = 55.0,
        maxHz: Double = 3000.0,
        rollOffHz: Double = 1000.0,
        adaptiveTuning: Bool = true
    ) {
        self.sampleRate = sampleRate
        self.fftSize = fftSize
        self.bassFftSize = bassFftSize
        self.bassCrossoverHz = bassCrossoverHz
        self.referenceHz = referenceHz
        self.memorySeconds = memorySeconds
        self.minHz = minHz
        self.maxHz = maxHz
        self.rollOffHz = rollOffHz
        self.adaptiveTuning = adaptiveTuning
        main = Spectrum(fftSize, sampleRate: sampleRate)
        bass = Spectrum(bassFftSize, sampleRate: sampleRate)
    }

    public func reset() {
        for i in 0..<12 { accumulatedChroma[i] = 0; frameChroma[i] = 0 }
        tuningX = 0.0
        tuningY = 0.0
        accumulatedWeight = 0.0
        gridOffsetCents = 0.0
        framesSinceGridMove = 0
    }

    /// Tuning offset of the music relative to the current grid, in cents (-50..50).
    private func offsetFromGridCents() -> Double {
        let r = (tuningX * tuningX + tuningY * tuningY).squareRoot()
        if r < 1e-9 { return 0.0 }
        return atan2(tuningY, tuningX) / (2.0 * Double.pi) * 100.0
    }

    /// Estimated tuning offset relative to [referenceHz], in cents (-50..50). Positive is sharp.
    public func tuningOffsetCents() -> Double {
        var c = gridOffsetCents + offsetFromGridCents()
        while c > 50.0 { c -= 100.0 }
        while c < -50.0 { c += 100.0 }
        return c
    }

    /// How consistent the tuning estimate is, 0..1.
    public func tuningConfidence() -> Double {
        if accumulatedWeight < 1e-9 { return 0.0 }
        return ((tuningX * tuningX + tuningY * tuningY).squareRoot() / accumulatedWeight).coerceIn(0.0, 1.0)
    }

    /// Pitch classes this device is itself sounding (bit n = pitch class n, C = 0). Peaks within
    /// [OWN_TOLERANCE_CENTS] of those notes at concert pitch are turned down and kept out of the
    /// tuning estimate. Set before each [process] call.
    public var ownMask: Int = 0

    /// Analyse the audio ending at index [end] (exclusive) in [buffer]; at least [windowSamples]
    /// samples must precede it. [hopSeconds] is the time since the previous call.
    public func process(_ buffer: [Float], _ end: Int, _ hopSeconds: Double) {
        main.compute(buffer, end)
        bass.compute(buffer, end)

        for i in 0..<12 { frameChroma[i] = 0 }
        frameWeight = 0.0
        frameX = 0.0
        frameY = 0.0
        collectPeaks(bass, minHz, bassCrossoverHz)
        collectPeaks(main, bassCrossoverHz, maxHz)

        let decay = exp(-hopSeconds / memorySeconds)
        for i in 0..<12 { accumulatedChroma[i] = Float(Double(accumulatedChroma[i]) * decay + Double(frameChroma[i])) }
        tuningX = tuningX * decay + frameX
        tuningY = tuningY * decay + frameY
        accumulatedWeight = accumulatedWeight * decay + frameWeight
        framesSinceGridMove += 1
        // Move the grid onto the singers when they are consistently more than 15 cents off it,
        // no more often than every two seconds, so the pitch classes settle rather than hunt.
        if adaptiveTuning && Double(framesSinceGridMove) * hopSeconds > 2.0 && tuningConfidence() > 0.5 {
            let off = offsetFromGridCents()
            if abs(off) > 15.0 {
                gridOffsetCents += off
                while gridOffsetCents > 50.0 { gridOffsetCents -= 100.0 }
                while gridOffsetCents < -50.0 { gridOffsetCents += 100.0 }
                // The tuning vector was measured against the old grid; re-express it against the new one.
                let r = (tuningX * tuningX + tuningY * tuningY).squareRoot()
                tuningX = r; tuningY = 0.0
                framesSinceGridMove = 0
            }
        }
    }

    private func collectPeaks(_ s: Spectrum, _ fromHz: Double, _ toHz: Double) {
        let minBin = Int(fromHz / s.binHz).coerceAtLeast(2)
        let maxBin = Int(toHz / s.binHz).coerceAtMost(s.size / 2 - 2)
        if maxBin <= minBin { return }
        let threshold = s.noiseFloor(minBin, maxBin) * 4
        let binHz = s.binHz
        let referenceHz = self.referenceHz
        let rollOffHz = self.rollOffHz
        let gridOffsetCents = self.gridOffsetCents
        let ownMask = self.ownMask
        var frameWeight = self.frameWeight
        var frameX = self.frameX
        var frameY = self.frameY
        s.mag.withUnsafeBufferPointer { mag in
            frameChroma.withUnsafeMutableBufferPointer { frameChroma in
                for k in minBin...maxBin {
                    let m = mag[k]
                    if m <= threshold || m <= mag[k - 1] || m < mag[k + 1] { continue }
                    // Kotlin's ln(Float) goes through the double-precision log.
                    let a = Float(log(Double(mag[k - 1] + 1e-12 as Float)))
                    let b = Float(log(Double(m + 1e-12 as Float)))
                    let c = Float(log(Double(mag[k + 1] + 1e-12 as Float)))
                    let denom = a - 2 * b + c
                    let delta: Float = (denom.isFinite && denom != 0) ? (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) : 0
                    let hz = Double(Float(k) + delta) * binHz
                    if hz < fromHz || hz >= toHz { continue }
                    let semitone = 12.0 * log2(hz / referenceHz) - gridOffsetCents / 100.0
                    let nearest = roundToInt(semitone)
                    let cents = (semitone - Double(nearest)) * 100.0
                    let pc = floorMod(nearest + 9, 12)
                    let weight = hz <= rollOffHz ? Double(m) : Double(m) * (rollOffHz / hz)
                    if ownMask != 0 {
                        // The device's own notes sit exactly on concert pitch; a singer rarely does.
                        let concert = 12.0 * log2(hz / referenceHz)
                        let concertNearest = roundToInt(concert)
                        let concertPc = floorMod(concertNearest + 9, 12)
                        if ownMask & (1 << concertPc) != 0 && abs(concert - Double(concertNearest)) * 100.0 < ChromaExtractor.OWN_TOLERANCE_CENTS {
                            frameChroma[pc] += Float(weight * ChromaExtractor.OWN_DISCOUNT)
                            frameWeight += weight * ChromaExtractor.OWN_DISCOUNT
                            continue
                        }
                    }
                    frameChroma[pc] += Float(weight)
                    frameWeight += weight
                    let angle = cents / 100.0 * 2.0 * Double.pi
                    frameX += weight * cos(angle)
                    frameY += weight * sin(angle)
                }
            }
        }
        self.frameWeight = frameWeight
        self.frameX = frameX
        self.frameY = frameY
    }

    /// How close to the device's own note, at concert pitch, a peak must be to count as it.
    public static let OWN_TOLERANCE_CENTS = 30.0
    /// What is left of the device's own notes after the discount.
    public static let OWN_DISCOUNT = 0.2
}
