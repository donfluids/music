import Foundation
import XCTest
@testable import KeyCatchCore

final class KeyDetectorTests: XCTestCase {

    func testCMajorScaleProfileIsCMajor() {
        // C D E F G A B with a heavy tonic and dominant.
        let chroma: [Float] = [5, 0, 2, 0, 3, 2, 0, 4, 0, 2, 0, 1.5]
        let ranked = KeyDetector.rank(chroma)
        XCTAssertEqual(Key(0, .major), ranked.first!.key)
        XCTAssertTrue(KeyDetector.confidence(ranked) > 0.3)
    }

    func testAMinorWithLeadingToneIsAMinor() {
        // A B C D E F G# with emphasis on A, E and C.
        let chroma: [Float] = [3, 0, 1.5, 0, 3.5, 1.5, 0, 0.5, 2, 5, 0, 1.5]
        let ranked = KeyDetector.rank(chroma)
        XCTAssertEqual(Key(9, .minor), ranked.first!.key)
    }

    func testTemperleyProfilesFindTheSameScales() {
        let cMajor: [Float] = [5, 0, 2, 0, 3, 2, 0, 4, 0, 2, 0, 1.5]
        let aMinor: [Float] = [3, 0, 1.5, 0, 3.5, 1.5, 0, 0.5, 2, 5, 0, 1.5]
        XCTAssertEqual(Key(0, .major), KeyDetector.rank(cMajor, .temperley).first!.key)
        XCTAssertEqual(Key(9, .minor), KeyDetector.rank(aMinor, .temperley).first!.key)
    }

    func testOnlyGuitarUsesTemperley() {
        XCTAssertEqual(KeyProfile.temperley, AnalysisPreset.guitar.keyProfile)
        XCTAssertEqual(KeyProfile.krumhanslKessler, AnalysisPreset.band.keyProfile)
        XCTAssertEqual(KeyProfile.krumhanslKessler, AnalysisPreset.voices.keyProfile)
    }

    func testSilenceHasNoConfidence() {
        let ranked = KeyDetector.rank([Float](repeating: 0, count: 12))
        XCTAssertEqual(0.0, KeyDetector.confidence(ranked), accuracy: 1e-9)
    }

    // synthesisedDMajorProgressionIsDMajor and sharpSingingReportsTuningOffset run the whole
    // MusicAnalyzer and are ported with it.

    /// Not in the Kotlin suite: the sharp-singing case fed straight to the ChromaExtractor, so the
    /// extractor is covered without the analyser.
    func testChromaExtractorReportsSharpTuning() {
        let rate = 44100
        let n = 3 * rate
        var out = [Float](repeating: 0, count: n)
        for midi in [57, 61, 64] {
            let f = 440.0 * pow(2.0, Double(midi - 69) / 12.0) * pow(2.0, 30.0 / 1200.0)
            for i in 0..<n { out[i] += Float(0.1 * sin(2 * Double.pi * f * Double(i) / Double(rate))) }
        }
        let chroma = ChromaExtractor(rate)
        let hop = 2048
        var end = chroma.windowSamples
        while end <= n {
            chroma.process(out, end, Double(hop) / Double(rate))
            end += hop
        }
        XCTAssertEqual(30.0, chroma.tuningOffsetCents(), accuracy: 6.0)
        let top = (0..<12).sorted { chroma.accumulatedChroma[$0] > chroma.accumulatedChroma[$1] }.prefix(3)
        XCTAssertEqual(Set([9, 1, 4]), Set(top))
    }
}
