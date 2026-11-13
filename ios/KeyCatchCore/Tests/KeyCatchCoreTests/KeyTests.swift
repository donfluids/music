import XCTest
@testable import KeyCatchCore

final class KeyTests: XCTestCase {
    func testSpellingFollowsKeySignature() {
        XCTAssertEqual(["G", "A", "B", "C", "D", "E", "F#"], Key(7, .major).scaleNames)
        XCTAssertEqual(["F", "G", "A", "Bb", "C", "D", "E"], Key(5, .major).scaleNames)
        XCTAssertEqual(["Eb", "F", "G", "Ab", "Bb", "C", "D"], Key(3, .major).scaleNames)
        XCTAssertEqual(["D", "E", "F", "G", "A", "Bb", "C"], Key(2, .minor).scaleNames)
    }

    func testRelativeKeys() {
        XCTAssertEqual(Key(9, .minor), Key(0, .major).relative)
        XCTAssertEqual(Key(0, .major), Key(9, .minor).relative)
        XCTAssertEqual("E minor", Key(7, .major).relative.label)
    }

    func testDiatonicChords() {
        let c = Key(0, .major).chords
        XCTAssertEqual(["C", "Dm", "Em", "F", "G", "Am", "B°"], c.map { $0.name })
        XCTAssertEqual(["I", "ii", "iii", "IV", "V", "vi", "vii°"], c.map { $0.numeral })
        let am = Key(9, .minor).chords
        XCTAssertEqual(["Am", "B°", "C", "Dm", "Em", "F", "G"], am.map { $0.name })
    }

    func testSignatures() {
        XCTAssertEqual(0, Key(0, .major).signature)
        XCTAssertEqual(2, Key(2, .major).signature)
        XCTAssertEqual(-3, Key(3, .major).signature)
        XCTAssertEqual(-3, Key(0, .minor).signature)
        XCTAssertEqual(6, Key(6, .major).signature)
    }
}
