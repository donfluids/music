// kckey: runs KeyCatchCore.MusicAnalyzer over raw audio and reports the key readout.
//
// Usage: kckey LISTFILE
// Each line of LISTFILE: <input.f32> <output.jsonl> <sampleRate> <preset id: band|guitar|voices>
// The input is little-endian float32 mono at sampleRate. Audio goes in 1024-sample chunks with
// nowNanos = clock time of the chunk's last sample (clock = 10 s + samples / rate), as kclive does.
// After the chunk that reaches each whole second from 1 s on, one JSON line is written.
// Keys are written as tonic + 12 * minor (C major 0, A minor 21), -1 for none.
// No CREPE or Basic Pitch here (they need Core ML): Voices uses the autocorrelation tracker and
// Band/Guitar the spectral chroma, i.e. the app with "Chords and key" / CREPE switched off.
import Foundation
import KeyCatchCore

let chunk = 1024
let baseNanos = 10_000_000_000
let args = CommandLine.arguments
guard args.count == 2, let list = try? String(contentsOfFile: args[1], encoding: .utf8) else {
    FileHandle.standardError.write("usage: kckey LISTFILE\n".data(using: .utf8)!)
    exit(2)
}

func k(_ key: Key?) -> Int {
    guard let key = key else { return -1 }
    return key.tonic + (key.mode == .minor ? 12 : 0)
}

for line in list.split(separator: "\n") {
    let parts = line.split(separator: " ").map(String.init)
    guard parts.count == 4, let rate = Int(parts[2]) else { continue }
    let data = try! Data(contentsOf: URL(fileURLWithPath: parts[0]))
    let n = data.count / 4
    var audio = [Float](repeating: 0, count: n)
    _ = audio.withUnsafeMutableBytes { data.copyBytes(to: $0) }
    let analyzer = MusicAnalyzer(sampleRate: rate, preset: AnalysisPreset.byId(parts[3]))
    var out = ""
    var nextMoment = 1.0
    var buf = [Float](repeating: 0, count: chunk)
    var fed = 0
    let started = DispatchTime.now().uptimeNanoseconds
    while fed + chunk <= n {
        for i in 0..<chunk { buf[i] = audio[fed + i] }
        fed += chunk
        let now = baseNanos + Int(Double(fed) / Double(rate) * 1e9)
        analyzer.process(buf, count: chunk, nowNanos: now)
        let t = Double(fed) / Double(rate)
        if t >= nextMoment {
            let r = analyzer.result
            let alts = r.keyAlternatives.map { "[\(k($0.0)),\(String(format: "%.4f", Double($0.1)))]" }.joined(separator: ",")
            out += "{\"t\":\(String(format: "%.6f", t)),\"moment\":\(nextMoment),\"key\":\(k(r.key)),\"display\":\(k(r.displayKey)),"
                + "\"fromChords\":\(r.displayKeyFromChords),\"modeDecided\":\(r.modeDecided),\"pairDecided\":\(r.pairDecided),"
                + "\"modeScore\":\(String(format: "%.4f", Double(r.modeScore))),\"keyConf\":\(String(format: "%.4f", Double(r.keyConfidence))),"
                + "\"keyScore\":\(String(format: "%.4f", Double(r.keyScore))),\"songKey\":\(k(r.songKey)),"
                + "\"songConf\":\(String(format: "%.4f", Double(r.songKeyConfidence))),\"keyNow\":\(k(r.keyNow)),"
                + "\"tuning\":\(String(format: "%.2f", Double(r.tuningCents))),\"fromMelody\":\(r.keyFromMelody),\"alts\":[\(alts)]}\n"
            while nextMoment <= t { nextMoment += 1 }
        }
    }
    let ms = Double(DispatchTime.now().uptimeNanoseconds - started) / 1e6
    out += "{\"done\":true,\"seconds\":\(Double(n) / Double(rate)),\"cpuMs\":\(String(format: "%.1f", ms))}\n"
    try! out.write(toFile: parts[1], atomically: true, encoding: .utf8)
}
