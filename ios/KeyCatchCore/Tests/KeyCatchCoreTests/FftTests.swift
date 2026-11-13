import XCTest
@testable import KeyCatchCore

final class FftTests: XCTestCase {
    func testMatchesNaiveDft() {
        let n = 64
        var rnd = KotlinRandom(7)
        var re = (0..<n).map { _ in rnd.nextFloat() * 2 - 1 }
        var im = (0..<n).map { _ in rnd.nextFloat() * 2 - 1 }
        var expectedRe = [Double](repeating: 0, count: n)
        var expectedIm = [Double](repeating: 0, count: n)
        for k in 0..<n {
            for t in 0..<n {
                let angle = -2 * Double.pi * Double(k) * Double(t) / Double(n)
                expectedRe[k] += Double(re[t]) * cos(angle) - Double(im[t]) * sin(angle)
                expectedIm[k] += Double(re[t]) * sin(angle) + Double(im[t]) * cos(angle)
            }
        }
        Fft(n).transform(&re, &im)
        for k in 0..<n {
            XCTAssertEqual(expectedRe[k], Double(re[k]), accuracy: 1e-3)
            XCTAssertEqual(expectedIm[k], Double(im[k]), accuracy: 1e-3)
        }
    }

    func testSinePeaksAtItsBin() {
        let n = 1024
        let bin = 37
        var re = (0..<n).map { Float(sin(2 * Double.pi * Double(bin) * Double($0) / Double(n))) }
        var im = [Float](repeating: 0, count: n)
        Fft(n).transform(&re, &im)
        var best = 0
        for k in 1..<(n / 2) where re[k] * re[k] + im[k] * im[k] > re[best] * re[best] + im[best] * im[best] { best = k }
        XCTAssertEqual(bin, best)
    }
}

/// kotlin.random.Random(seed) (XorWow), so the test sees the same random input as the Kotlin one.
private struct KotlinRandom {
    private var x: Int32, y: Int32, z: Int32, w: Int32, v: Int32, addend: Int32

    init(_ seed: Int32) {
        let seed1 = seed, seed2 = seed >> 31
        x = seed1; y = seed2; z = 0; w = 0; v = ~seed1
        addend = (seed1 << 10) ^ Int32(bitPattern: UInt32(bitPattern: seed2) >> 4)
        for _ in 0..<64 { _ = nextInt() }
    }

    mutating func nextInt() -> Int32 {
        var t = x
        t = t ^ Int32(bitPattern: UInt32(bitPattern: t) >> 2)
        x = y; y = z; z = w
        let v0 = v
        w = v0
        t = (t ^ (t << 1)) ^ v0 ^ (v0 << 4)
        v = t
        addend = addend &+ 362437
        return t &+ addend
    }

    mutating func nextFloat() -> Float {
        let bits = Int32(bitPattern: UInt32(bitPattern: nextInt()) >> 8) // takeUpperBits(24)
        return Float(bits) / Float(1 << 24)
    }
}
