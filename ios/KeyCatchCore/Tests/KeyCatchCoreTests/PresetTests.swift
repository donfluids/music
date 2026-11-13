import Foundation
import XCTest
@testable import KeyCatchCore

/// Every preset must still get the basics right; they differ in emphasis, not in kind.
final class PresetTests: XCTestCase {

    func testEveryPresetFindsTheKey() {
        let progression = Synth.progression(
            [[50, 57, 62, 66], [55, 59, 62, 67], [57, 61, 64, 69], [50, 57, 62, 66]],
            secondsEach: 2.0
        )
        for preset in AnalysisPreset.all {
            let result = Synth.analyze(progression, MusicAnalyzer(sampleRate: Synth.sampleRate, preset: preset))
            XCTAssertEqual(result.key, Key(2, .major), preset.title)
        }
    }

    func testEveryPresetFindsFourFour() {
        let audio = Synth.clickTrack(110.0, 20.0, accents: [1, 0.4, 0.7, 0.4])
        for preset in AnalysisPreset.all {
            let result = Synth.analyze(audio, MusicAnalyzer(sampleRate: Synth.sampleRate, preset: preset))
            XCTAssertNotNil(result.bpm, preset.title)
            guard let bpm = result.bpm else { continue }
            XCTAssertEqual(Double(bpm), 110.0, accuracy: 3.5, preset.title)
            XCTAssertEqual(result.meterLabel, "4/4", preset.title)
        }
    }

    func testPresetLookupFallsBackToBand() {
        XCTAssertEqual(AnalysisPreset.byId(nil), AnalysisPreset.band)
        XCTAssertEqual(AnalysisPreset.byId("nonsense"), AnalysisPreset.band)
        XCTAssertEqual(AnalysisPreset.byId("voices"), AnalysisPreset.voices)
    }
}
