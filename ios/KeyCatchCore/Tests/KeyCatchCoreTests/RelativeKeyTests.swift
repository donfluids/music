import Foundation
import XCTest
@testable import KeyCatchCore

final class RelativeKeyTests: XCTestCase {
    private let cMajor = Key(0, .major)
    private let aMinor = Key(9, .minor)
    private let t = RelativeKeyResolver.THRESHOLD

    /// Notes with a breath after every note of 0.8 s or longer.
    private func line(_ notes: (Int, Double)...) -> [SungNote] {
        var at = 0.0
        return notes.map { m, d in
            let n = SungNote(midi: m, startSeconds: at, durationSeconds: d)
            at += d + (d >= 0.8 ? 0.5 : 0.0)
            return n
        }
    }
    private func score(_ notes: [SungNote], _ key: Key? = nil) -> RelativeKeyResolver.Evidence {
        RelativeKeyResolver.evidence(key ?? cMajor, notes, nil, [])
    }

    private lazy var cTune = line((60, 0.4), (62, 0.4), (64, 0.4), (65, 0.4), (67, 0.8), (69, 0.4), (67, 0.4), (65, 0.4), (64, 0.4), (62, 0.4), (60, 1.2))
    private lazy var aNatural = line((69, 0.4), (71, 0.4), (72, 0.4), (74, 0.4), (76, 0.8), (74, 0.4), (72, 0.4), (71, 0.4), (72, 0.4), (71, 0.4), (69, 1.2))
    private lazy var aHarmonic = line((69, 0.4), (71, 0.4), (72, 0.4), (74, 0.4), (76, 0.8), (74, 0.4), (72, 0.4), (71, 0.4), (68, 0.4), (71, 0.4), (69, 1.2))
    private lazy var aDwellingOnC = line((72, 0.8), (71, 0.4), (69, 0.4), (72, 0.8), (74, 0.4), (72, 0.4), (71, 0.4), (72, 0.8), (71, 0.4), (69, 0.6))

    func testATuneThatRestsOnCIsCMajor() {
        let e = score(cTune)
        XCTAssertTrue(e.total > t, "\(e)")
        XCTAssertTrue(e.endings > 0, "endings \(e)")
    }

    func testTheSameNotesRestingOnAAreAMinor() {
        let e = score(aNatural)
        XCTAssertTrue(e.total < -t, "\(e)")
        // The answer does not depend on which of the pair the finder offered.
        XCTAssertEqual(e.total, score(aNatural, aMinor).total, accuracy: 1e-9)
    }

    func testAGSharpMakesTheMinorClearer() {
        let natural = score(aNatural)
        let harmonic = score(aHarmonic)
        XCTAssertTrue(harmonic.leadingTone < 0, "leading tone \(harmonic.leadingTone)")
        XCTAssertEqual(0.0, natural.leadingTone, accuracy: 1e-9)
        XCTAssertTrue(harmonic.total < natural.total, "\(harmonic) vs \(natural)")
    }

    func testATuneThatDwellsOnCButEndsOnAIsNotCalledMajor() {
        // Three phrases rest on C, the last on A: genuinely between the two. Undecided is honest.
        let e = score(aDwellingOnC)
        XCTAssertTrue(e.total <= t, "\(e)")
    }

    func testChordsCountTonicTimeTheMajorDominantAndCadences() {
        func seg(_ root: Int, _ type: ChordType, _ from: Float, _ to: Float) -> ChordSegment { ChordSegment(ChordLabel(root, type), from, to) }
        let inC = [seg(0, .major, 0, 2), seg(5, .major, 2, 3), seg(7, .major, 3, 4), seg(0, .major, 4, 6)]
        let inAm = [seg(9, .minor, 0, 2), seg(2, .minor, 2, 3), seg(4, .major, 3, 4), seg(9, .minor, 4, 6)]
        let c = RelativeKeyResolver.evidence(cMajor, [], nil, inC)
        let a = RelativeKeyResolver.evidence(cMajor, [], nil, inAm)
        XCTAssertTrue(c.chords > 1.0, "C chords \(c.chords)")
        XCTAssertTrue(a.chords < -1.0, "Am chords \(a.chords)")
    }

    func testWithoutNotesTheChromaStillSeesTheLeadingTone() {
        // A minor with G# where G would be: energy on A, C, E, and G# rather than G.
        var chroma = [Float](repeating: 0, count: 12)
        chroma[9] = 1; chroma[0] = 0.6; chroma[4] = 0.8; chroma[11] = 0.4; chroma[2] = 0.4; chroma[5] = 0.3; chroma[8] = 0.4; chroma[7] = 0.05
        let e = RelativeKeyResolver.evidence(cMajor, [], chroma, [])
        XCTAssertTrue(e.leadingTone < -1.0, "leading \(e.leadingTone)")
        XCTAssertTrue(e.total < -t, "\(e)")
    }

    func testTheDecisionHoldsUntilTheEvidenceTurns() {
        let tr = RelativeModeTracker()
        XCTAssertEqual(cMajor, tr.update(cMajor, 0.4)); XCTAssertNil(tr.decided)       // leaning, undecided
        XCTAssertEqual(cMajor, tr.update(cMajor, -0.3)); XCTAssertNil(tr.decided)      // a wobble does not swap the lean
        XCTAssertEqual(aMinor, tr.update(cMajor, -0.7)); XCTAssertNil(tr.decided)      // a clear turn does
        XCTAssertEqual(cMajor, tr.update(aMinor, 2.0)); XCTAssertEqual(cMajor, tr.decided)
        XCTAssertEqual(cMajor, tr.update(cMajor, 0.8)); XCTAssertEqual(cMajor, tr.decided) // weaker, still held
        tr.update(cMajor, 0.2); XCTAssertNil(tr.decided)                                // faded: undecided
        XCTAssertEqual(aMinor, tr.update(cMajor, -2.0)); XCTAssertEqual(aMinor, tr.decided)
        // A different pair starts afresh.
        XCTAssertEqual(Key(7, .major), tr.update(Key(7, .major), 0.5)); XCTAssertNil(tr.decided)
    }
}
