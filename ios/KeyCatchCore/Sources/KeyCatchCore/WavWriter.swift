import Foundation

/// Streams 16-bit mono PCM to a WAV file through a buffered stream; the header is patched on
/// close. Foundation only so it can be unit-tested anywhere. Not thread-safe: use from one thread.
public final class WavWriter {
    /// A file that is not a 16-bit PCM WAV, or is cut short.
    public struct FormatError: Error, CustomStringConvertible {
        public let description: String
    }

    public let file: URL
    private let sampleRate: Int
    private let handle: FileHandle
    private var buffer = [UInt8](repeating: 0, count: 8192)
    /// Bytes waiting to go to the file (the Kotlin BufferedOutputStream's 64 KiB buffer).
    private var pending = Data()
    private static let pendingCapacity = 64 * 1024
    private var bytesWritten = 0
    private var closed = false

    /// Samples written so far.
    public var sampleCount: Int { bytesWritten / 2 }

    public static let headerSize = 44

    public init(_ file: URL, _ sampleRate: Int) throws {
        self.file = file
        self.sampleRate = sampleRate
        // Truncate and write a placeholder header, patched on close.
        try Data(count: WavWriter.headerSize).write(to: file)
        handle = try FileHandle(forWritingTo: file)
        try handle.seekToEnd()
        pending.reserveCapacity(WavWriter.pendingCapacity)
    }

    /// Appends `count` samples in -1..1 from `samples`.
    public func write(_ samples: [Float], _ count: Int) throws {
        var i = 0
        while i < count {
            let n = min(count - i, buffer.count / 2)
            for j in 0..<n {
                let x = samples[i + j] * 32768
                // Float.toInt() in Kotlin truncates toward zero and saturates; NaN becomes 0.
                let v = x.isNaN ? 0 : (x >= 32767 ? 32767 : (x <= -32768 ? -32768 : Int(x)))
                buffer[2 * j] = UInt8(v & 0xFF)
                buffer[2 * j + 1] = UInt8((v >> 8) & 0xFF)
            }
            if pending.count + n * 2 > WavWriter.pendingCapacity { try flush() }
            pending.append(contentsOf: buffer[0..<(n * 2)])
            bytesWritten += n * 2
            i += n
        }
    }

    private func flush() throws {
        if pending.isEmpty { return }
        try handle.write(contentsOf: pending)
        pending.removeAll(keepingCapacity: true)
    }

    public func close() throws {
        if closed { return }
        closed = true
        try flush()
        try handle.close()
        let patch = try FileHandle(forUpdating: file)
        defer { try? patch.close() }
        try patch.seek(toOffset: 0)
        try patch.write(contentsOf: Data(WavWriter.header(sampleRate, bytesWritten)))
    }

    public static func header(_ sampleRate: Int, _ dataBytes: Int) -> [UInt8] {
        var h = [UInt8](repeating: 0, count: headerSize)
        func str(_ offset: Int, _ s: String) { for (i, c) in s.utf8.enumerated() { h[offset + i] = c } }
        func int32(_ offset: Int, _ v: Int) { for i in 0..<4 { h[offset + i] = UInt8((v >> (8 * i)) & 0xFF) } }
        func int16(_ offset: Int, _ v: Int) { h[offset] = UInt8(v & 0xFF); h[offset + 1] = UInt8((v >> 8) & 0xFF) }
        str(0, "RIFF"); int32(4, 36 + dataBytes); str(8, "WAVE")
        str(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1)
        int32(24, sampleRate); int32(28, sampleRate * 2); int16(32, 2); int16(34, 16)
        str(36, "data"); int32(40, dataBytes)
        return h
    }

    /// Reads a 16-bit PCM WAV back into floats (first channel only).
    public static func read(_ file: URL) throws -> (sampleRate: Int, samples: [Float]) {
        let bytes = [UInt8](try Data(contentsOf: file))
        guard bytes.count >= headerSize, tag(bytes, 0) == "RIFF" else { throw FormatError(description: "Not a WAV file: \(file.path)") }
        var pos = 12
        var sampleRate = 0
        var channels = 1
        var bits = 16
        var format = 1
        while pos + 8 <= bytes.count {
            let id = tag(bytes, pos)
            let size = le32(bytes, pos + 4)
            guard size >= 0 else { throw FormatError(description: "Bad chunk size in \(file.path)") }
            if id == "fmt " {
                guard pos + 24 <= bytes.count else { throw FormatError(description: "Truncated fmt chunk in \(file.path)") }
                format = le16(bytes, pos + 8)
                channels = le16(bytes, pos + 10)
                sampleRate = le32(bytes, pos + 12)
                bits = le16(bytes, pos + 22)
            } else if id == "data" {
                guard format == 1 && bits == 16 && channels > 0 else { throw FormatError(description: "Only 16-bit PCM WAV is supported: \(file.path)") }
                let count = size / 2 / channels
                guard count >= 0, count == 0 || pos + 8 + (count - 1) * 2 * channels + 2 <= bytes.count else {
                    throw FormatError(description: "Truncated data chunk in \(file.path)")
                }
                var out = [Float](repeating: 0, count: count)
                for i in 0..<count {
                    let p = pos + 8 + i * 2 * channels
                    out[i] = Float(Int16(truncatingIfNeeded: le16(bytes, p))) / 32768
                }
                return (sampleRate, out)
            }
            pos += 8 + size + (size & 1)
        }
        throw FormatError(description: "No data chunk in \(file.path)")
    }

    private static func tag(_ b: [UInt8], _ p: Int) -> String { String(decoding: b[p..<(p + 4)], as: UTF8.self) }
    private static func le16(_ b: [UInt8], _ p: Int) -> Int { Int(b[p]) | (Int(b[p + 1]) << 8) }
    /// Signed 32-bit, as the Kotlin Int it mirrors.
    private static func le32(_ b: [UInt8], _ p: Int) -> Int { Int(Int32(truncatingIfNeeded: le16(b, p) | (le16(b, p + 2) << 16))) }
}
