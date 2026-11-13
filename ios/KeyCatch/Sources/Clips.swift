import Foundation
import KeyCatchCore

/// Records one clip: the raw microphone signal to a WAV file plus a JSON sidecar with the mode,
/// a timeline of what the analyser decided, the chords heard during the clip, and (later) the
/// user's labels. All file work happens on the recorder's own queue; the audio queue only
/// hands samples over. Failures are reported once through `onError`.
final class ClipRecorder {
    let name: String
    let wavFile: URL
    let jsonFile: URL
    private let partFile: URL
    private let sampleRate: Int
    private let preset: AnalysisPreset
    private let maxSeconds: Double
    private let onError: (String) -> Void
    private let queue = DispatchQueue(label: "KeyCatch-clip")
    private let lock = NSLock()
    private var writer: WavWriter?
    private var timeline: [[String: Any]] = []
    private var lastTimelineSeconds = -1.0
    private var submitted = 0
    private var closed = false
    private var failed = false
    /// The analyser's clock when the clip began, used to keep only the chords heard during the clip.
    var analysisStartSeconds: Float {
        get { lock.withLock { _analysisStart } }
        set { lock.withLock { _analysisStart = newValue } }
    }
    private var _analysisStart: Float

    init(dir: URL, sampleRate: Int, preset: AnalysisPreset, analysisStartSeconds: Float, maxSeconds: Double = 60, onError: @escaping (String) -> Void) {
        // UTC throughout: a local time plus offset would reveal the region the clip was made in.
        name = "clip_" + ClipRecorder.utc("yyyyMMdd_HHmmss_SSS").string(from: Date())
        wavFile = dir.appendingPathComponent("\(name).wav")
        partFile = dir.appendingPathComponent("\(name).wav.part")
        jsonFile = dir.appendingPathComponent("\(name).json")
        self.sampleRate = sampleRate
        self.preset = preset
        self.maxSeconds = maxSeconds
        self.onError = onError
        _analysisStart = analysisStartSeconds
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        queue.async { [self] in
            do { writer = try WavWriter(partFile, sampleRate) } catch { fail(error) }
        }
    }

    /// True once the clip reached its length; the owner should then call `finish`.
    var full: Bool { lock.withLock { Double(submitted) >= maxSeconds * Double(sampleRate) } }

    /// Seconds of audio handed to the recorder so far.
    var seconds: Double { lock.withLock { Double(submitted) / Double(sampleRate) } }

    /// Called on the audio queue: hands the samples over and returns at once.
    func write(_ samples: [Float], _ count: Int) {
        let accept: Bool = lock.withLock {
            guard !closed, !failed, Double(submitted) < maxSeconds * Double(sampleRate) else { return false }
            submitted += count
            return true
        }
        guard accept else { return }
        queue.async { [self] in
            guard let w = writer else { return }
            do { try w.write(samples, count) } catch { fail(error) }
        }
    }

    /// Notes what the analyser currently thinks, at most twice a second. Audio queue only.
    func noteResult(_ result: AnalysisResult) {
        let t = seconds
        if lock.withLock({ closed || failed }) || t - lastTimelineSeconds < 0.5 { return }
        lastTimelineSeconds = t
        var entry: [String: Any] = ["t": round2(t), "keyConfidence": round2(Double(result.keyConfidence)), "meterConfidence": round2(Double(result.meterConfidence))]
        entry["key"] = result.key?.label ?? NSNull()
        entry["bpm"] = result.bpm.map { round2(Double($0)) } ?? NSNull()
        entry["meter"] = result.meterLabel ?? NSNull()
        entry["chord"] = result.currentChord?.description ?? NSNull()
        queue.async { [self] in timeline.append(entry) }
    }

    /// Closes the WAV, writes the sidecar and renames the clip into place, on the recorder's
    /// queue. Returns at once; `onDone` is called there with true if the clip was saved. Later
    /// calls are ignored.
    func finish(_ finalResult: AnalysisResult, onDone: @escaping (Bool) -> Void = { _ in }) {
        let first: Bool = lock.withLock { if closed { return false }; closed = true; return true }
        guard first else { return }
        let start = analysisStartSeconds
        queue.async { [self] in
            let w = writer
            writer = nil
            var saved = false
            do {
                if let w, !failed {
                    try w.close()
                    let duration = Double(w.sampleCount) / Double(sampleRate)
                    let data = try JSONSerialization.data(withJSONObject: sidecar(finalResult, duration, start), options: [.prettyPrinted, .sortedKeys])
                    try data.write(to: jsonFile)
                    try FileManager.default.moveItem(at: partFile, to: wavFile)
                    saved = true
                } else {
                    try? w?.close()
                    try? FileManager.default.removeItem(at: partFile)
                }
            } catch {
                fail(error)
                try? FileManager.default.removeItem(at: partFile)
                try? FileManager.default.removeItem(at: jsonFile)
            }
            onDone(saved)
        }
    }

