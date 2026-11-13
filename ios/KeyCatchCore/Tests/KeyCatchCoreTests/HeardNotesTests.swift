import Foundation
import XCTest
@testable import KeyCatchCore

/// Stands in for Basic Pitch: it knows the score, works out when each frame of the window it is
/// given was played (from how many windows it has seen), and answers 0.9 for the notes really
/// sounding then and 0.02 for the rest. What HeardNotes does around the model (windowing,
/// dating, the floor, the bass boost, pitch classes) is then tested for real.
private final class ScoreNetwork: NoteNetwork {
    /// (midi, start, end) in seconds.
    let score: [(Int, Double, Double)]
    let firstRun: Int
    let runSamples: Int
    private(set) var calls = 0

    init(score: [(Int, Double, Double)], runEverySeconds: Double = 0.5) {
        self.score = score
        // As HeardNotes schedules its runs: the first when the kept frames reach back to the first
        // sample, then every runFrames hops.
        let runFrames = Int(runEverySeconds * Double(BasicPitch.SAMPLE_RATE) / Double(BasicPitch.HOP)).coerceIn(1, HeardNotes.KEEP_END)
        firstRun = BasicPitch.WINDOW - (HeardNotes.KEEP_END - runFrames) * BasicPitch.HOP
        runSamples = runFrames * BasicPitch.HOP
    }

    func notes(window: [Float], from: Int, to: Int) -> [[Float]] {
        XCTAssertEqual(window.count, BasicPitch.WINDOW)
        // The window ends at the newest resampled sample; frame f is centred at window sample f * HOP.
        let written = firstRun + calls * runSamples
        let start = written - BasicPitch.WINDOW
        calls += 1
        return (from..<to).map { f in
            let t = Double(start + f * BasicPitch.HOP) / Double(BasicPitch.SAMPLE_RATE)
            var row = [Float](repeating: 0.02, count: BasicPitch.NOTES)
            for (midi, s, e) in score where t >= s && t < e { row[midi - BasicPitch.LOWEST_MIDI] = 0.9 }
            return row
        }
    }
}

final class HeardNotesTests: XCTestCase {

    func testTheResamplerKeepsATonesPitchAndTime() {
        for rate in [44100, 48000] {
            let r = StreamResampler(rate, 22050)
            let f = 1000.0
            let input = (0..<rate).map { Float(sin(2 * Double.pi * f * Double($0) / Double(rate))) }
            var out: [Float] = []
            var i = 0
            while i < input.count { let n = min(512, input.count - i); r.push(input, i, n) { out.append($0) }; i += n }
            // Output n is the input at time n / 22050: compare away from the start.
            var worst = 0.0
            for n in 2000..<(out.count - 10) { worst = max(worst, abs(Double(out[n]) - sin(2 * Double.pi * f * Double(n) / 22050))) }
            XCTAssertTrue(worst < 0.01, "rate \(rate): worst error \(worst)")
            XCTAssertTrue(abs(out.count - 22050) < 100, "rate \(rate): \(out.count) samples")
        }
    }

    /// C major (C3 E3 G3) for 2 s, then A minor (A2 C3 E3) for 2 s, as plucked strings.
    private func chordAudio(_ rate: Int) -> ([Float], [(Int, Double, Double)]) {
        var audio = [Float](repeating: 0, count: rate * 5)
        var score: [(Int, Double, Double)] = []
        func tone(_ midi: Int, _ start: Double, _ end: Double) {
            score.append((midi, start, end))
            let f = 440.0 * pow(2.0, Double(midi - 69) / 12.0)
            for i in Int(start * Double(rate))..<Int(end * Double(rate)) {
                let t = Double(i) / Double(rate) - start
                var s = 0.0
                for k in 1...6 { s += pow(0.6, Double(k) - 1.0) * sin(2 * Double.pi * f * Double(k) * t) }
                audio[i] += Float(0.08 * s * exp(-t * 1.2))
            }
        }
        for m in [48, 52, 55] { tone(m, 0.0, 2.0) }
        for m in [45, 48, 52] { tone(m, 2.0, 4.0) }
        return (audio, score)
    }

