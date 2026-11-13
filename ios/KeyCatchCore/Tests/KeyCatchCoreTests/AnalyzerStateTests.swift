import Foundation
import XCTest
@testable import KeyCatchCore

final class AnalyzerStateTests: XCTestCase {

    /// Every published beat time must sit on the click grid, at every publish, not just the last.
    func testPublishedBeatStaysOnTheGrid() {
        let bpm = 100.0
        let periodNanos = 60.0 / bpm * 1e9
        let audio = Synth.clickTrack(bpm, 20.0, accents: [1, 0.3, 0.6, 0.3])
        var offsets = [Double]()
        var previousBeat = 0
        Synth.analyze(audio) { r in
            if !r.tempoLocked || r.lastBeatAtNanos == 0 || r.secondsHeard < 10 { return }
            let beats = Double(r.lastBeatAtNanos) / periodNanos
            offsets.append(abs(beats - Double(kotlinRound(beats))))
            // A fresh tempo estimate may re-place the beat by a hop or so; anything larger is drift.
            let backwards = Double(previousBeat - r.lastBeatAtNanos) / periodNanos
            XCTAssertTrue(backwards < 0.2, "beat moved backwards by \(backwards) beats")
            previousBeat = max(previousBeat, r.lastBeatAtNanos)
        }
        XCTAssertTrue(offsets.count > 100, "too few samples")
        XCTAssertTrue((offsets.max() ?? .infinity) < 0.15, "max offset \(String(describing: offsets.max()))")
    }

    /// After a reset the next publish must not carry the old song's key, tempo, meter or chords.
    func testResetClearsEverythingPublished() {
        let chords = Synth.progression([[55, 59, 62, 67], [48, 55, 60, 64], [50, 57, 62, 66], [55, 59, 62, 67]], secondsEach: 3.0)
        let clicks = Synth.clickTrack(96.0, 12.0, accents: [1, 0.4, 0.7, 0.4])
        let analyzer = MusicAnalyzer(sampleRate: Synth.sampleRate)
        let before = Synth.analyze(Synth.mix(chords, clicks), analyzer)
        XCTAssertEqual(before.key?.label, "G major")
        XCTAssertTrue(before.bpm != nil && before.meterLabel != nil && !before.chordSegments.isEmpty)

        analyzer.reset()
        let after = Synth.analyze([Float](repeating: 0, count: Synth.sampleRate / 5), analyzer) // 0.2 s of silence
        XCTAssertNil(after.key)
        XCTAssertNil(after.bpm)
        XCTAssertNil(after.meterLabel)
        XCTAssertNil(after.currentChord)
        XCTAssertNil(after.songKey)
        XCTAssertTrue(after.chordSegments.isEmpty)
        XCTAssertEqual(after.lastBeatAtNanos, 0)
        XCTAssertTrue(after.secondsHeard < 0.3)
    }
}