    /// Throws the clip away without saving anything.
    func discard() {
        let first: Bool = lock.withLock { if closed { return false }; closed = true; return true }
        guard first else { return }
        queue.async { [self] in
            try? writer?.close()
            writer = nil
            try? FileManager.default.removeItem(at: partFile)
        }
    }

    private func sidecar(_ r: AnalysisResult, _ duration: Double, _ start: Float) -> [String: Any] {
        func orNull(_ v: Any?) -> Any { v ?? NSNull() }
        let chords: [[String: Any]] = r.chordSegments.filter { $0.endSeconds > start }.map { seg in
            ["chord": orNull(seg.chord?.description), "start": round2(Double(max(seg.startSeconds - start, 0))), "end": round2(Double(seg.endSeconds - start))]
        }
        return [
            "clip": name,
            "recordedAt": ClipRecorder.utc("yyyy-MM-dd'T'HH:mm:ss'Z'").string(from: Date()),
            "ios": ProcessInfo.processInfo.operatingSystemVersion.majorVersion, // system version only; no device model
            "sampleRate": sampleRate,
            "preset": preset.id,
            "durationSeconds": round2(duration),
            "final": [
                "key": orNull(r.key?.label), "keyConfidence": round2(Double(r.keyConfidence)), "tuningCents": round2(Double(r.tuningCents)),
                "bpm": orNull(r.bpm.map { round2(Double($0)) }), "tempoStrength": round2(Double(r.tempoStrength)),
                "meter": orNull(r.meterLabel), "meterConfidence": round2(Double(r.meterConfidence)),
                "songKey": orNull(r.songKey?.label), "songKeyConfidence": round2(Double(r.songKeyConfidence)),
            ] as [String: Any],
            "chords": chords,
            "timeline": timeline,
            "labels": [String: Any](),
        ]
    }

    private func fail(_ error: Error) {
        let first: Bool = lock.withLock { if failed { return false }; failed = true; return true }
        if first { onError("Could not write the clip: \(error.localizedDescription)") }
    }

    private func round2(_ v: Double) -> Double { (v * 100).rounded() / 100 }

    static func utc(_ pattern: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = pattern
        return f
    }
}

/// Where clips live, plus labelling, sharing and deleting. Clips stay in the app's own
/// Documents folder, which other apps cannot read; Share is the only way out.
struct ClipStore {
    let dir: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("clips", isDirectory: true)

    func files() -> [URL] {
        let all = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return all.filter { ["wav", "json"].contains($0.pathExtension) }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    var count: Int { files().filter { $0.pathExtension == "wav" }.count }

    /// Removes leftovers of clips interrupted by a crash; files written in the last two minutes are left alone.
    func cleanUp() {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let cutoff = Date().addingTimeInterval(-120)
        for f in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        where f.pathExtension == "part" {
            let modified = (try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            if modified < cutoff { try? FileManager.default.removeItem(at: f) }
        }
    }

    /// Writes the user's answer for one clip into its sidecar. Empty fields are left out.
    func saveLabels(_ clip: String, key: String, tempo: String, meter: String, notes: String) {
        let file = dir.appendingPathComponent("\(clip).json")
        guard let data = try? Data(contentsOf: file),
              var json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        func clean(_ v: String, _ max: Int) -> String {
            String(v.filter { $0 == "\n" || !($0.unicodeScalars.first.map { CharacterSet.controlCharacters.contains($0) } ?? false) }
                .trimmingCharacters(in: .whitespacesAndNewlines).prefix(max))
        }
        var labels: [String: Any] = [:]
        if !key.trimmingCharacters(in: .whitespaces).isEmpty { labels["key"] = clean(key, 40) }
        if !tempo.trimmingCharacters(in: .whitespaces).isEmpty { labels["tempo"] = clean(tempo, 20) }
        if !meter.trimmingCharacters(in: .whitespaces).isEmpty { labels["meter"] = clean(meter, 20) }
        if !notes.trimmingCharacters(in: .whitespaces).isEmpty { labels["notes"] = clean(notes, 500) }
        json["labels"] = labels
        if let out = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]) { try? out.write(to: file) }
    }

    func deleteAll() {
        for f in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] { try? FileManager.default.removeItem(at: f) }
    }
}
