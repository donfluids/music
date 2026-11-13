#if canImport(CoreML)
import CoreML
import Foundation
import XCTest
@testable import KeyCatchCore

/// The two Core ML models against the same reference data the Android tests use. These run
/// only where Core ML exists (macOS in CI), not on Linux.
final class CoreMLModelsTests: XCTestCase {
    private func json(_ name: String) throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Resources"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    func testCrepeMatchesTheReference() throws {
        let ref = try json("crepe-reference")
        let frames = try XCTUnwrap(ref["frames"] as? [[Double]])
        let expected = try XCTUnwrap(ref["activations"] as? [[Double]])
        let crepe = try CoreMLCrepe()
        for (f, frame) in frames.enumerated() {
            // The model takes the frame already normalised, as CrepePitch does.
            let mean = frame.reduce(0, +) / Double(frame.count)
            let sd = max((frame.map { ($0 - mean) * ($0 - mean) }.reduce(0, +) / Double(frame.count)).squareRoot(), 1e-8)
            let got = crepe.activations(frame16k: frame.map { Float(($0 - mean) / sd) })
            XCTAssertEqual(got.count, 360)
            let worst = zip(got, expected[f]).map { abs(Double($0) - $1) }.max() ?? 1
            let peak = got.indices.max { got[$0] < got[$1] }!
            let wantPeak = expected[f].indices.max { expected[f][$0] < expected[f][$1] }!
            print("CREPE frame \(f): largest difference \(worst), peak \(peak) (reference \(wantPeak))")
            XCTAssertEqual(peak, wantPeak, "frame \(f)")
            // Half precision on the Neural Engine: a few hundredths at most.
            XCTAssertLessThan(worst, 0.05, "frame \(f)")
        }
    }

    /// The reference script's test signal: C major (C3 E3 G3 C4), then A minor (A2 E3 A3 C4).
    private func testSignal() -> [Float] {
        var x = [Double](repeating: 0, count: BasicPitch.WINDOW)
        func tone(_ midi: Int, _ start: Double, _ end: Double) {
            let f = 440.0 * pow(2.0, Double(midi - 69) / 12.0)
            for i in x.indices {
                let t = Double(i) / 22050.0
                if t < start || t >= end { continue }
                let tt = t - start
                var s = 0.0
                for h in 1...6 { s += pow(0.6, Double(h - 1)) * sin(2 * .pi * f * Double(h) * tt) }
                x[i] += s * exp(-tt * 1.5) * 0.08
            }
        }
        for m in [48, 52, 55, 60] { tone(m, 0.05, 1.0) }
        for m in [45, 52, 57, 60] { tone(m, 1.0, 2.0) }
        return x.map { Float($0) }
    }

    func testBasicPitchNotesMatchTheReference() throws {
        let ref = try json("basic-pitch-reference")
        let want = try XCTUnwrap(ref["note"] as? [[Double]])
        let model = try CoreMLBasicPitch()
        let outputs = model.outputs(window: testSignal())
        // Which output is the notes: the one closest to the reference.
        var best = ""; var bestWorst = Double.infinity
        for (name, values) in outputs where values.count == BasicPitch.FRAMES * BasicPitch.NOTES {
            var worst = 0.0
            for t in 0..<BasicPitch.FRAMES { for k in 0..<BasicPitch.NOTES { worst = max(worst, abs(Double(values[t * BasicPitch.NOTES + k]) - want[t][k])) } }
            print("Basic Pitch output \(name): largest difference from the reference notes \(worst)")
            if worst < bestWorst { bestWorst = worst; best = name }
        }
        XCTAssertEqual(best, model.noteOutput)
        XCTAssertLessThan(bestWorst, 0.05)
        let notes = model.notes(window: testSignal(), from: 0, to: BasicPitch.FRAMES)
        func heard(_ t: Int) -> Set<Int> { Set((0..<88).filter { notes[t][$0] > 0.3 }.map { $0 + BasicPitch.LOWEST_MIDI }) }
        XCTAssertTrue(heard(40).isSuperset(of: [48, 52, 55, 60]), "\(heard(40))")
        XCTAssertTrue(heard(140).isSuperset(of: [45, 52, 57, 60]), "\(heard(140))")
    }

    func testTiming() throws {
        let crepe = try CoreMLCrepe()
        let frame = (0..<1024).map { Float(sin(Double($0) * 0.1)) }
        _ = crepe.activations(frame16k: frame)
        var start = Date()
        for _ in 0..<50 { _ = crepe.activations(frame16k: frame) }
        print(String(format: "CREPE on Core ML: %.2f ms per frame (46 ms available)", Date().timeIntervalSince(start) * 1000 / 50))
        let bp = try CoreMLBasicPitch()
        let window = testSignal()
        _ = bp.notes(window: window, from: 114, to: 157)
        start = Date()
        for _ in 0..<10 { _ = bp.notes(window: window, from: 114, to: 157) }
        print(String(format: "Basic Pitch on Core ML: %.1f ms per run (one run every 500 ms)", Date().timeIntervalSince(start) * 1000 / 10))
    }
}
#endif
