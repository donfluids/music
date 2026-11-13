import Foundation
@testable import KeyCatchCore

/// Synthetic audio for tests: voice-like tones and click tracks.
enum Synth {
    static let sampleRate = 44100

    static func midiToHz(_ midi: Int) -> Double { 440.0 * pow(2.0, Double(midi - 69) / 12.0) }

    /// A sustained chord made of harmonic-rich tones.
    static func chord(_ midiNotes: [Int], _ seconds: Double, amplitude: Float = 0.2) -> [Float] {
        let n = Int(seconds * Double(sampleRate))
        var out = [Float](repeating: 0, count: n)
        let harmonics: [Double] = [1.0, 0.5, 0.3, 0.2, 0.1]
        for midi in midiNotes {
            let f = midiToHz(midi)
            for i in 0..<n {
                let t = Double(i) / Double(sampleRate)
                var v = 0.0
                for (h, a) in harmonics.enumerated() { v += a * sin(2 * Double.pi * f * Double(h + 1) * t) }
                out[i] += Float(v * Double(amplitude) / Double(midiNotes.count))
            }
        }
        return out
    }

    /// Several chords back to back.
    static func progression(_ chords: [[Int]], secondsEach: Double) -> [Float] {
        chords.map { chord($0, secondsEach) }.reduce([Float]()) { $0 + $1 }
    }

    /// Clicks at `bpm`. `accents` gives the amplitude of each beat in the bar, cycled.
    /// `subdivisions` adds quieter clicks between beats (2 = eighths, 3 = triplets).
    static func clickTrack(
        _ bpm: Double,
        _ seconds: Double,
        accents: [Float] = [1],
        subdivisions: Int = 0,
        subdivisionAmplitude: Float = 0.3
    ) -> [Float] {
        let n = Int(seconds * Double(sampleRate))
        var out = [Float](repeating: 0, count: n)
        let period = 60.0 / bpm * Double(sampleRate)
        var beat = 0
        var t = 0.0
        while t < Double(n) {
            addClick(&out, Int(t), accents[beat % accents.count])
            if subdivisions > 1 {
                for s in 1..<subdivisions { addClick(&out, Int(t + period * Double(s) / Double(subdivisions)), subdivisionAmplitude) }
            }
            beat += 1
            t += period
        }
        return out
    }

    static func click(_ out: inout [Float], _ start: Int, _ amplitude: Float) { addClick(&out, start, amplitude) }

    private static func addClick(_ out: inout [Float], _ start: Int, _ amplitude: Float) {
        let length = Int(0.03 * Double(sampleRate))
        for i in 0..<length {
            let idx = start + i
            if idx >= out.count { break }
            let env = exp(-Double(i) / (0.005 * Double(sampleRate)))
            out[idx] += Float(Double(amplitude) * env * sin(2 * Double.pi * 1500.0 * Double(i) / Double(sampleRate)))
        }
    }

    static func mix(_ a: [Float], _ b: [Float]) -> [Float] {
        let n = max(a.count, b.count)
        return (0..<n).map { (($0 < a.count) ? a[$0] : 0) + (($0 < b.count) ? b[$0] : 0) }
    }

    // `analyze(audio, analyzer:, onPublish:)` (feeds audio through a MusicAnalyzer in 1024-sample
    // chunks and returns the final AnalysisResult) is added in a separate extension file once
    // MusicAnalyzer is ported.
}
