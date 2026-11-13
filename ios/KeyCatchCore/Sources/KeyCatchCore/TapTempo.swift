import Foundation

/// Tempo from taps. The tempo is the median gap between the last few taps, so one tap that
/// comes early or late does not throw it; a pause starts a new count, and a tap that follows the
/// last one almost at once (a finger bouncing) is ignored.
public final class TapTempo {
    private let maxTaps: Int
    private let resetNanos: Int
    private let bounceNanos: Int
    private var taps: [Int] = []

    public struct Reading: Hashable, Sendable {
        public let bpm: Float
        public let lastTapNanos: Int
        public let taps: Int
        public init(bpm: Float, lastTapNanos: Int, taps: Int) { self.bpm = bpm; self.lastTapNanos = lastTapNanos; self.taps = taps }
    }

    public init(maxTaps: Int = 8, resetNanos: Int = 2_500_000_000, bounceNanos: Int = 150_000_000) {
        self.maxTaps = maxTaps
        self.resetNanos = resetNanos
        self.bounceNanos = bounceNanos
    }

    /// Records a tap at `nowNanos`; the reading once there are two taps, else nil.
    @discardableResult
    public func tap(_ nowNanos: Int) -> Reading? {
        if let last = taps.last {
            let gap = nowNanos - last
            if gap >= 0 && gap < bounceNanos { return reading() }
            if gap > resetNanos || gap < 0 { taps.removeAll() }
        }
        taps.append(nowNanos)
        while taps.count > maxTaps { taps.removeFirst() }
        return reading()
    }

    private func reading() -> Reading? {
        if taps.count < 2 { return nil }
        let gaps = zip(taps, taps.dropFirst()).map { a, b in Double(b - a) }.sorted()
        let median = gaps.count % 2 == 1 ? gaps[gaps.count / 2] : (gaps[gaps.count / 2 - 1] + gaps[gaps.count / 2]) / 2
        return Reading(bpm: Float(60e9 / median).coerceIn(30, 300), lastTapNanos: taps.last!, taps: taps.count)
    }

    public func clear() { taps.removeAll() }
}
