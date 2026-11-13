#if canImport(CoreML)
import CoreML
import Foundation
import XCTest
@testable import KeyCatchCore

/// Both Beat This! packages against the official PyTorch models (FP32), and the whole live path
/// (resampler, front end, Core ML, reading) against the Python pipeline, from the reference data
/// of tools/beatthis/make_fixtures.py. These run only where Core ML exists (macOS in CI).
final class BeatThisCoreMLTests: XCTestCase {
    private static let names = ["small": "small0", "full": "final0"]

    private func compareWithPyTorch(_ which: BeatThisModel) throws {
        let ref = try BeatThisFixtures.json("beat-this-coreml")
        let inputs = try BeatThisFixtures.halves("beat-this-coreml-input")
        let size = BeatThis.INPUT_FRAMES * BeatThis.MELS
        XCTAssertEqual(inputs.count, 2 * size)
        let labels = try XCTUnwrap(ref["inputs"] as? [String])
        let want = try XCTUnwrap((ref["logits"] as? [String: Any])?[BeatThisCoreMLTests.names[which.rawValue]!] as? [[String: Any]])
        let net = try CoreMLBeatThis(which)
        for k in 0..<2 {
            let out = try XCTUnwrap(net.logits(Array(inputs[(k * size)..<((k + 1) * size)])))
            let wb = BeatThisFixtures.nums(want[k]["beat"]).map { Float($0) }
            let wd = BeatThisFixtures.nums(want[k]["downbeat"]).map { Float($0) }
            XCTAssertEqual(out.beat.count, BeatThis.INPUT_FRAMES)
            var worst: Float = 0
            var flips = 0
            for t in 0..<BeatThis.INPUT_FRAMES {
                worst = max(worst, abs(out.beat[t] - wb[t]), abs(out.downbeat[t] - wd[t]))
                if (out.beat[t] > 0) != (wb[t] > 0) { flips += 1 }
                if (out.downbeat[t] > 0) != (wd[t] > 0) { flips += 1 }
            }
            let crop = { (x: [Float]) in Array(x[BeatThis.BORDER..<(BeatThis.BORDER + BeatThis.FRAMES)]) }
            let got = BeatPostprocessing.beatsAndDownbeats(beat: crop(out.beat), downbeat: crop(out.downbeat))
            let exp = BeatPostprocessing.beatsAndDownbeats(beat: crop(wb), downbeat: crop(wd))
            print("Beat This! \(which.rawValue) on Core ML, \(labels[k]): largest logit difference from PyTorch \(worst), "
                  + "\(flips) of \(2 * BeatThis.INPUT_FRAMES) signs differ, beats \(got.beats.count) (PyTorch \(exp.beats.count)), "
                  + "downbeats \(got.downbeats.count) (PyTorch \(exp.downbeats.count))")
            // Half precision: the FP16 stand-in in PyTorch was within 0.24 (small) and 0.12 (full).
            XCTAssertLessThan(worst, 0.5, labels[k])
            XCTAssertEqual(got.beats, exp.beats, labels[k])
            XCTAssertEqual(got.downbeats, exp.downbeats, labels[k])
        }
    }

    func testBeatThisSmallMatchesPyTorch() throws { try compareWithPyTorch(.small) }

    func testBeatThisFullMatchesPyTorch() throws { try compareWithPyTorch(.full) }

