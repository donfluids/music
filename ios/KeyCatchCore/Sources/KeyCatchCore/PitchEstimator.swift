import Foundation

/// A monophonic pitch estimator: the fundamental of the voice at the end of a buffer, or nothing.
public protocol PitchEstimator: AnyObject {
    /// Analyses the audio ending at [end] (exclusive) in [buffer]. Returns true if voiced.
    func process(_ buffer: [Float], end: Int) -> Bool
    var hz: Double { get }
    /// How sure the estimate is, 0..1.
    var clarity: Double { get }
    /// Fractional MIDI note of [hz] against A4 = 440 Hz.
    var midi: Double { get }
    /// Samples that must precede the analysis point.
    var samplesNeeded: Int { get }
}

extension PitchEstimator {
    /// Fractional MIDI note of [hz] against A4 = 440 Hz.
    public var midi: Double { 69.0 + 12.0 * log2(hz / 440.0) }
}
