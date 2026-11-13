import Foundation
@testable import KeyCatchCore

extension Synth {
    /// Feed audio through an analyser in realistic chunks and return the final result.
    @discardableResult
    static func analyze(
        _ audio: [Float],
        _ analyzer: MusicAnalyzer = MusicAnalyzer(sampleRate: sampleRate),
        onPublish: ((AnalysisResult) -> Void)? = nil
    ) -> AnalysisResult {
        let chunk = 1024
        var i = 0
        while i < audio.count {
            let n = min(chunk, audio.count - i)
            let buf = Array(audio[i..<(i + n)])
            let nowNanos = Int(Double(i + n) / Double(sampleRate) * 1e9)
            if analyzer.process(buf, count: n, nowNanos: nowNanos) { onPublish?(analyzer.result) }
            i += n
        }
        return analyzer.result
    }
}
