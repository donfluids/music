import Foundation

/// Spectral-flux onset strength: how much louder each frequency bin got since the last frame.
/// Produces one number per hop. Larger means "something just started" (a drum hit, a new
/// syllable, a chord change). The tempo and meter trackers work entirely from this signal.
public final class OnsetDetector {
    public let fftSize: Int
    /// Log-compression strength. Scale-free-ish thanks to downstream normalisation.
    private let gamma: Float
    /// Onsets below `lowBandMaxHz` get this much extra weight (kick, bass). 0 disables.
    private let lowBandWeight: Float
    /// Bins on either side taken into the maximum of the previous frame before differencing
    /// (SuperFlux, Böck & Widmer 2013). A note wobbling by vibrato then no longer counts as a
    /// stream of onsets. 0 disables.
    private let vibratoBins: Int

    private let fft: Fft
    // Per-frame working storage, allocated once (the Kotlin preallocates FloatArrays).
    private let window: UnsafeMutableBufferPointer<Float>
    private let re: UnsafeMutableBufferPointer<Float>
    private let im: UnsafeMutableBufferPointer<Float>
    private let current: UnsafeMutableBufferPointer<Float>
    private let previous: UnsafeMutableBufferPointer<Float>
    private let previousMax: UnsafeMutableBufferPointer<Float>
    private let binHz: Double
    private let minBin: Int
    private let maxBin: Int
    private let lowBandMaxBin: Int
    private var hasPrevious = false

    /// - Parameters:
    ///   - gamma: log-compression strength. Scale-free-ish thanks to downstream normalisation.
    ///   - lowBandMaxHz: onsets below this frequency get `lowBandWeight` extra weight (kick, bass). 0 disables.
    ///   - vibratoBins: bins on either side taken into the maximum of the previous frame before
    ///     differencing (SuperFlux, Böck & Widmer 2013). 0 disables.
    public init(
        _ sampleRate: Int,
        fftSize: Int = 2048,
        minHz: Double = 30.0,
        maxHz: Double = 8000.0,
        gamma: Float = 20,
        lowBandMaxHz: Double = 0.0,
        lowBandWeight: Float = 0,
        vibratoBins: Int = 2
    ) {
        self.fftSize = fftSize
        self.gamma = gamma
        self.lowBandWeight = lowBandWeight
        self.vibratoBins = vibratoBins
        fft = Fft(fftSize)
        window = .allocate(capacity: fftSize)
        _ = window.initialize(from: Fft.hannWindow(fftSize))
        re = .allocate(capacity: fftSize)
        re.initialize(repeating: 0)
        im = .allocate(capacity: fftSize)
        im.initialize(repeating: 0)
        current = .allocate(capacity: fftSize / 2 + 1)
        current.initialize(repeating: 0)
        previous = .allocate(capacity: fftSize / 2 + 1)
        previous.initialize(repeating: 0)
        previousMax = .allocate(capacity: fftSize / 2 + 1)
        previousMax.initialize(repeating: 0)
        binHz = Double(sampleRate) / Double(fftSize)
        let minBin = Int(minHz / binHz).coerceAtLeast(1)
        let maxBin = Int(maxHz / binHz).coerceAtMost(fftSize / 2)
        self.minBin = minBin
        self.maxBin = maxBin
        lowBandMaxBin = lowBandMaxHz > 0 ? Int(lowBandMaxHz / binHz).coerceIn(minBin, maxBin) : minBin - 1
    }

    deinit {
        window.deallocate()
        re.deallocate()
        im.deallocate()
        current.deallocate()
        previous.deallocate()
        previousMax.deallocate()
    }

    public func reset() {
        previous.update(repeating: 0)
        hasPrevious = false
    }

    /// Returns the onset strength for the `fftSize` samples at `offset` in `buffer`.
    public func process(_ buffer: [Float], _ offset: Int) -> Float {
        precondition(offset >= 0 && offset + fftSize <= buffer.count)
        buffer.withUnsafeBufferPointer { buffer in
            for i in 0..<fftSize {
                re[i] = buffer[offset + i] * window[i]
                im[i] = 0
            }
        }
        fft.transform(re, im)
        let scale: Float = 2 / Float(fftSize)
        // ln1p in Double then back to Float, as kotlin.math.ln1p(Float) does.
        for k in minBin...maxBin { current[k] = Float(log1p(Double(gamma * (re[k] * re[k] + im[k] * im[k]).squareRoot() * scale))) }
        // Widen the previous frame's peaks so small pitch drift does not look like a new note.
        for k in minBin...maxBin {
            var m = previous[k]
            for j in max(minBin, k - vibratoBins)...min(maxBin, k + vibratoBins) where previous[j] > m { m = previous[j] }
            previousMax[k] = m
        }
        var flux: Float = 0
        for k in minBin...maxBin {
            let d = current[k] - previousMax[k]
            if d > 0 { flux += k <= lowBandMaxBin ? d * (1 + lowBandWeight) : d }
        }
        let result: Float = hasPrevious ? flux : 0
        for k in minBin...maxBin { previous[k] = current[k] }
        hasPrevious = true
        return result
    }
}
