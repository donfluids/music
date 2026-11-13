import Foundation

// Small helpers that keep the port close to the Kotlin original, so the two read side by side.

/// Kotlin's Math.floorMod: the remainder with the divisor's sign.
@inlinable public func floorMod(_ a: Int, _ b: Int) -> Int { let r = a % b; return (r != 0 && (r < 0) != (b < 0)) ? r + b : r }

/// Kotlin's Math.floorDiv.
@inlinable public func floorDiv(_ a: Int, _ b: Int) -> Int { let q = a / b; return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q }

extension Comparable {
    /// Kotlin's coerceIn.
    @inlinable public func coerceIn(_ lo: Self, _ hi: Self) -> Self { min(max(self, lo), hi) }
    @inlinable public func coerceAtLeast(_ lo: Self) -> Self { max(self, lo) }
    @inlinable public func coerceAtMost(_ hi: Self) -> Self { min(self, hi) }
}

/// Kotlin's roundToInt: ties round toward positive infinity, like Math.round.
@inlinable public func roundToInt(_ x: Double) -> Int { Int((x + 0.5).rounded(.down)) }
@inlinable public func roundToInt(_ x: Float) -> Int { Int((x + 0.5).rounded(.down)) }

/// Kotlin's Math.round: half up (toward positive infinity).
@inlinable public func kotlinRound(_ x: Double) -> Int { Int((x + 0.5).rounded(.down)) }
