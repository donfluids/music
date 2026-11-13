import Foundation
import XCTest
@testable import KeyCatchCore

final class TempoAndMeterTests: XCTestCase {

    private func assertBpm(_ expected: Double, _ result: AnalysisResult, file: StaticString = #filePath, line: UInt = #line) {
        let bpm = result.bpm
        XCTAssertNotNil(bpm, "no tempo found", file: file, line: line)
        guard let bpm else { return }
        XCTAssertEqual(Double(bpm), expected, accuracy: expected * 0.03, "bpm", file: file, line: line)
    }

    func testPlainClicksAt120() {
        let result = Synth.analyze(Synth.clickTrack(120.0, 12.0))
        assertBpm(120.0, result)
        XCTAssertTrue(result.tempoLocked)
    }

    func testFourFourAt110() {
        let audio = Synth.clickTrack(110.0, 16.0, accents: [1, 0.4, 0.7, 0.4])
        let result = Synth.analyze(audio)
        assertBpm(110.0, result)
        XCTAssertEqual(result.meterLabel, "4/4")
        XCTAssertEqual(result.beatsPerBar, 4)
    }

    func testThreeFourAt130() {
        let audio = Synth.clickTrack(130.0, 16.0, accents: [1, 0.4, 0.4])
        let result = Synth.analyze(audio)
        assertBpm(130.0, result)
        XCTAssertEqual(result.meterLabel, "3/4")
    }

    func testSixEightFeltInTwo() {
        // Dotted-quarter pulse at 80 with triplet eighths underneath, strong-weak bars.
        let audio = Synth.clickTrack(80.0, 18.0, accents: [1, 0.5], subdivisions: 3, subdivisionAmplitude: 0.35)
        let result = Synth.analyze(audio)
        assertBpm(80.0, result)
        XCTAssertTrue(result.meterCompound, "compound")
        XCTAssertEqual(result.meterLabel, "6/8")
    }

    func testSevenEightAtTheEighth() {
        // 2+2+3 at 200 eighths per minute.
        let audio = Synth.clickTrack(200.0, 20.0, accents: [1, 0.3, 0.7, 0.3, 0.7, 0.3, 0.3])
        let result = Synth.analyze(audio)
        assertBpm(200.0, result)
        XCTAssertEqual(result.meterLabel, "7/8")
    }

    func testDownbeatIsOnTheAccent() {
        let bpm = 100.0
        let audio = Synth.clickTrack(bpm, 16.0, accents: [1, 0.3, 0.6, 0.3])
        let analyzer = MusicAnalyzer(sampleRate: Synth.sampleRate)
        let result = Synth.analyze(audio, analyzer)
        XCTAssertEqual(result.beatsPerBar, 4)
        // The beat that lastBeatAtNanos points at must be a multiple of the period from time 0
        // (allowing one hop of slack) and its bar index must match its position from the start.
        let periodNanos = 60.0 / bpm * 1e9
        let beatsFromStart = Double(result.lastBeatAtNanos) / periodNanos
        let nearest = kotlinRound(beatsFromStart)
        XCTAssertEqual(beatsFromStart, Double(nearest), accuracy: 0.15, "beat alignment")
        XCTAssertEqual(result.lastBeatIndexInBar, floorMod(nearest, 4))
    }

    func testKeyAndTempoTogether() {
        let chords = Synth.progression(
            [[55, 59, 62, 67], [48, 55, 60, 64], [50, 57, 62, 66], [55, 59, 62, 67]],
            secondsEach: 4.0
        )
        let clicks = Synth.clickTrack(96.0, 16.0, accents: [1, 0.4, 0.7, 0.4], subdivisions: 2, subdivisionAmplitude: 0.15)
        let result = Synth.analyze(Synth.mix(chords, clicks))
        XCTAssertEqual(result.key?.label, "G major")
        assertBpm(96.0, result)
        XCTAssertEqual(result.meterLabel, "4/4")
    }

    func testSilenceReportsNothing() {
        let result = Synth.analyze([Float](repeating: 0, count: 10 * Synth.sampleRate))
        XCTAssertEqual(result.bpm, nil)
        XCTAssertEqual(result.key, nil)
        XCTAssertEqual(result.meterLabel, nil)
    }
}
