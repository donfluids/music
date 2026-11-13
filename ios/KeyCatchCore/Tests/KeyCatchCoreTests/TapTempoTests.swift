import XCTest
@testable import KeyCatchCore

final class TapTempoTests: XCTestCase {
    private let ms = 1_000_000

    func testTwoTapsGiveATempoAndMoreTapsSteadyIt() {
        let t = TapTempo()
        XCTAssertNil(t.tap(0))
        XCTAssertEqual(120, t.tap(500 * ms)!.bpm, accuracy: 0.01)
        // Human taps wobble; the reading stays close.
        var r: TapTempo.Reading? = nil
        for (i, wobble) in [12, -9, 15, -11, 6].enumerated() { r = t.tap((1000 + i * 500 + wobble) * ms) }
        XCTAssertEqual(120, r!.bpm, accuracy: 3)
        XCTAssertEqual(7, r!.taps)
    }

    func testOneStrayTapDoesNotThrowTheTempo() {
        let t = TapTempo()
        [0, 600, 1200, 1800, 2160, 3000, 3600].forEach { t.tap($0 * ms) } // the fifth tap is early
        XCTAssertEqual(100, t.tap(4200 * ms)!.bpm, accuracy: 2)
    }

    func testAPauseStartsANewCountAndABounceIsIgnored() {
        let t = TapTempo()
        t.tap(0); t.tap(500 * ms); t.tap(1000 * ms)
        // Three seconds later: a fresh count at a new tempo.
        XCTAssertNil(t.tap(4000 * ms))
        XCTAssertEqual(60, t.tap(5000 * ms)!.bpm, accuracy: 0.01)
        // A finger bouncing 40 ms later changes nothing.
        XCTAssertEqual(60, t.tap(5040 * ms)!.bpm, accuracy: 0.01)
        XCTAssertEqual(2, t.tap(5040 * ms)!.taps)
    }

    func testClearForgetsTheTaps() {
        let t = TapTempo()
        t.tap(0); t.tap(500 * ms)
        t.clear()
        XCTAssertNil(t.tap(1000 * ms))
    }
}
