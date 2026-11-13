import Foundation
import XCTest
@testable import KeyCatchCore

/// A lone voice singing between the keys, with slides: the case a chroma key finder blurs.
/// The MelodyKeyTest cases that run the whole analyser (the others are in MelodyKeyTests).
final class MelodyKeyAnalyzerTests: XCTestCase {
    private let rate = Synth.sampleRate

    /// A sung line: each note slides in from a semitone below over 60 ms, has vibrato, and sits `offsetCents` off the grid.
    private func sing(_ notes: [(Int, Double)], offsetCents: Double, gapAfter: Set<Int> = []) -> [Float] {
        let r = Double(rate)
        let total = notes.reduce(0.0) { $0 + $1.1 } + Double(gapAfter.count) * 0.5 + 0.5
        var out = [Float](repeating: 0, count: Int(total * r))
        var t0 = 0.0
        for (k, note) in notes.enumerated() {
            let (midi, len) = note
            let f = 440.0 * pow(2.0, (Double(midi - 69) + offsetCents / 100.0) / 12.0)
            let n = Int(len * r)
            var phase = 0.0
            for i in 0..<n {
                let t = Double(i) / r
                let slide = t < 0.06 ? pow(2.0, -(1.0 - t / 0.06) / 12.0) : 1.0
                let vib = 1.0 + 0.004 * sin(2 * Double.pi * 5.5 * t)
                phase += 2 * Double.pi * f * slide * vib / r
                let env = min(1.0, t / 0.02) * min(1.0, Double(n - i) / r / 0.04)
                let idx = Int(t0 * r) + i
                if idx < out.count {
                    let s: Double = sin(phase) + 0.5 * sin(2 * phase) + 0.25 * sin(3 * phase)
                    out[idx] += Float(0.2 * env * s)
                }
            }
            t0 += len + (gapAfter.contains(k) ? 0.5 : 0.0)
        }
        return out
    }

    // An E major tune: ends phrases on E, longest note E, uses G#, F#, A, B, D#.
    private let tune: [(Int, Double)] = [
        (63, 0.3), (64, 0.5), (68, 0.4), (66, 0.3), (64, 0.8),   // D# E G# F# E(long)   phrase
        (69, 0.3), (68, 0.3), (66, 0.3), (71, 0.4), (68, 0.3),   // A G# F# B G#
        (66, 0.3), (64, 0.3), (63, 0.3), (64, 0.9),              // F# E D# E(long)      phrase end
    ]

    func testSharpSingingWithSlidesStillReadsEMajorAndTheTuning() {
        let audio = sing(tune, offsetCents: 40.0, gapAfter: [4, 13])
        let result = Synth.analyze(audio, MusicAnalyzer(sampleRate: rate, preset: .voices))
        XCTAssertTrue(result.sungNotes.count >= 10, "notes found: \(result.sungNotes.count)")
        XCTAssertTrue(result.keyFromMelody, "key from melody")
        XCTAssertEqual(result.key, Key(4, .major))
        XCTAssertEqual(result.tuningCents, 40, accuracy: 12, "tuning \(result.tuningCents)")
        // The notes landed on single pitch classes: no D#/E split from the sharpness.
        let pcs = result.sungNotes.map { floorMod($0.midi, 12) }
        XCTAssertTrue(pcs.filter { $0 == 4 }.count >= pcs.filter { $0 == 5 }.count * 2, "E dominant: \(pcs)")
    }

    func testFlatSingingReadsTheSameKey() {
        let audio = sing(tune, offsetCents: -35.0, gapAfter: [4, 13])
        let result = Synth.analyze(audio, MusicAnalyzer(sampleRate: rate, preset: .voices))
        XCTAssertEqual(result.key, Key(4, .major))
        XCTAssertEqual(result.tuningCents, -35, accuracy: 12)
    }

    func testTheShownKeyHoldsStillWhileThePhraseIsSung() {
        // A line that starts on the dominant and the sixth, which also fit B major and C# minor,
        // then settles on E. The display should move at most once or twice, never flicker.
        let rate = 44100
        let line = [71, 73, 71, 68, 71, 73, 76, 73, 71, 68, 66, 64, 64, 68, 71, 64]
        let audio = sing(line.enumerated().map { i, m in (m, i == line.count - 1 ? 0.9 : 0.35) }, offsetCents: 0.0, gapAfter: [7])
        let analyzer = MusicAnalyzer(sampleRate: rate, preset: .voices)
        var seen = [String]()
        var i = 0
        while i < audio.count {
            let n = min(1024, audio.count - i)
            analyzer.process(Array(audio[i..<(i + n)]), count: n, nowNanos: Int(Double(i + n) / Double(rate) * 1e9))
            if let k = analyzer.result.key?.label, seen.isEmpty || seen.last != k {
                seen.append(k)
                print("t=\(String(format: "%.2f", Double(i + n) / Double(rate))) key=\(k) held=\(analyzer.result.keyHeldSeconds) melody=\(analyzer.result.keyFromMelody)")
            }
            i += n
        }
        XCTAssertTrue(seen.count <= 2, "shown keys: \(seen)")
        XCTAssertEqual(seen.last, "E major")
        let r = analyzer.result
        XCTAssertTrue(r.keyHeldSeconds > 0.5, "held \(r.keyHeldSeconds) s")
        XCTAssertTrue(r.keyScore > 0.3, "score \(r.keyScore)")
    }

    private func analyse(_ audio: [Float]) -> AnalysisResult {
        let analyzer = MusicAnalyzer(sampleRate: rate, preset: .voices)
        var i = 0
        while i < audio.count {
            let n = min(1024, audio.count - i)
            analyzer.process(Array(audio[i..<(i + n)]), count: n, nowNanos: Int(Double(i + n) / Double(rate) * 1e9))
            i += n
        }
        return analyzer.result
    }

    func testRelativeMajorAndMinorAreToldApartFromTheSinging() {
        // The same white notes, twice: once resting on C, once resting on A with a G# before it.
        let inC: [(Int, Double)] = [(60, 0.4), (62, 0.4), (64, 0.4), (65, 0.4), (67, 0.8), (69, 0.4), (67, 0.4), (65, 0.4), (64, 0.4), (62, 0.4), (60, 1.2)]
        let inA: [(Int, Double)] = [(69, 0.4), (71, 0.4), (72, 0.4), (74, 0.4), (76, 0.8), (74, 0.4), (72, 0.4), (71, 0.4), (68, 0.4), (71, 0.4), (69, 1.2)]
        let c = analyse(sing(inC + inC, offsetCents: 0.0, gapAfter: [4, 10, 15]))
        let a = analyse(sing(inA + inA, offsetCents: 0.0, gapAfter: [4, 10, 15]))
        print("C tune: \(c.key?.label ?? "null") decided=\(c.modeDecided) score=\(c.modeScore); A tune: \(a.key?.label ?? "null") decided=\(a.modeDecided) score=\(a.modeScore)")
        XCTAssertEqual(c.key, Key(0, .major))
        XCTAssertTrue(c.modeDecided, "C decided, score \(c.modeScore)")
        XCTAssertEqual(a.key, Key(9, .minor))
        XCTAssertTrue(a.modeDecided, "A decided, score \(a.modeScore)")
        XCTAssertEqual(a.relativeKey, Key(0, .major))
    }
}
