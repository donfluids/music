import Foundation
import XCTest
@testable import KeyCatchCore

/// A lone voice singing between the keys, with slides: the case a chroma key finder blurs.
/// Only the tests that do not need MusicAnalyzer are ported here.
final class MelodyKeyTests: XCTestCase {
    private let rate = 44100   // Synth.SAMPLE_RATE in the Kotlin tests

    func testMelodicFinderUsesPhraseEndingsAndTheLongestNote() {
        // Same pitch classes as C major and A minor; the phrases end on A and A is longest: A minor.
        let notes = [
            SungNote(midi: 60, startSeconds: 0.0, durationSeconds: 0.3), SungNote(midi: 64, startSeconds: 0.3, durationSeconds: 0.3),
            SungNote(midi: 67, startSeconds: 0.6, durationSeconds: 0.3), SungNote(midi: 69, startSeconds: 0.9, durationSeconds: 1.0),
            SungNote(midi: 62, startSeconds: 2.5, durationSeconds: 0.3), SungNote(midi: 65, startSeconds: 2.8, durationSeconds: 0.3),
            SungNote(midi: 64, startSeconds: 3.1, durationSeconds: 0.3), SungNote(midi: 69, startSeconds: 3.4, durationSeconds: 0.9),
        ]
        XCTAssertEqual(MelodicKeyFinder.rank(notes)[0].key, Key(9, .minor))
        // Same notes, phrases ending on C with C longest: C major.
        let cNotes = notes.map { n -> SungNote in
            if n.midi == 69 { var c = n; c.pitch = 72.0; return c }
            return n
        }
        XCTAssertEqual(MelodicKeyFinder.rank(cNotes)[0].key, Key(0, .major))
    }

    func testPitchTrackerFindsTheFundamentalNotAnOctave() {
        let tracker = PitchTracker(rate)
        for midi in [43, 50, 57, 64, 72] {
            let f = 440.0 * pow(2.0, Double(midi - 69) / 12.0)
            let r = Double(rate)
            let x = (0..<4096).map { i -> Float in
                let t = Double(i)
                let a: Double = sin(2 * Double.pi * f * t / r)
                let b: Double = 0.6 * sin(4 * Double.pi * f * t / r)
                let c: Double = 0.3 * sin(6 * Double.pi * f * t / r)
                return Float(0.2 * (a + b + c))
            }
            XCTAssertTrue(tracker.process(x, end: 4096))
            XCTAssertTrue(abs(tracker.midi - Double(midi)) < 0.1, "midi \(midi) got \(tracker.midi)")
        }
    }
}
