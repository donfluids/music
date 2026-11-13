import Foundation

/// In-place iterative radix-2 complex FFT.
///
/// Pure Swift so it can be unit-tested on any platform. Sizes must be a power of two.
public final class Fft {
    public let size: Int
    private let levels: Int
    private let cosTable: [Float]
    private let sinTable: [Float]
    private let bitReversed: [Int]

    public init(_ size: Int) {
        precondition(size >= 2 && (size & (size - 1)) == 0, "FFT size must be a power of two, got \(size)")
        self.size = size
        levels = size.trailingZeroBitCount
        var cosTable = [Float](repeating: 0, count: size / 2)
        var sinTable = [Float](repeating: 0, count: size / 2)
        for i in 0..<(size / 2) {
            let angle = 2.0 * Double.pi * Double(i) / Double(size)
            cosTable[i] = Float(cos(angle))
            sinTable[i] = Float(sin(angle))
        }
        var bitReversed = [Int](repeating: 0, count: size)
        for i in 0..<size {
            // Reverse the low `levels` bits of i (Integer.reverse(i) ushr (32 - levels) in the Kotlin).
            var r = 0
            var v = i
            for _ in 0..<levels { r = (r << 1) | (v & 1); v >>= 1 }
            bitReversed[i] = r
        }
        self.cosTable = cosTable
        self.sinTable = sinTable
        self.bitReversed = bitReversed
    }

    /// Forward transform. `re` and `im` are both length `size` and are overwritten.
    public func transform(_ re: inout [Float], _ im: inout [Float]) {
        precondition(re.count == size && im.count == size)
        re.withUnsafeMutableBufferPointer { r in
            im.withUnsafeMutableBufferPointer { i in transform(r, i) }
        }
    }

    /// Forward transform on raw buffers, for per-frame callers that keep their own storage.
    /// `re` and `im` must each hold at least `size` elements; the first `size` are overwritten.
    public func transform(_ re: UnsafeMutableBufferPointer<Float>, _ im: UnsafeMutableBufferPointer<Float>) {
        precondition(re.count >= size && im.count >= size)
        let size = self.size
        bitReversed.withUnsafeBufferPointer { bitReversed in
            for i in 0..<size {
                let j = bitReversed[i]
                if j > i {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
        }
        cosTable.withUnsafeBufferPointer { cosTable in
            sinTable.withUnsafeBufferPointer { sinTable in
                var halfSize = 1
                while halfSize < size {
                    let fullSize = halfSize * 2
                    let tableStep = size / fullSize
                    var i = 0
                    while i < size {
                        var k = 0
                        for j in i..<(i + halfSize) {
                            let l = j + halfSize
                            let tRe = re[l] * cosTable[k] + im[l] * sinTable[k]
                            let tIm = -re[l] * sinTable[k] + im[l] * cosTable[k]
                            re[l] = re[j] - tRe
                            im[l] = im[j] - tIm
                            re[j] += tRe
                            im[j] += tIm
                            k += tableStep
                        }
                        i += fullSize
                    }
                    halfSize = fullSize
                }
            }
        }
    }

    /// Convenience: windowed magnitude spectrum of a real signal.
    /// Writes size/2 + 1 magnitudes into `out`, scaled so a full-scale sine peaks near 0.5.
    public func magnitudeSpectrum(_ input: [Float], _ window: [Float], _ re: inout [Float], _ im: inout [Float], _ out: inout [Float]) {
        for i in 0..<size {
            re[i] = input[i] * window[i]
            im[i] = 0
        }
        transform(&re, &im)
        let scale: Float = 2 / Float(size)
        for k in 0...(size / 2) {
            out[k] = (re[k] * re[k] + im[k] * im[k]).squareRoot() * scale
        }
    }

    public static func hannWindow(_ size: Int) -> [Float] {
        (0..<size).map { i in Float(0.5 - 0.5 * cos(2.0 * Double.pi * Double(i) / Double(size))) }
    }
}
