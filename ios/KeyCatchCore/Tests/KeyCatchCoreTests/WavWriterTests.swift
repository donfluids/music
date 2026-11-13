import XCTest
@testable import KeyCatchCore

final class WavWriterTests: XCTestCase {
    func testRoundTrip() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("livekey-\(UUID().uuidString).wav")
        defer { try? FileManager.default.removeItem(at: file) }
        let samples = (0..<3000).map { Float(0.5 * sin(2 * Double.pi * 440 * Double($0) / 44100.0)) }
        let w = try WavWriter(file, 44100)
        try w.write(samples, 1000)
        try w.write(Array(samples[1000..<3000]), 2000)
        XCTAssertEqual(3000, w.sampleCount)
        try w.close()
        let length = try FileManager.default.attributesOfItem(atPath: file.path)[.size] as! NSNumber
        XCTAssertEqual(WavWriter.headerSize + 6000, length.intValue)
        let (rate, back) = try WavWriter.read(file)
        XCTAssertEqual(44100, rate)
        XCTAssertEqual(3000, back.count)
        for i in samples.indices { XCTAssertEqual(samples[i], back[i], accuracy: 1 / 32768) }
    }
}
