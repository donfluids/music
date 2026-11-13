import Foundation
import XCTest
@testable import KeyCatchCore

/// Untrained singers wander between neighbouring keys. The app shows the centre (the key that
/// keeps a keyboard closest to them overall) and where they are now.
final class WanderingSingersTests: XCTestCase {
    private let rate = Synth.sampleRate
    private static let tune: [(Int, Int)] = [(55, 1), (60, 2), (64, 1), (60, 1), (64, 2), (62, 1), (60, 2), (57, 1), (55, 3),
        (55, 1), (60, 2), (64, 1), (60, 1), (64, 2), (62, 1), (67, 3), (64, 1), (67, 2), (64, 1), (60, 1),
        (64, 2), (62, 1), (60, 2), (57, 1), (55, 2), (55, 1), (60, 2), (64, 1), (60, 1), (64, 2), (62, 1), (60, 4)]
    private var tune: [(Int, Int)] { Self.tune }
    private static let beat = 0.45
    private var beat: Double { Self.beat }
    private static let verses = 3
    private static let total = Double(verses * tune.reduce(0) { $0 + $1.1 }) * beat + 1.0
    private var total: Double { Self.total }
    private let c = Key(0, .major)

    private func sing(_ level: (Double) -> Double, _ errCents: Double, seed: Int64 = 1) -> [Float] {
        var rnd = JavaRandom(seed)
        let r = Double(rate)
        let song = (1...Self.verses).flatMap { _ in tune }
        var out = [Float](repeating: 0, count: Int(total * r))
        var t0 = 0.3; var phase = 0.0
        for (midi, beats) in song {
            let len = Double(beats) * beat; let e = rnd.nextGaussian() * errCents; let n = Int(len * r)
            for i in 0..<n {
                let t = Double(i) / r; let at = t0 + t
                let vib = t > 0.25 ? 25.0 * sin(2 * Double.pi * 5.3 * t) : 0.0
                phase += 2 * Double.pi * 440.0 * pow(2.0, ((Double(midi) * 100.0 + e + level(at) + vib) / 100.0 - 69) / 12.0) / r
                let env = min(1.0, t / 0.03) * (beats >= 3 ? min(1.0, Double(n - i) / r / 0.15) : 1.0)
                let idx = Int(at * r)
                if idx < out.count {
                    let s: Double = sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase)
                    out[idx] += Float(0.18 * env * s)
                }
            }
            t0 += len
        }
        return out
    }

    private func hear(_ audio: [Float]) -> AnalysisResult {
        let a = MusicAnalyzer(sampleRate: rate, preset: .voices)
        var i = 0
        while i < audio.count {
            let n = min(1024, audio.count - i)
            a.process(Array(audio[i..<(i + n)]), count: n, nowNanos: Int(Double(i + n) / Double(rate) * 1e9))
            i += n
        }
        return a.result
    }

    private func describe(_ r: AnalysisResult) -> String {
        "centre \(r.displayKey?.label ?? "null") (pair=\(r.pairDecided) mode=\(r.modeDecided)) now \(r.keyNow?.label ?? "null") range \(r.keyRangeLow?.label ?? "null")..\(r.keyRangeHigh?.label ?? "null") \(r.keyMove) tuning \(Int(r.tuningCents)) spread \(Int(r.singerSpreadCents))"
    }
    private func pairOf(_ k: Key?) -> Key? { k.map { RelativeKeyResolver.pair($0).first } }

    func testSteadySingingIsOneKeyWithNoWandering() {
        let r = hear(sing({ _ in 0.0 }, 10.0))
        print("steady: \(describe(r))")
        XCTAssertEqual(r.displayKey, c, describe(r))
        XCTAssertEqual(r.keyNow, c, describe(r))
        XCTAssertEqual(r.keyRangeLow, c, describe(r)); XCTAssertEqual(r.keyRangeHigh, c, describe(r))
    }

    func testRisingFromCToDAndBackCentresOnCSharpAndEndsInC() {
        let total = self.total
        let r = hear(sing({ t in 100.0 * (1 - cos(2 * Double.pi * t / total)) }, 10.0))
        print("C to D and back: \(describe(r))")
        XCTAssertEqual(pairOf(r.displayKey), c.transposed(1), describe(r))
        XCTAssertEqual(pairOf(r.keyNow), c, describe(r))
        XCTAssertEqual(pairOf(r.keyRangeLow), c, describe(r)); XCTAssertEqual(pairOf(r.keyRangeHigh), c.transposed(2), describe(r))
    }

    func testWobblingBetweenCAndDCentresOnCSharp() {
        let r = hear(sing({ t in 100.0 + 100.0 * sin(2 * Double.pi * t / 25.0) }, 10.0))
        print("wobbling C..D: \(describe(r))")
        XCTAssertEqual(pairOf(r.displayKey), c.transposed(1), describe(r))
        XCTAssertTrue(pairOf(r.keyRangeLow) == c && pairOf(r.keyRangeHigh) == c.transposed(2), describe(r))
    }

    func testAnAverageSingerCreepingSharpIsFollowed() {
        let total = self.total
        let r = hear(sing({ t in 150.0 * t / total }, 30.0))
        print("creeping sharp: \(describe(r))")
        XCTAssertTrue(pairOf(r.keyNow) == c.transposed(1) || pairOf(r.keyNow) == c.transposed(2), describe(r))
        XCTAssertEqual(r.keyMove, KeyLevelTracker.Move.rising, describe(r))
        // Never sure of a key the singing was not in.
        if r.pairDecided { XCTAssertTrue([c, c.transposed(1)].contains { $0 == pairOf(r.displayKey) }, describe(r)) }
    }

    func testALastVerseLiftedASemitoneIsANewKey() {
        let lift = Double(2 * tune.reduce(0) { $0 + $1.1 }) * beat + 0.3
        let r = hear(sing({ t in t < lift ? 0.0 : 100.0 }, 10.0))
        print("lifted last verse: \(describe(r))")
        XCTAssertEqual(r.displayKey, c, describe(r))
        XCTAssertEqual(r.keyNow, c.transposed(1), describe(r))
        XCTAssertEqual(r.keyMove, KeyLevelTracker.Move.settled, describe(r))
    }
}
