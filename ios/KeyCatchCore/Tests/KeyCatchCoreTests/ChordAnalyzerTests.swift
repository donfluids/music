import Foundation
import XCTest
@testable import KeyCatchCore

/// The ChordTest cases that run the whole analyser.
final class ChordAnalyzerTests: XCTestCase {

    private func collapsedTriads(_ result: AnalysisResult) -> [String] {
        result.chordSegments.compactMap { $0.chord?.triad.description }
            .reduce(into: [String]()) { acc, n in if acc.last != n { acc.append(n) } }
    }

    func testAnalyzerTracksChordSequenceAndSuggestsKey() {
        // D - G - A - Bm - D, two seconds each, as sung voices.
        let roots = [[50, 57, 62, 66], [55, 59, 62, 67], [57, 61, 64, 69], [59, 62, 66, 71], [50, 57, 62, 66]]
        let result = Synth.analyze(Synth.progression(roots, secondsEach: 2.0))
        XCTAssertEqual(collapsedTriads(result), ["D", "G", "A", "Bm", "D"])
        XCTAssertEqual(result.currentChord?.triad.description ?? "null", "D")
        XCTAssertEqual(result.songKey, Key(2, .major))
        XCTAssertTrue(result.songKeyConfidence > 0.3, "confidence \(result.songKeyConfidence)")
    }

    func testAnalyzerHearsSeventhChords() {
        // G - C - D7 - G with the seventh voiced clearly.
        let chords = [[43, 55, 59, 62, 67], [48, 55, 60, 64, 67], [50, 57, 60, 62, 66], [43, 55, 59, 62, 67]]
        let result = Synth.analyze(Synth.progression(chords, secondsEach: 2.0))
        XCTAssertEqual(collapsedTriads(result), ["G", "C", "D", "G"])
        // maxByOrNull keeps the first of equal maxima; max(by:) with < keeps the last, so reduce explicitly.
        let ds = result.chordSegments.filter { $0.chord?.root == 2 }
        let longest = ds.dropFirst().reduce(ds.first) { best, s in (best == nil || s.duration > best!.duration) ? s : best }
        guard let d = longest?.chord else { return XCTFail("no D segment") }
        XCTAssertEqual(d.type, ChordType.dominantSeventh)
        XCTAssertEqual(result.songKey, Key(7, .major))
    }

    func testChordBoundariesFollowTheBeatWhenTempoIsKnown() {
        // Chords change every two beats at 120 BPM (one second) over a click track.
        let bpm = 120.0
        let chords = [[48, 55, 60, 64], [53, 57, 60, 65], [55, 59, 62, 67], [48, 55, 60, 64]]
        let progression = Synth.progression(chords + chords + chords + chords, secondsEach: 1.0)
        let clicks = Synth.clickTrack(bpm, 16.0, accents: [1, 0.4, 0.7, 0.4])
        let result = Synth.analyze(Synth.mix(progression, clicks))
        XCTAssertTrue(result.tempoLocked, "tempo locked")
        // Every boundary in the second half of the song should land within 150 ms of a whole second.
        let late = result.chordSegments.filter { $0.startSeconds > 8 }
        XCTAssertTrue(late.count >= 4, "need segments")
        for seg in late {
            let offset = seg.startSeconds - Float(roundToInt(seg.startSeconds))
            XCTAssertTrue(abs(offset) < 0.15, "boundary at \(seg.startSeconds) for \(String(describing: seg.chord))")
        }
        XCTAssertEqual(Array(collapsedTriads(result).suffix(4)), ["C", "F", "G", "C"])
    }
}
