import Foundation
import XCTest
@testable import KeyCatchCore

final class ChordTests: XCTestCase {

    private func seg(_ chord: ChordLabel?, _ start: Float, _ end: Float) -> ChordSegment { ChordSegment(chord, start, end) }
    private func maj(_ root: Int) -> ChordLabel { ChordLabel(root, minor: false) }
    private func min(_ root: Int) -> ChordLabel { ChordLabel(root, minor: true) }
    private func chroma(_ pcWeights: (Int, Float)...) -> [Float] {
        var c = [Float](repeating: 0, count: 12)
        for (pc, w) in pcWeights { c[pc] = w }
        return c
    }

    func testTemplateMatchingOnCleanChroma() {
        let t = ChordTemplates()
        XCTAssertEqual(maj(0), t.match(chroma((0, 1), (4, 0.8), (7, 0.9)))!.first)
        XCTAssertEqual(min(9), t.match(chroma((9, 1), (0, 0.8), (4, 0.9)))!.first)
        XCTAssertEqual(ChordLabel(7, .dominantSeventh), t.match(chroma((7, 1), (11, 0.8), (2, 0.9), (5, 0.7)))!.first)
        XCTAssertEqual(ChordLabel(0, .majorSeventh), t.match(chroma((0, 1), (4, 0.8), (7, 0.9), (11, 0.7)))!.first)
        XCTAssertEqual(ChordLabel(2, .minorSeventh), t.match(chroma((2, 1), (5, 0.8), (9, 0.9), (0, 0.7)))!.first)
        XCTAssertEqual(ChordLabel(2, .sus4), t.match(chroma((2, 1), (7, 0.8), (9, 0.9)))!.first)
        // A triad with a little overtone energy on the seventh stays a triad.
        XCTAssertEqual(maj(0), t.match(chroma((0, 1), (4, 0.8), (7, 0.9), (10, 0.2)))!.first)
        XCTAssertNil(t.match([Float](repeating: 1, count: 12)))
    }

    func testNumeralsAndNames() {
        let g = Key(7, .major)
        XCTAssertEqual("I", maj(7).numeral(in: g))
        XCTAssertEqual("IV", maj(0).numeral(in: g))
        XCTAssertEqual("V7", ChordLabel(2, .dominantSeventh).numeral(in: g))
        XCTAssertEqual("vi7", ChordLabel(4, .minorSeventh).numeral(in: g))
        XCTAssertEqual("bVII", maj(5).numeral(in: g))
        XCTAssertEqual("Vsus4", ChordLabel(2, .sus4).numeral(in: g))
        XCTAssertEqual("i", min(4).numeral(in: Key(4, .minor)))
        XCTAssertEqual("Bb", maj(10).name(key: Key(5, .major)))
        XCTAssertEqual("A#m7", ChordLabel(10, .minorSeventh).name(key: nil))
        XCTAssertEqual(maj(0), ChordLabel(0, .majorSeventh).triad)
    }

    func testTrackerRemovesOneOffMisreads() {
        let tracker = ChordTracker(lag: 4)
        let c = chroma((0, 1), (4, 0.8), (7, 0.9))
        let g = chroma((7, 1), (11, 0.8), (2, 0.9))
        let glitch = chroma((1, 1), (5, 0.8), (8, 0.9)) // Db, one observation only
        let sequence = Array(repeating: c, count: 10) + [glitch] + Array(repeating: c, count: 10) + Array(repeating: g, count: 10)
        var decided: [ChordLabel?] = []
        for obs in sequence { if tracker.observe(obs, 0.2) { decided.append(tracker.decided) } }
        XCTAssertEqual(sequence.count - tracker.lag + 1, decided.count)
        XCTAssertTrue(!decided.contains { $0 == maj(1) }, "glitch survived: \(decided)")
        XCTAssertEqual(maj(0), decided.first!)
        XCTAssertEqual(maj(7), decided.last!)
        // Exactly one change, from C to G.
        let changes = zip(decided, decided.dropFirst()).filter { $0 != $1 }.count
        XCTAssertEqual(1, changes)
    }

    func testTrackerReportsSilenceAsNoChord() {
        let tracker = ChordTracker(lag: 2)
        for _ in 0..<6 { tracker.observe([Float](repeating: 0, count: 12), 0.2) }
        XCTAssertTrue(tracker.hasDecision)
        XCTAssertNil(tracker.decided)
    }

    func testCadenceDecidesBetweenRelativeKeys() {
        var segments: [ChordSegment] = []
        var t: Float = 0
        for _ in 0..<4 {
            for root in [0, 5, 7, 0] { segments.append(seg(maj(root), t, t + 2)); t += 2 }
        }
        let ranked = ChordKeyEstimator.suggest(segments)
        XCTAssertEqual(Key(0, .major), ranked[0].key)
        XCTAssertTrue(ranked[0].cadences >= 4)
        XCTAssertTrue(ChordKeyEstimator.confidence(ranked) > 0.4)
    }

    func testDominantSeventhCountsAsCadence() {
        let segments = [
            seg(maj(0), 0, 2), seg(maj(5), 2, 4), seg(ChordLabel(7, .dominantSeventh), 4, 6), seg(maj(0), 6, 8),
            seg(maj(0), 8, 10), seg(maj(5), 10, 12), seg(ChordLabel(7, .dominantSeventh), 12, 14), seg(maj(0), 14, 16),
        ]
        let ranked = ChordKeyEstimator.suggest(segments)
        XCTAssertEqual(Key(0, .major), ranked[0].key)
        XCTAssertEqual(2, ranked[0].cadences)
    }

    func testHarmonicMinorProgressionIsMinor() {
        var segments: [ChordSegment] = []
        var t: Float = 0
        for _ in 0..<3 {
            for c in [min(9), min(2), maj(4), min(9)] { segments.append(seg(c, t, t + 2)); t += 2 }
        }
        XCTAssertEqual(Key(9, .minor), ChordKeyEstimator.suggest(segments)[0].key)
    }

    func testWorshipProgressionWithFlatSeven() {
        let segments = [
            seg(maj(7), 0, 4), seg(maj(0), 4, 8), seg(maj(2), 8, 12), seg(min(4), 12, 16),
            seg(maj(7), 16, 20), seg(maj(5), 20, 22), seg(maj(0), 22, 24), seg(maj(7), 24, 28),
        ]
        XCTAssertEqual(Key(7, .major), ChordKeyEstimator.suggest(segments)[0].key)
    }

    // analyzerTracksChordSequenceAndSuggestsKey, analyzerHearsSeventhChords and
    // chordBoundariesFollowTheBeatWhenTempoIsKnown run the whole MusicAnalyzer and are ported with it.
}
