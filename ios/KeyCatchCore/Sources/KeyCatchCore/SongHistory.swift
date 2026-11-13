import Foundation

/// One song as KeyCatch heard it. `key` is the concert key; `transpose` is what the player had set.
public struct SongRecord: Hashable, Sendable, Identifiable {
    public let atMillis: Int
    public let key: Key
    public var transpose: Int = 0
    public var bpm: Int? = nil
    /// The tempo was tapped in by the player rather than heard.
    public var bpmTapped: Bool = false
    public var meter: String? = nil
    public var seconds: Int = 0
    public var presetId: String = ""

    public var id: Int { atMillis }

    public init(atMillis: Int, key: Key, transpose: Int = 0, bpm: Int? = nil, bpmTapped: Bool = false, meter: String? = nil, seconds: Int = 0, presetId: String = "") {
        self.atMillis = atMillis; self.key = key; self.transpose = transpose; self.bpm = bpm
        self.bpmTapped = bpmTapped; self.meter = meter; self.seconds = seconds; self.presetId = presetId
    }

    /// Shorter than this is a false start, not a song.
    public static let minSeconds = 15

    /// What to save for the song just heard, or nil when nothing worth keeping was heard: no key,
    /// or too short. The key is the one on screen: held, or else the centre.
    public static func from(analysis a: AnalysisResult, heldKey: Key?, preset: AnalysisPreset, tapBpm: Float?,
                            tempoMultiplier: Float, transpose: Int, nowMillis: Int) -> SongRecord? {
        guard let key = heldKey ?? a.displayKey else { return nil }
        if a.secondsHeard < Float(minSeconds) { return nil }
        let tapped = !preset.readsBeat
        let bpm: Float? = tapped ? tapBpm : (a.tempoLocked ? a.bpm.map { $0 * tempoMultiplier } : nil)
        return SongRecord(
            atMillis: nowMillis, key: key, transpose: transpose, bpm: bpm.map { roundToInt($0) },
            bpmTapped: tapped && bpm != nil, meter: preset.readsBeat ? a.meterLabel : nil,
            seconds: Int(a.secondsHeard), presetId: preset.id)
    }
}

/// The saved song list, newest first, kept in one small JSON file in the app's private storage.
/// Not thread-safe: the caller keeps every call on one queue.
public final class SongHistory {
    private let url: URL
    private let limit: Int
    private var songs: [SongRecord]?

    public init(url: URL, limit: Int = 500) { self.url = url; self.limit = limit }

    public func all() -> [SongRecord] {
        if let songs { return songs }
        let read = read()
        songs = read
        return read
    }

    /// Adds `record` in time order, so an undone delete goes back where it was.
    @discardableResult
    public func add(_ record: SongRecord) throws -> [SongRecord] {
        let list = (all().filter { $0.atMillis != record.atMillis } + [record]).sorted { $0.atMillis > $1.atMillis }
        return try write(Array(list.prefix(limit)))
    }

    @discardableResult
    public func remove(_ atMillis: Int) throws -> [SongRecord] { try write(all().filter { $0.atMillis != atMillis }) }

    @discardableResult
    public func clear() throws -> [SongRecord] { try write([]) }

    private func read() -> [SongRecord] {
        // A damaged file loses the history rather than stopping the app.
        guard let data = try? Data(contentsOf: url),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return array.compactMap(decode)
    }

    private func write(_ list: [SongRecord]) throws -> [SongRecord] {
        songs = list
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONSerialization.data(withJSONObject: list.map(encode))
        // Written beside the file and swapped in, so a crash mid-write never leaves half a file.
        try data.write(to: url, options: .atomic)
        return list
    }

    private func encode(_ r: SongRecord) -> [String: Any] {
        var o: [String: Any] = ["at": r.atMillis, "tonic": r.key.tonic, "mode": r.key.mode == .major ? "MAJOR" : "MINOR",
                                "transpose": r.transpose, "tapped": r.bpmTapped, "seconds": r.seconds, "preset": r.presetId]
        if let bpm = r.bpm { o["bpm"] = bpm }
        if let meter = r.meter { o["meter"] = meter }
        return o
    }

    private func decode(_ o: [String: Any]) -> SongRecord? {
        guard let at = (o["at"] as? NSNumber)?.intValue, let tonic = (o["tonic"] as? NSNumber)?.intValue,
              let modeName = o["mode"] as? String else { return nil }
        let mode: Mode
        switch modeName { case "MAJOR": mode = .major; case "MINOR": mode = .minor; default: return nil }
        return SongRecord(
            atMillis: at, key: Key(floorMod(tonic, 12), mode), transpose: (o["transpose"] as? NSNumber)?.intValue ?? 0,
            bpm: (o["bpm"] as? NSNumber)?.intValue, bpmTapped: (o["tapped"] as? Bool) ?? false, meter: o["meter"] as? String,
            seconds: (o["seconds"] as? NSNumber)?.intValue ?? 0, presetId: o["preset"] as? String ?? "")
    }
}
