import XCTest
@testable import KeyCatchCore

/// Octave and bar-length choices the tracker must get right for church repertoire.
final class TempoOctaveTests: XCTestCase {

    private func assertBpm(_ expected: Double, _ result: AnalysisResult, tolerance: Double = 0.035,
                           file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertNotNil(result.bpm, "no tempo", file: file, line: line)
        guard let bpm = result.bpm else { return }
        XCTAssertEqual(Double(bpm), expected, accuracy: expected * tolerance, file: file, line: line)
    }

    func testAccentedFourFourFromOneTwentyToOneForty() {
        // Strong-weak-medium-weak at the beat: the beat, not half time, across the working range.
        for bpm in [120.0, 130.0, 140.0] {
            let result = Synth.analyze(Synth.clickTrack(bpm, 20.0, accents: [1, 0.4, 0.7, 0.4]))
            assertBpm(bpm, result)
            XCTAssertEqual(result.meterLabel, "4/4", "meter at \(bpm)")
        }
    }

    func testStrummedEighthsInFourFourAtOneTwenty() {
        let audio = Synth.clickTrack(240.0, 20.0, accents: [1, 0.3, 0.5, 0.3, 0.7, 0.3, 0.5, 0.3])
        let result = Synth.analyze(audio)
        assertBpm(120.0, result)
        XCTAssertEqual(result.meterLabel, "4/4")
    }

    func testSlowBalladStrummedInEighthsIsReadAtOneOfItsTwoPulses() {
        // 68 BPM with every eighth strummed is the same onset pattern as 136 BPM in 4/4; nothing
        // in the onsets can tell them apart, so either reading is accepted and the ÷2 control
        // covers the other. What must hold: it is one of the two, and the bar is four beats.
        let audio = Synth.clickTrack(136.0, 24.0, accents: [1, 0.3, 0.5, 0.3, 0.7, 0.3, 0.5, 0.3])
        let result = Synth.analyze(audio)
        guard let found = result.bpm else { return XCTFail("no tempo") }
        let bpm = Double(found)
        XCTAssertTrue(abs(bpm - 68.0) < 68.0 * 0.035 || abs(bpm - 136.0) < 136.0 * 0.035, "bpm \(bpm)")
        XCTAssertEqual(result.meterLabel, "4/4")
    }

    func testFastWaltzStaysInThree() {
        // A waltz at 150 is rhythmically the same as 3/2 at 75; either pulse is accepted, but the
        // bar must be three and must not be mistaken for 6/8.
        for bpm in [140.0, 150.0] {
            let result = Synth.analyze(Synth.clickTrack(bpm, 20.0, accents: [1, 0.4, 0.4]))
            guard let found = result.bpm else { XCTFail("no tempo at \(bpm)"); continue }
            let got = Double(found)
            XCTAssertTrue(abs(got - bpm) < bpm * 0.035 || abs(got - bpm / 2) < bpm * 0.02, "bpm \(got) at \(bpm)")
            XCTAssertEqual(result.meterLabel, "3/4", "meter at \(bpm)")
        }
    }

    func testCompoundAtFiftyFiveDottedQuarters() {
        // 6/8 hymn: dotted-quarter pulse at 55, eighths at 165, strong-weak bars.
        let audio = Synth.clickTrack(55.0, 24.0, accents: [1, 0.5], subdivisions: 3, subdivisionAmplitude: 0.35)
        let result = Synth.analyze(audio)
        // Either reading is right: the dotted-quarter pulse felt in two, or the eighths in six.
        guard let found = result.bpm else { return XCTFail("no tempo") }
        let bpm = Double(found)
        let dotted = abs(bpm - 55.0) < 55.0 * 0.035
        let eighths = abs(bpm - 165.0) < 165.0 * 0.035
        XCTAssertTrue(dotted || eighths, "bpm \(bpm)")
        XCTAssertEqual(result.meterLabel, "6/8")
        if dotted { XCTAssertTrue(result.meterCompound, "compound") } else { XCTAssertEqual(result.beatsPerBar, 6) }
    }

    func testSwungEighthsAreNotCompound() {
        let bpm = 100.0
        let n = Int(16.0 * Double(Synth.sampleRate))
        var out = [Float](repeating: 0, count: n)
        let period = 60.0 / bpm * Double(Synth.sampleRate)
        let accents: [Float] = [1, 0.4, 0.7, 0.4]
        var beat = 0
        var t = 0.0
        while t < Double(n) {
            Synth.click(&out, Int(t), accents[beat % 4])
            Synth.click(&out, Int(t + period * 2.0 / 3.0), 0.35)
            beat += 1
            t += period
        }
        let result = Synth.analyze(out)
        assertBpm(100.0, result)
        XCTAssertFalse(result.meterCompound, "compound")
        XCTAssertEqual(result.meterLabel, "4/4")
    }

    /// Feeds the tracker one impulse per beat and returns (seconds, bpm) for every estimate it makes.
    private func trackerEstimates(_ fps: Double, _ bpmSequence: [(Double, Double)]) -> [(Double, Double)] {
        let tracker = TempoTracker(fps)
        var out: [(Double, Double)] = []
        var frame = 0
        var nextBeat = 0.0
        for (bpm, seconds) in bpmSequence {
            let period = fps * 60.0 / bpm
            let frames = Int(seconds * fps)
            for _ in 0..<frames {
                let onset: Float
                if Double(frame) >= nextBeat { nextBeat += period; onset = 1 } else { onset = 0.05 }
                tracker.push(onset)
                frame += 1
                if frame % Int(fps / 2) == 0, let e = tracker.estimate() { out.append((Double(frame) / fps, e.bpm)) }
            }
        }
        return out
    }

    func testLockedTempoResistsAnOctaveJumpThenFollowsIt() {
        let fps = 86.13
        // 70 then 140: the doubled tempo is well inside the prior, so without hysteresis the
        // tracker would jump on the first estimate that sees mostly 140-BPM onsets.
        let estimates = trackerEstimates(fps, [(70.0, 12.0), (140.0, 12.0)])
        let before = estimates.filter { $0.0 > 10.0 && $0.0 <= 12.0 }.map { $0.1 }
        XCTAssertTrue(before.count >= 3 && before.allSatisfy { abs($0 - 70.0) < 3.0 }, "locked at 70 before the change: \(before)")
        // Hysteresis: for the first two seconds after the change the tracker still says 70.
        let justAfter = estimates.filter { $0.0 > 12.5 && $0.0 <= 14.0 }.map { $0.1 }
        XCTAssertTrue(justAfter.count >= 2 && justAfter.allSatisfy { abs($0 - 70.0) < 3.0 }, "held 70 for a while after the change: \(justAfter)")
        // But it does follow a real change within a few seconds.
        let later = estimates.filter { $0.0 > 22.0 }.map { $0.1 }
        XCTAssertTrue(later.count >= 2 && later.allSatisfy { abs($0 - 140.0) < 6.0 }, "followed to 140 eventually: \(later)")
    }
}
