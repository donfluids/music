import Foundation
import XCTest
@testable import KeyCatchCore

final class SongHistoryTests: XCTestCase {
    private let g = Key(7, .major)
    private let em = Key(4, .minor)

    private func tempFile(_ name: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(name)
    }

    func testSurvivesARestart() throws {
        let file = tempFile("history.json")
        let a = SongRecord(atMillis: 1000, key: g, transpose: 2, bpm: 96, bpmTapped: true, seconds: 200, presetId: "voices")
        let b = SongRecord(atMillis: 2000, key: em, bpm: 120, meter: "6/8", seconds: 180, presetId: "band")
        let h = SongHistory(url: file)
        try h.add(a); try h.add(b)
        XCTAssertEqual(SongHistory(url: file).all(), [b, a])
    }

    func testKeepsTheNewestUpToTheLimit() throws {
        let h = SongHistory(url: tempFile("h.json"), limit: 3)
        for i in 1...5 { try h.add(SongRecord(atMillis: i, key: g)) }
        XCTAssertEqual(h.all().map(\.atMillis), [5, 4, 3])
    }

    func testRemovesOneOrAll() throws {
        let file = tempFile("h.json")
        let h = SongHistory(url: file)
        for i in 1...3 { try h.add(SongRecord(atMillis: i, key: g)) }
        XCTAssertEqual(try h.remove(2).map(\.atMillis), [3, 1])
        XCTAssertTrue(try h.clear().isEmpty)
        XCTAssertTrue(SongHistory(url: file).all().isEmpty)
    }

    func testAnUndoneDeleteGoesBackInPlace() throws {
        let h = SongHistory(url: tempFile("h.json"))
        let songs = (1...3).map { SongRecord(atMillis: $0, key: g) }
        for s in songs { try h.add(s) }
        try h.remove(2)
        XCTAssertEqual(try h.add(songs[1]).map(\.atMillis), [3, 2, 1])
    }

    func testADamagedFileStartsEmpty() throws {
        let file = tempFile("h.json")
        try Data("{not json".utf8).write(to: file)
        XCTAssertTrue(SongHistory(url: file).all().isEmpty)
    }

    func testSavesWhatIsOnScreen() {
        var a = AnalysisResult()
        a.secondsHeard = 95; a.displayKey = g; a.key = em; a.bpm = 62; a.tempoLocked = true; a.meterLabel = "4/4"
        let r = SongRecord.from(analysis: a, heldKey: nil, preset: .band, tapBpm: nil, tempoMultiplier: 2, transpose: -1, nowMillis: 42)
        XCTAssertEqual(r, SongRecord(atMillis: 42, key: g, transpose: -1, bpm: 124, bpmTapped: false, meter: "4/4", seconds: 95, presetId: "band"))
        // Hold wins over the analysis.
        XCTAssertEqual(SongRecord.from(analysis: a, heldKey: em, preset: .band, tapBpm: nil, tempoMultiplier: 2, transpose: -1, nowMillis: 0)?.key, em)
    }

    func testVoicesSaveTheTappedTempoAndNoMetre() {
        var a = AnalysisResult()
        a.secondsHeard = 60; a.displayKey = g; a.bpm = 140; a.tempoLocked = true; a.meterLabel = "3/4"
        let r = SongRecord.from(analysis: a, heldKey: nil, preset: .voices, tapBpm: 71.6, tempoMultiplier: 1, transpose: 0, nowMillis: 0)!
        XCTAssertEqual(r.bpm, 72)
        XCTAssertTrue(r.bpmTapped)
        XCTAssertNil(r.meter)
        XCTAssertNil(SongRecord.from(analysis: a, heldKey: nil, preset: .voices, tapBpm: nil, tempoMultiplier: 1, transpose: 0, nowMillis: 0)!.bpm)
    }

    func testFalseStartsAreNotSaved() {
        var a = AnalysisResult()
        a.secondsHeard = 60
        XCTAssertNil(SongRecord.from(analysis: a, heldKey: nil, preset: .band, tapBpm: nil, tempoMultiplier: 1, transpose: 0, nowMillis: 0))
        a.secondsHeard = 10; a.displayKey = g
        XCTAssertNil(SongRecord.from(analysis: a, heldKey: nil, preset: .band, tapBpm: nil, tempoMultiplier: 1, transpose: 0, nowMillis: 0))
    }
}
