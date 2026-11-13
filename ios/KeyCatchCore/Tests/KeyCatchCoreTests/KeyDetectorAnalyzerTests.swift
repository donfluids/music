import Foundation
import XCTest
@testable import KeyCatchCore

/// The KeyDetectorTest cases that run the whole analyser.
final class KeyDetectorAnalyzerTests: XCTestCase {

    func testSynthesisedDMajorProgressionIsDMajor() {
        // D - G - A - Bm - D voiced in the range a congregation sings.
        let progression = Synth.progression(
            [
                [50, 57, 62, 66], // D
                [55, 59, 62, 67], // G
                [57, 61, 64, 69], // A
                [59, 62, 66, 71], // Bm
                [50, 57, 62, 66], // D
            ],
            secondsEach: 1.5
        )
        let result = Synth.analyze(progression)
        XCTAssertEqual(result.key, Key(2, .major))
        XCTAssertTrue(result.keyConfidence > 0.3, "confidence \(result.keyConfidence)")
        XCTAssertTrue(abs(result.tuningCents) < 5, "tuning \(result.tuningCents)")
    }

    func testSharpSingingReportsTuningOffset() {
        // Every note 30 cents sharp: shift the reference the synth uses.
        let n = Int(3.0 * Double(Synth.sampleRate))
        let notes = [57, 61, 64]
        var out = [Float](repeating: 0, count: n)
        for midi in notes {
            let f = Synth.midiToHz(midi) * pow(2.0, 30.0 / 1200.0)
            for i in 0..<n { out[i] += Float(0.1 * sin(2 * Double.pi * f * Double(i) / Double(Synth.sampleRate))) }
        }
        let result = Synth.analyze(out)
        XCTAssertEqual(result.tuningCents, 30, accuracy: 6)
    }
}
