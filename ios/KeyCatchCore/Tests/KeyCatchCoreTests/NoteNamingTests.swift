import XCTest
@testable import KeyCatchCore

final class NoteNamingTests: XCTestCase {
    private let doReMi = NoteNaming.fixedDo

    func testLettersAreUnchanged() {
        XCTAssertEqual("F#m7", NoteNaming.letters.of("F#m7"))
        XCTAssertEqual("Bb major", NoteNaming.letters.label(Key(10, .major)))
    }

    func testFixedDoKeepsAccidentalsAndSuffixes() {
        XCTAssertEqual("Do", doReMi.of("C"))
        XCTAssertEqual("Fa#m7", doReMi.of("F#m7"))
        XCTAssertEqual("Sib", doReMi.of("Bb"))
        XCTAssertEqual("Solsus4", doReMi.of("Gsus4"))
    }

    func testKeysAndChordsFollowTheKeySpelling() {
        XCTAssertEqual("Mib major", doReMi.label(Key(3, .major)))
        XCTAssertEqual("Re# minor", doReMi.label(Key(3, .minor)))
        XCTAssertEqual("Lab", doReMi.chord(ChordLabel(8, .major), key: Key(3, .major)))
        XCTAssertEqual("Sol#", doReMi.chord(ChordLabel(8, .major), key: nil))
    }

    func testUnknownIdsFallBackToLetters() {
        XCTAssertEqual(NoteNaming.fixedDo, NoteNaming.byId("fixed_do"))
        XCTAssertEqual(NoteNaming.letters, NoteNaming.byId(nil))
        XCTAssertEqual(NoteNaming.letters, NoteNaming.byId("german"))
    }
}