    /// The synthetic band through the whole live path, compared with the Python pipeline (soxr,
    /// the float64 log-mel, PyTorch FP32, bt_live.Reader).
    private func endToEnd(_ which: BeatThisModel) throws {
        let ref = try BeatThisFixtures.json("beat-this-coreml")
        let want = try XCTUnwrap((ref["e2e"] as? [String: Any])?[BeatThisCoreMLTests.names[which.rawValue]!] as? [[String: Any]])
            .map(BeatThisFixtures.Reading.init)
        let audio = BeatThisFixtures.band()
        XCTAssertEqual(Double(audio.map { abs($0) }.max()!), BeatThisFixtures.num(ref["bandPeak"])!, accuracy: 1e-6, "the synthetic band must match Python's")
        let tracker = BeatTracker(try CoreMLBeatThis(which), 44100, name: which.title)
        var got: [BeatReading] = []
        var i = 0
        while i < audio.count {
            let n = min(1024, audio.count - i)
            tracker.push(audio, i, n)
            if tracker.readings > got.count, let r = tracker.latest { got.append(r) }
            i += n
        }
        XCTAssertFalse(tracker.failed)
        // The resampler needs a few samples past the end for the last moment, which Python has.
        XCTAssertGreaterThanOrEqual(got.count, want.count - 1)
        for (g, w) in zip(got, want) {
            let what = "\(which.rawValue) at \(w.t) s"
            XCTAssertEqual(g.moment, w.t, what)
            print("Beat This! \(which.rawValue) end to end at \(w.t) s: \(g.bpm.map { String(format: "%.2f", $0) } ?? "-") BPM (Python "
                  + "\(w.bpm.map { String(format: "%.2f", $0) } ?? "-")), last beat \(g.lastBeatSeconds.map { String(format: "%.3f", $0) } ?? "-") "
                  + "(\(w.last.map { String(format: "%.3f", $0) } ?? "-")), \(g.beatsPerBar ?? 0) beats per bar (\(w.beatsPerBar ?? 0)), "
                  + "position \(g.lastBeatIndexInBar) (\(w.index))")
            XCTAssertEqual(g.bpm ?? 0, w.bpm ?? 0, accuracy: 0.02 * (w.bpm ?? 1), what)
            XCTAssertEqual(g.lastBeatSeconds ?? 0, w.last ?? 0, accuracy: 0.025, what)
            XCTAssertEqual(g.beatsPerBar, w.beatsPerBar, what)
            XCTAssertEqual(g.lastBeatIndexInBar, w.index, what)
        }
        // And it is the right answer: 100 BPM, 4/4, beat k at 0.6 k s with the downbeat on k % 4 == 0.
        if let last = got.last {
            XCTAssertEqual(last.bpm ?? 0, 100, accuracy: 1.5)
            XCTAssertEqual(last.beatsPerBar, 4)
            let k = Int((last.lastBeatSeconds! / 0.6).rounded())
            XCTAssertEqual(last.lastBeatIndexInBar, k % 4)
        }
    }

    func testBeatThisSmallEndToEnd() throws { try endToEnd(.small) }

    func testBeatThisFullEndToEnd() throws { try endToEnd(.full) }

    func testBeatThisTiming() throws {
        let inputs = try BeatThisFixtures.halves("beat-this-coreml-input")
        let input = Array(inputs[0..<(BeatThis.INPUT_FRAMES * BeatThis.MELS)])
        for which in BeatThisModel.allCases {
            for (units, label) in [(MLComputeUnits.all, "all units"), (.cpuOnly, "CPU only")] {
                let net = try CoreMLBeatThis(model: KeyCatchModels.load(which.resourceName, computeUnits: units))
                XCTAssertNotNil(net.logits(input))
                let start = Date()
                for _ in 0..<10 { _ = net.logits(input) }
                print(String(format: "Beat This! %@ on Core ML (%@): %.1f ms per run (one run a second)", which.rawValue, label,
                             Date().timeIntervalSince(start) * 1000 / 10))
            }
        }
        // The front end and the reading, on this machine's CPU, per second of audio.
        let audio = BeatThisFixtures.band(seconds: 10)
        let tracker = BeatTracker(FixedNetwork(), 44100)
        let start = Date()
        tracker.push(audio, 0, audio.count)
        print(String(format: "Beat This! resampler, front end and reading: %.2f ms per second of audio", Date().timeIntervalSince(start) * 1000 / 10))
    }
}

/// Beats every half second, for timing what surrounds the network.
private final class FixedNetwork: BeatNetwork {
    func logits(_ spect: [Float]) -> (beat: [Float], downbeat: [Float])? {
        let b = (0..<BeatThis.INPUT_FRAMES).map { $0 % 25 == 0 ? Float(3) : -3 }
        let d = (0..<BeatThis.INPUT_FRAMES).map { $0 % 100 == 0 ? Float(3) : -3 }
        return (b, d)
    }
}
#endif