    private func top3(_ h: HeardNotes, _ from: Double, _ to: Double, file: StaticString = #filePath, line: UInt = #line) -> Set<Int> {
        var c = [Float](repeating: 0, count: 12)
        XCTAssertTrue(h.pitchClasses(fromSeconds: from, toSeconds: to, out: &c) > 0, file: file, line: line)
        return Set(c.indices.sorted { c[$0] != c[$1] ? c[$0] > c[$1] : $0 < $1 }.prefix(3))
    }

    /// The Kotlin test runs the real model; here [ScoreNetwork] stands in for it, so this tests
    /// the windowing, the dating of frames and the pitch classes, not the model's hearing.
    func testAChordIsHeardAndDatedToWhenItWasPlayed() {
        let rate = 44100
        let (audio, score) = chordAudio(rate)
        let network = ScoreNetwork(score: score)
        let h = HeardNotes(network, rate)
        var i = 0
        while i < audio.count { let n = min(512, audio.count - i); h.push(audio, i, n); i += n }
        XCTAssertTrue(network.calls > 0)
        XCTAssertEqual(top3(h, 0.8, 1.8), [0, 4, 7])
        XCTAssertEqual(top3(h, 2.3, 3.3), [9, 0, 4])
        // Just after the change the old chord must be gone: the frames are dated right.
        XCTAssertEqual(top3(h, 2.1, 2.4), [9, 0, 4])
        XCTAssertTrue((0.5...0.8).contains(h.delaySeconds), "delay \(h.delaySeconds)")
        // The first run reaches back to the start: the opening chord is not lost.
        XCTAssertEqual(top3(h, 0.0, 0.3), [0, 4, 7])
    }

    /// The same on a worker queue: the results arrive there, and the delay allows for it.
    func testAChordIsHeardOnAWorker() {
        let rate = 44100
        let (audio, score) = chordAudio(rate)
        let queue = DispatchQueue(label: "heard-notes-test")
        let h = HeardNotes(ScoreNetwork(score: score), rate, worker: queue)
        var i = 0
        // Waiting for the worker after each block keeps the fake's count of windows in step (a
        // worker that falls three windows behind skips one, which the count could not follow).
        while i < audio.count { let n = min(512, audio.count - i); h.push(audio, i, n); queue.sync {}; i += n }
        XCTAssertEqual(top3(h, 0.8, 1.8), [0, 4, 7])
        XCTAssertEqual(top3(h, 2.1, 2.4), [9, 0, 4])
        XCTAssertTrue(h.delaySeconds > 0.9, "delay \(h.delaySeconds)")
        XCTAssertEqual(h.skipped, 0)
    }

    func testTheResamplerShiftsPitchAndKeepsTime() {
        let rate = 48000
        let r = StreamResampler(rate, 22050)
        r.shiftCents = 40.0
        let f = 1000.0
        let input = (0..<rate).map { Float(sin(2 * Double.pi * f * Double($0) / Double(rate))) }
        var out: [Float] = []; var times: [Double] = []
        var i = 0
        while i < input.count { let n = min(512, input.count - i); r.push(input, i, n) { out.append($0); times.append(r.lastTime) }; i += n }
        // Each output is the input at its recorded time, and the tone now reads 40 cents lower.
        var worst = 0.0
        for n in 2000..<(out.count - 10) { worst = max(worst, abs(Double(out[n]) - sin(2 * Double.pi * f * times[n]))) }
        XCTAssertTrue(worst < 0.01, "worst \(worst)")
        let lowered = f * pow(2.0, -40.0 / 1200)
        var worst2 = 0.0
        for n in 2000..<(out.count - 10) { worst2 = max(worst2, abs(Double(out[n]) - sin(2 * Double.pi * lowered * Double(n) / 22050 + 2 * Double.pi * f * times[0]))) }
        XCTAssertTrue(worst2 < 0.02, "not lowered: \(worst2)")
    }
}
