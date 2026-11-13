import Foundation
import XCTest
@testable import KeyCatchCore

/// java.util.Random, so tests seeded as in Kotlin draw the same numbers.
struct JavaRandom {
    private var seed: UInt64
    private var nextNextGaussian = 0.0
    private var haveNextNextGaussian = false

    init(_ seed: Int64) { self.seed = (UInt64(bitPattern: seed) ^ 0x5DEECE66D) & ((1 << 48) - 1) }

    private mutating func next(_ bits: Int) -> Int32 {
        seed = (seed &* 0x5DEECE66D &+ 0xB) & ((1 << 48) - 1)
        return Int32(truncatingIfNeeded: Int64(bitPattern: seed) >> (48 - bits))
    }

    mutating func nextDouble() -> Double {
        Double((Int64(next(26)) << 27) + Int64(next(27))) * 0x1.0p-53
    }

    mutating func nextGaussian() -> Double {
        if haveNextNextGaussian { haveNextNextGaussian = false; return nextNextGaussian }
        var v1 = 0.0, v2 = 0.0, s = 0.0
        repeat {
            v1 = 2 * nextDouble() - 1
            v2 = 2 * nextDouble() - 1
            s = v1 * v1 + v2 * v2
        } while s >= 1 || s == 0
        let multiplier = (-2 * log(s) / s).squareRoot()
        nextNextGaussian = v2 * multiplier
        haveNextNextGaussian = true
        return v1 * multiplier
    }
}

final class SingerModelTests: XCTestCase {
    func testSoftSharesSplitANoteBetweenTwoKeys() {
        let plain = softShares(60.1, 0.0)
        XCTAssertEqual(plain.count, 1)
        XCTAssertEqual(plain[0].0, 0)
        XCTAssertEqual(plain[0].1, 1.0)
        let split = Dictionary(uniqueKeysWithValues: softShares(60.45, 0.3))
        XCTAssertTrue(split[0]! > split[1]! && split[1]! > 0.3, "\(split)")
        let tight = Dictionary(uniqueKeysWithValues: softShares(60.1, 0.1))
        XCTAssertTrue(tight[0]! > 0.99, "\(tight)")
    }

    func testVibratoAroundANoteBetweenKeysStaysOneNote() {
        // Sung 45 cents above C with 40 cents of vibrato: it crosses into C# and back all the time.
        let seg = NoteSegmenter()
        var t = 0.0
        while t < 1.2 { seg.push(60.45 + 0.4 * sin(2 * Double.pi * 5.5 * t), t); t += 0.046 }
        seg.push(Double.nan, t + 0.2)
        XCTAssertEqual(seg.notes.count, 1, "\(seg.notes)")
        XCTAssertEqual(seg.notes[0].pitch, 60.45, accuracy: 0.1)
    }

    func testASlowSlideLeavesNoPassingNote() {
        // C held, a 300 ms slide to D, D held: two notes, no C# in between.
        let seg = NoteSegmenter()
        var t = 0.0
        while t < 0.6 { seg.push(60.0, t); t += 0.046 }
        let slideStart = t
        while t < slideStart + 0.3 { seg.push(60.0 + 2.0 * (t - slideStart) / 0.3, t); t += 0.046 }
        while t < slideStart + 0.9 { seg.push(62.0, t); t += 0.046 }
        seg.push(Double.nan, t + 0.2)
        XCTAssertEqual(seg.notes.map { $0.midi }, [60, 62], "\(seg.notes)")
    }

