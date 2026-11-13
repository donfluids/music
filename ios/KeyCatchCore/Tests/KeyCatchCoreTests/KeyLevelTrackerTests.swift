import Foundation
import XCTest
@testable import KeyCatchCore

final class KeyLevelTrackerTests: XCTestCase {
    // A C major tune, 32 notes.
    private let tune = [55, 60, 64, 60, 64, 62, 60, 57, 55, 55, 60, 64, 60, 64, 62, 67, 64, 67, 64, 60, 64, 62, 60, 57, 55, 55, 60, 64, 60, 64, 62, 60]

    /// The tune sung [times] over, with the whole level at [levelCents] (time) above C.
    private func sing(times: Int = 2, _ levelCents: (Double) -> Double) -> [SungNote] {
        var out: [SungNote] = []
        var t = 0.0
        for _ in 0..<times {
            for m in tune {
                out.append(SungNote(pitch: Double(m) + levelCents(t) / 100.0, startSeconds: t, durationSeconds: 0.45, gridCents: 0.0))
                t += 0.5
            }
        }
        return out
    }

    func testASteadyTuneSitsInOneKey() {
        let l = KeyLevelTracker().analyse(sing { _ in 0.0 })!
        XCTAssertEqual(0.0, l.centreCents, accuracy: 15.0)
        XCTAssertEqual(0, l.nowShift); XCTAssertEqual(0, l.lowShift); XCTAssertEqual(0, l.highShift)
        XCTAssertEqual(KeyLevelTracker.Move.steady, l.move)
        // The notes come back as sung, exactly in tune.
        XCTAssertEqual(tune, l.notes.prefix(tune.count).map { $0.midi })
    }

    func testASteadyTuneSungFortyCentsSharpIsStillOneKey() {
        let l = KeyLevelTracker().analyse(sing { _ in 40.0 })!
        XCTAssertEqual(40.0, l.centreCents, accuracy: 15.0)
        XCTAssertEqual(0, l.nowShift)
        XCTAssertTrue(l.notes.allSatisfy { abs($0.pitch - Double(kotlinRound($0.pitch))) < 0.15 })
    }

    func testASlowCreepIsFollowedAcrossTheSemitone() {
        // C rising steadily to C# plus 50 over 32 seconds: the curve passes the semitone.
        let l = KeyLevelTracker().analyse(sing { t in 150.0 * t / 32.0 })!
        XCTAssertTrue((110.0...170.0).contains(l.nowCents), "now \(l.nowCents)")
        XCTAssertEqual(1, kotlinRound(l.nowCents / 100.0), "now nearest C#")
        // Half the time below C# minus 50, half above: the centre is C#, not C.
        XCTAssertEqual(1, kotlinRound(l.centreCents / 100.0), "centre \(l.centreCents)")
        XCTAssertEqual(KeyLevelTracker.Move.rising, l.move)
        // Moved back to the centre, the tune reads as one key again: the same notes each time round.
        let first = l.notes.prefix(tune.count).map { $0.midi - l.notes[0].midi }
        let second = l.notes.dropFirst(tune.count).map { $0.midi - l.notes[0].midi }
        XCTAssertEqual(first, second)
    }

    func testWanderingBetweenCAndDCentresOnCSharp() {
        let l = KeyLevelTracker().analyse(sing(times: 3) { t in 100.0 + 100.0 * sin(2 * Double.pi * t / 24.0) })!
        // The centre sits on a semitone (the wobble is symmetric), and the range spans one either side.
        let fine = l.centreCents - 100.0 * Double(kotlinRound(l.centreCents / 100.0))
        XCTAssertEqual(0.0, fine, accuracy: 25.0, "centre \(l.centreCents)")
        XCTAssertTrue(l.lowShift <= -1 && l.highShift >= 1, "range \(l.lowShift)..\(l.highShift)")
        // Moved back to the centre, the tune sits a semitone above how it was written: C#.
        XCTAssertEqual(1, floorMod(l.notes[1].midi - tune[1], 12))
    }

    func testACleanStepUpThatHoldsIsANewKey() {
        // Two times round in C, then the last time a semitone up, held.
        let l = KeyLevelTracker().analyse(sing(times: 3) { t in t < 32.0 ? 0.0 : 100.0 })!
        XCTAssertEqual(KeyLevelTracker.Move.settled, l.move)
        XCTAssertEqual(1, l.nowShift)
        XCTAssertEqual(0, kotlinRound(l.centreCents / 100.0))
    }
}
