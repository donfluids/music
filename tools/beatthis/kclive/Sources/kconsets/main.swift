// kconsets: writes KeyCatchCore's onset-strength signal (OnsetDetector with a preset's settings,
// hop 512, newest 2048 samples per hop, as MusicAnalyzer feeds it) for offline meter experiments.
//
// Usage: kconsets LISTFILE   (lines: <input.f32> <output.f32> <sampleRate> <preset id>)
// Output: little-endian float32, one onset value per 512-sample hop.
import Foundation
import KeyCatchCore

let hop = 512
guard CommandLine.arguments.count == 2, let list = try? String(contentsOfFile: CommandLine.arguments[1], encoding: .utf8) else {
    FileHandle.standardError.write("usage: kconsets LISTFILE\n".data(using: .utf8)!)
    exit(2)
}
for line in list.split(separator: "\n") {
    let parts = line.split(separator: " ").map(String.init)
    guard parts.count == 4, let rate = Int(parts[2]) else { continue }
    let data = try! Data(contentsOf: URL(fileURLWithPath: parts[0]))
    let n = data.count / 4
    var audio = [Float](repeating: 0, count: n)
    _ = audio.withUnsafeMutableBytes { data.copyBytes(to: $0) }
    let p = AnalysisPreset.byId(parts[3])
    let det = OnsetDetector(rate, minHz: p.onsetMinHz, maxHz: p.onsetMaxHz, gamma: p.onsetGamma,
                            lowBandMaxHz: p.onsetLowBandMaxHz, lowBandWeight: p.onsetLowBandWeight)
    var window = [Float](repeating: 0, count: det.fftSize)
    var out = [Float]()
    var i = 0
    while i + hop <= n {
        window.removeFirst(hop)
        window.append(contentsOf: audio[i..<(i + hop)])
        out.append(det.process(window, 0))
        i += hop
    }
    let bytes = out.withUnsafeBufferPointer { Data(buffer: $0) }
    try! bytes.write(to: URL(fileURLWithPath: parts[1]))
}