    func testTheOffsetFollowsADriftButNotAScatter() {
        // Drifting 60 cents flat over 30 notes, each held 0.6 s: the model should follow.
        let drift = SingerModel()
        var t = 0.0
        for i in 0..<30 {
            let truth = -60.0 * Double(i) / 29
            // The segmenter reports each note relative to the current offset.
            let seen = 60.0 + (truth - drift.offsetCents) / 100.0
            drift.observe(SungNote(pitch: seen, startSeconds: t, durationSeconds: 0.6)); t += 0.7
        }
        XCTAssertTrue(drift.offsetCents < -35.0, "offset \(drift.offsetCents)")
        // The shown tuning follows (the last eight seconds' worth, so it lags the drift a
        // little), and reads as one lean: offset plus leftover, no damping.
        XCTAssertTrue(drift.currentCents < -40.0, "shown \(drift.currentCents)")
        // Notes scattered at random say nothing about direction: the grid stays put.
        var rnd = JavaRandom(3)
        let scatter = SingerModel()
        for i in 0..<40 { scatter.observe(SungNote(pitch: 60.0 + (rnd.nextDouble() - 0.5), startSeconds: Double(i) * 0.7, durationSeconds: 0.6)) }
        // Well short of the 50 cents at which notes would be relabelled.
        XCTAssertTrue(abs(scatter.offsetCents) < 25.0, "offset \(scatter.offsetCents)")
        XCTAssertTrue(scatter.spreadCents > 25.0, "spread \(scatter.spreadCents)")
    }

    func testTheShownTuningIsTheWholeLeanEvenForAScatteredSinger() {
        // 43 cents sharp with 15 cents of scatter: the grid moves slowly, but the number on
        // screen must say about 43 from early on, not a damped fraction of it.
        var rnd = JavaRandom(7)
        let m = SingerModel()
        var t = 0.0
        var shown: [Double] = []
        for i in 0..<40 {
            let truth = 43.0 + 15.0 * rnd.nextGaussian()
            let seen = 60.0 + (truth - m.offsetCents) / 100.0
            m.observe(SungNote(pitch: seen, startSeconds: t, durationSeconds: 0.5)); t += 0.6
            if (6..<20).contains(i) { shown.append(m.currentCents) }
        }
        // Halfway between two keys, the grid may settle on either side (+43 of C or -57 of C#);
        // either way the shown lean is the whole distance from the named key.
        let wrapped = shown.map { v -> Double in let x = v - 43.0 + 50.0; return (x - 100.0 * (x / 100.0).rounded(.down)) - 50.0 }
        XCTAssertEqual(wrapped.reduce(0, +) / Double(wrapped.count), 0.0, accuracy: 8.0, "shown \(shown.map { Int($0) }) offset \(m.offsetCents)")
        XCTAssertTrue(m.offsetCents > -50.0 && m.offsetCents <= 50.0, "grid \(m.offsetCents)")
        // And for a consistent singer the shown value and the grid agree.
        let c = SingerModel()
        t = 0.0
        for _ in 0..<20 { c.observe(SungNote(pitch: 60.0 + (45.0 - c.offsetCents) / 100.0, startSeconds: t, durationSeconds: 0.6)); t += 0.7 }
        XCTAssertEqual(c.currentCents, 45.0, accuracy: 5.0)
    }

    func testASingerConsistentlyFortyFiveCentsSharpIsMet() {
        // Halfway between keys, but always the same way: that is an offset, not carelessness.
        let m = SingerModel()
        var t = 0.0
        for _ in 0..<20 {
            let seen = 60.0 + (45.0 - m.offsetCents) / 100.0
            m.observe(SungNote(pitch: seen, startSeconds: t, durationSeconds: 0.6)); t += 0.7
        }
        XCTAssertEqual(m.offsetCents, 45.0, accuracy: 8.0)
    }

    func testJavaRandomMatchesTheJvm() {
        // java.util.Random(42): nextDouble() = 0.7275636800328681, then nextGaussian() = 1.1419053154730547
        // (values from the JDK), so the seeded tests above see the same numbers as in Kotlin.
        var r = JavaRandom(42)
        XCTAssertEqual(r.nextDouble(), 0.7275636800328681, accuracy: 1e-15)
        var g = JavaRandom(42)
        XCTAssertEqual(g.nextGaussian(), 1.1419053154730547, accuracy: 1e-12)
    }
}
