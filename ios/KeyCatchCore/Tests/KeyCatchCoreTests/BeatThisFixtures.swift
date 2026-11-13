import Foundation
import XCTest
@testable import KeyCatchCore

/// Reference data for the Beat This! tests, written by tools/beatthis/make_fixtures.py, and the
/// synthetic signals it was made from. The signals are twins of make_fixtures.py's synth_signal
/// and synth_band and must give the same samples.
enum BeatThisFixtures {
    static func json(_ name: String) throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Resources"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    /// A little-endian float16 file as Floats.
    static func halves(_ name: String) throws -> [Float] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "bin", subdirectory: "Resources"))
        let data = try Data(contentsOf: url)
        var out = [Float](repeating: 0, count: data.count / 2)
        data.withUnsafeBytes { raw in
            for i in out.indices { out[i] = halfToFloat(UInt16(raw[2 * i]) | (UInt16(raw[2 * i + 1]) << 8)) }
        }
        return out
    }

    static func halfToFloat(_ h: UInt16) -> Float {
        let sign: Float = h >> 15 == 0 ? 1 : -1
        let exponent = Int((h >> 10) & 0x1F)
        let mantissa = Float(h & 0x3FF)
        if exponent == 0 { return sign * mantissa * powf(2, -24) }
        if exponent == 31 { return mantissa == 0 ? sign * .infinity : .nan }
        return sign * (1 + mantissa / 1024) * powf(2, Float(exponent - 15))
    }

    /// Numerical Recipes' 32-bit LCG, as make_fixtures.py's Lcg.
    struct Lcg {
        var s: UInt32
        mutating func next() -> Double {
            s = s &* 1664525 &+ 1013904223
            return Double(s) / 4294967296.0 * 2.0 - 1.0
        }
    }

    /// make_fixtures.py synth_signal: two tones, a chirp and noise bursts at 22,050 Hz.
    static func signal(_ n: Int) -> [Float] {
        var rng = Lcg(s: 1)
        return (0..<n).map { i in
            let t = Double(i) / 22050.0
            var v = 0.3 * sin(2 * Double.pi * 440.0 * t) + 0.1 * sin(2 * Double.pi * 6000.0 * t)
            v += 0.2 * sin(2 * Double.pi * (100.0 * t + 1500.0 * t * t))
            let noise = rng.next()
            if (i / 2205) % 3 == 1 { v += 0.25 * noise }
            return Float(v)
        }
    }

    static func midiHz(_ m: Int) -> Double { 440.0 * pow(2.0, Double(m - 69) / 12.0) }

    /// make_fixtures.py synth_band: a loud 4/4 groove at 100 BPM, 44.1 kHz.
    static func band(rate: Int = 44100, seconds: Double = 12.0, bpm: Double = 100.0) -> [Float] {
        var rng = Lcg(s: 7)
        let n = Int(seconds * Double(rate))
        let beat = 60.0 / bpm
        let roots = [36, 33, 29, 31]
        var prev = 0.0
        var out = [Float](repeating: 0, count: n)
        for i in 0..<n {
            let t = Double(i) / Double(rate)
            let b = Int((t / beat).rounded(.down))
            let tb = t - Double(b) * beat
            let pos = b % 4
            let bar = b / 4
            let half = beat / 2.0
            let te = t - (t / half).rounded(.down) * half
            let noise = rng.next()
            var v = 0.0
            if pos == 0 || pos == 2 {
                v += 0.8 * sin(2 * Double.pi * (45.0 * tb + 120.0 * 0.03 * (1.0 - exp(-tb / 0.03)))) * exp(-tb / 0.2)
            } else {
                v += (0.5 * noise + 0.3 * sin(2 * Double.pi * 190.0 * tb)) * exp(-tb / 0.08)
            }
            v += 0.15 * (noise - prev) * exp(-te / 0.015)
            prev = noise
            let root = roots[bar % 4]
            let f = midiHz(root)
            v += 0.35 * (sin(2 * Double.pi * f * tb) + 0.4 * sin(4 * Double.pi * f * tb)) * exp(-tb / 0.5)
            if pos == 0 {
                let third = root == 33 ? 3 : 4
                for m in [root + 24, root + 24 + third, root + 31] {
                    v += 0.08 * (sin(2 * Double.pi * midiHz(m) * tb) + 0.3 * sin(4 * Double.pi * midiHz(m) * tb)) * exp(-tb / 1.0)
                }
            }
            out[i] = Float(0.6 * v)
        }
        return out
    }

    /// A JSON number as a Double, whichever way the platform's parser handed it over.
    static func num(_ x: Any?) -> Double? {
        if let d = x as? Double { return d }
        if let i = x as? Int { return Double(i) }
        if let n = x as? NSNumber { return n.doubleValue }
        return nil
    }

    static func nums(_ x: Any?) -> [Double] { (x as? [Any] ?? []).compactMap { num($0) } }

    /// A stored reading's fields.
    struct Reading {
        let t: Double
        let firstFrame: Int
        let beats: [Double]
        let downbeats: [Double]
        let bpm: Double?
        let period: Double?
        let last: Double?
        let beatsPerBar: Int?
        let index: Int
        let locked: Bool
        let strength: Double
        let scores: [Int: Double]

        init(_ d: [String: Any]) {
            t = BeatThisFixtures.num(d["t"])!
            firstFrame = Int(BeatThisFixtures.num(d["s0"])!)
            beats = BeatThisFixtures.nums(d["beats"])
            downbeats = BeatThisFixtures.nums(d["downbeats"])
            bpm = BeatThisFixtures.num(d["bpm"])
            period = BeatThisFixtures.num(d["period"])
            last = BeatThisFixtures.num(d["last"])
            beatsPerBar = BeatThisFixtures.num(d["bpb"]).map { Int($0) }
            index = Int(BeatThisFixtures.num(d["index"])!)
            locked = d["locked"] as? Bool ?? false
            strength = BeatThisFixtures.num(d["strength"])!
            var s: [Int: Double] = [:]
            for (k, v) in d["scores"] as? [String: Any] ?? [:] { s[Int(k)!] = BeatThisFixtures.num(v) }
            scores = s
        }
    }
}
