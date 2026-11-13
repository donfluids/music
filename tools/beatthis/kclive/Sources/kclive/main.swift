// kclive: runs KeyCatchCore.MusicAnalyzer over raw audio and reports the beat readout.
//
// Usage: kclive LISTFILE
// Each line of LISTFILE: <input.f32> <output.jsonl> <sampleRate> <preset id: band|guitar>
// The input is little-endian float32 mono at sampleRate. Audio goes in 1024-sample chunks with
// nowNanos = clock time of the chunk's last sample (clock = 10 s + samples / rate). After the
// chunk that reaches each whole second from 4 s on, one JSON line is written with the result.
import Foundation
import KeyCatchCore

let chunk = 1024
let baseNanos = 10_000_000_000
let args = CommandLine.arguments
guard args.count == 2, let list = try? String(contentsOfFile: args[1], encoding: .utf8) else {
    FileHandle.standardError.write("usage: kclive LISTFILE\n".data(using: .utf8)!)
    exit(2)
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
    var nextMoment = 4.0
    var buf = [Float](repeating: 0, count: chunk)
    var fed = 0
    // Every beat the screen would pulse on: (time, bar position), recorded when lastBeatAtNanos moves.
    var shownBeats: [(Double, Int)] = []
    let started = DispatchTime.now().uptimeNanoseconds
    while fed + chunk <= n {
        for k in 0..<chunk { buf[k] = audio[fed + k] }
        fed += chunk
        let now = baseNanos + Int(Double(fed) / Double(rate) * 1e9)
        analyzer.process(buf, count: chunk, nowNanos: now)
        let t = Double(fed) / Double(rate)
        let lb = analyzer.result.lastBeatAtNanos
        if lb != 0 {
            let bt = Double(lb - baseNanos) / 1e9
            if shownBeats.isEmpty || abs(bt - shownBeats[shownBeats.count - 1].0) > 0.001 {
                shownBeats.append((bt, analyzer.result.beatsPerBar > 0 ? analyzer.result.lastBeatIndexInBar : -1))
            }
        }
        if t >= nextMoment {
            let r = analyzer.result
            let last = r.lastBeatAtNanos == 0 ? -1.0 : Double(r.lastBeatAtNanos - baseNanos) / 1e9
            let bpm = r.bpm.map { String(format: "%.4f", Double($0)) } ?? "null"
            let label = r.meterLabel.map { "\"\($0)\"" } ?? "null"
            out += "{\"t\":\(String(format: "%.6f", t)),\"moment\":\(nextMoment),\"bpm\":\(bpm),\"beatsPerBar\":\(r.beatsPerBar),"
                + "\"meter\":\(label),\"compound\":\(r.meterCompound),\"lastBeat\":\(String(format: "%.6f", last)),"
                + "\"periodMs\":\(String(format: "%.4f", Double(r.beatPeriodMillis))),\"locked\":\(r.tempoLocked),"
                + "\"indexInBar\":\(r.lastBeatIndexInBar)}\n"
            while nextMoment <= t { nextMoment += 1 }
        }
    }
    let ms = Double(DispatchTime.now().uptimeNanoseconds - started) / 1e6
    let beatList = shownBeats.map { String(format: "[%.6f,%d]", $0.0, $0.1) }.joined(separator: ",")
    out += "{\"shownBeats\":[\(beatList)]}\n"
    out += "{\"done\":true,\"seconds\":\(Double(n) / Double(rate)),\"cpuMs\":\(String(format: "%.1f", ms))}\n"
    try! out.write(toFile: parts[1], atomically: true, encoding: .utf8)
}
