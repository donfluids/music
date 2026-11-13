import CoreML
import Foundation
import KeyCatchCore
import Observation
import UIKit

/// The range of the tuning reference, in Hz.
let minReferenceHz = 430
let maxReferenceHz = 450

/// Everything the screens show and every action they take, as on Android's AnalyzerViewModel.
///
/// Threading: the audio queue owns the session's analyser and the clip recorder; the main
/// thread never mutates an analyser in use (it hands a new one to the audio queue instead);
/// results reach the main thread as copies.
@MainActor
@Observable
final class AppModel {
    // ---- What the screens show ----
    var listening = false
    var error: String?
    var preset: AnalysisPreset
    /// nil follows the system setting; true or false is the user's choice.
    var darkTheme: Bool?
    /// Display-only multiplier for the tempo (0.5, 1 or 2) when the tracker picks the wrong pulse.
    var tempoMultiplier: Float = 1
    /// The key frozen by Hold, or nil when the display follows the analysis.
    var heldKey: Key?
    /// Semitones added to every displayed key and chord: what to play, not what sounds.
    var transpose = 0
    /// Show Nashville numbers instead of chord letters.
    var nashville: Bool
    /// True while the first-clip notice is waiting to be accepted.
    var clipNoticePending = false
    var analysis = AnalysisResult()
    var recording = false
    var recordingSeconds = 0
    var clipCount = 0
    /// A clip that has just finished and is waiting for the user's labels.
    var clipAwaitingLabels: String?
    /// Files ready to hand to the share sheet; the screen consumes and clears them.
    var shareFiles: [URL]?
    /// The tempo the player tapped in, or nil; `tapAnchorNanos` is the last tap, for the pulse.
    var tapBpm: Float?
    var tapAnchorNanos = 0
    /// Taps in the current count, so the screen can ask for a few more.
    var tapCount = 0
    /// The A the band tunes to, in Hz; "in tune" is measured from it.
    var referenceHz: Int
    var naming: NoteNaming
    /// Follow the sung melody with CREPE (the default) or the autocorrelation tracker.
    var crepe: Bool
    /// Hear the notes for chords and key with Basic Pitch (the default) or the spectral chroma.
    var basicPitch: Bool
    /// Follow the beat in Band and Guitar with Beat This! (the default) or the standard tracker.
    var beatThis: Bool
    /// Songs heard, newest first, saved on the phone.
    var history: [SongRecord] = []
    var showHistory = false
    var showSettings = false
    var showLimits = false

    // ---- Machinery ----
    private let prefs = UserDefaults.standard
    private let clips = ClipStore()
    private let audioQueue = DispatchQueue(label: "KeyCatch-audio", qos: .userInitiated)
    /// Basic Pitch runs here, off the audio queue, so a slow phone cannot hold up the microphone.
    private let notesQueue = DispatchQueue(label: "KeyCatch-notes", qos: .utility)
    /// Beat This! runs here, once a second, for the same reason.
    private let beatQueue = DispatchQueue(label: "KeyCatch-beat", qos: .utility)
    private let historyQueue = DispatchQueue(label: "KeyCatch-history")
    private let songHistory: SongHistory
    private let tapTempo = TapTempo()

    /// One microphone session. Its analyser and recorder are only touched on the audio queue.
    private final class Session: @unchecked Sendable {
        let capture: AudioCapture
        var analyzer: MusicAnalyzer
        var recorder: ClipRecorder?
        var lastReportedSecond = -1
        init(capture: AudioCapture, analyzer: MusicAnalyzer) { self.capture = capture; self.analyzer = analyzer }
    }
    private var session: Session?

    /// The learned models, loaded (and compiled, the first time) off the main thread at launch.
    /// Until they are ready, or if they cannot be loaded, the standard methods are used.
    private var crepeModel: MLModel?
    private var basicPitchModel: MLModel?
    private var beatModels: [BeatThisModel: MLModel] = [:]

    init() {
        preset = AnalysisPreset.byId(prefs.string(forKey: Pref.preset))
        darkTheme = prefs.object(forKey: Pref.dark) as? Bool
        nashville = prefs.bool(forKey: Pref.nashville)
        referenceHz = (prefs.object(forKey: Pref.reference) as? Int ?? 440).coerceIn(minReferenceHz, maxReferenceHz)
        naming = NoteNaming.byId(prefs.string(forKey: Pref.naming))
        // The learned models are the default; a choice made in Settings is kept.
        crepe = prefs.object(forKey: Pref.crepe) as? Bool ?? true
        basicPitch = prefs.object(forKey: Pref.basicPitch) as? Bool ?? true
        beatThis = prefs.object(forKey: Pref.beatThis) as? Bool ?? true
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        songHistory = SongHistory(url: support.appendingPathComponent("history.json"))
        // In screenshot mode the screens show prepared songs instead of the saved list.
        if !Screenshots.active {
            historyQueue.async { [songHistory] in
                let songs = songHistory.all()
                DispatchQueue.main.async { self.history = songs }
            }
        }
        let clips = self.clips
        DispatchQueue.global(qos: .utility).async {
            clips.cleanUp()
            let count = clips.count
            DispatchQueue.main.async { self.clipCount = count }
        }
        Task.detached(priority: .utility) {
            let crepe = try? KeyCatchModels.load("CrepeTiny")
            let basicPitch = try? KeyCatchModels.load("BasicPitchNotes")
            var loaded: [BeatThisModel: MLModel] = [:]
            for which in BeatThisModel.allCases { if let m = try? KeyCatchModels.load(which.resourceName) { loaded[which] = m } }
            let beats = loaded
            await MainActor.run {
                self.crepeModel = crepe
                self.basicPitchModel = basicPitch
                self.beatModels = beats
            }
        }
    }

    // ---- Listening ----

    func start() {
        guard !listening, session == nil else { return }
        let capture = AudioCapture(queue: audioQueue)
        do {
            try capture.start()
        } catch {
            self.error = error.localizedDescription
            return
        }
        let s = Session(capture: capture, analyzer: newAnalyzer(capture.sampleRate, preset))
        capture.onAudio = { [weak self] samples, count, now in self?.onAudio(s, samples, count, now) }
        capture.onError = { [weak self] message in
            guard let self, self.session === s else { return }
            self.stop()
            self.error = message
        }
        session = s
        listening = true
        error = nil
        analysis = AnalysisResult()
        heldKey = nil
        transpose = 0
    }

    /// Runs on the audio queue.
    nonisolated private func onAudio(_ s: Session, _ samples: [Float], _ count: Int, _ now: Int) {
        let changed = s.analyzer.process(samples, count: count, nowNanos: now)
        if let r = s.recorder {
            r.write(samples, count)
            if changed { r.noteResult(s.analyzer.result) }
            let sec = Int(r.seconds)
            if sec != s.lastReportedSecond {
                s.lastReportedSecond = sec
                DispatchQueue.main.async { self.recordingSeconds = sec }
            }
            if r.full { finishClip(s, r) }
        }
        if changed {
            let result = s.analyzer.result
            DispatchQueue.main.async { if self.session === s { self.analysis = result } }
        }
    }

    func stop() {
        guard let s = session else { return }
        session = nil
        s.capture.stop() // the queued audio is done before the clip is finished
        audioQueue.sync { if let r = s.recorder { finishClip(s, r) } }
        saveSong()
        listening = false
        heldKey = nil
    }

    private func newAnalyzer(_ rate: Int, _ preset: AnalysisPreset) -> MusicAnalyzer {
        let crepeModel = preset.melodicKey && crepe ? self.crepeModel : nil
        let notesModel = !preset.melodicKey && basicPitch ? basicPitchModel : nil
        // Band uses the small Beat This!, Guitar the full one; Voices taps its tempo.
        let beat = beatThis ? preset.beatModel : nil
        let beatModel = beat.flatMap { beatModels[$0] }
        let notesQueue = self.notesQueue
        let beatQueue = self.beatQueue
        return MusicAnalyzer(
            sampleRate: rate, preset: preset,
            pitch: crepeModel.flatMap { m in (try? CoreMLCrepe(model: m)).map { net in { (r: Int) -> PitchEstimator in CrepePitch(net, r) } } },
            notes: notesModel.flatMap { m in (try? CoreMLBasicPitch(model: m)).map { net in { (r: Int) -> HeardNotes in HeardNotes(net, r, worker: notesQueue) } } },
            beats: beatModel.flatMap { m in (try? CoreMLBeatThis(model: m)).map { net in
                { (r: Int, hop: Int) -> BeatTracker in BeatTracker(net, r, hopSize: hop, name: beat!.title, worker: beatQueue) } } })
    }

    /// Hands a fresh analyser to the audio queue; the old one is never touched again.
    private func replaceAnalyzer() {
        guard let s = session else { return }
        let fresh = newAnalyzer(s.capture.sampleRate, preset)
        audioQueue.async {
            s.analyzer = fresh
            s.recorder?.analysisStartSeconds = 0 // the clip's chord filter follows the new clock
        }
    }

    /// A tap on the beat: from the second tap on, the tempo is shown and pulses.
    func tapBeat() {
        let now = Int(DispatchTime.now().uptimeNanoseconds)
        let r = tapTempo.tap(now)
        tapBpm = r?.bpm
        tapAnchorNanos = r?.lastTapNanos ?? now
        tapCount = r?.taps ?? 1
    }

    func clearTapTempo() {
        tapTempo.clear()
        tapBpm = nil
        tapAnchorNanos = 0
        tapCount = 0
    }

    /// Forget everything heard so far: a fresh analyser replaces the old one, never mutated in place.
    func reset() {
        saveSong()
        clearTapTempo()
        replaceAnalyzer()
        analysis = AnalysisResult()
        tempoMultiplier = 1
        heldKey = nil
        transpose = 0
    }

    /// Freeze the shown key so nothing changes under the player's hands; tap again to release.
    func toggleHold() {
        heldKey = heldKey == nil ? (analysis.displayKey ?? analysis.key) : nil
    }

    func setTranspose(_ semitones: Int) { transpose = semitones.coerceIn(-6, 6) }

    func setTempoMultiplier(_ m: Float) { tempoMultiplier = m }

    /// Switch presets. While listening, analysis starts over with the new settings.
    func setPreset(_ p: AnalysisPreset) {
        guard p != preset else { return }
        prefs.set(p.id, forKey: Pref.preset)
        preset = p
        replaceAnalyzer()
        analysis = AnalysisResult()
        tempoMultiplier = 1
        heldKey = nil
        transpose = 0
    }

    // ---- Settings ----

    func setNashville(_ on: Bool) { prefs.set(on, forKey: Pref.nashville); nashville = on }

    func setReferenceHz(_ hz: Int) {
        let v = hz.coerceIn(minReferenceHz, maxReferenceHz)
        prefs.set(v, forKey: Pref.reference)
        referenceHz = v
    }

    func setNaming(_ n: NoteNaming) { prefs.set(n.id, forKey: Pref.naming); naming = n }

    /// Switch the melody tracker. While listening in Voices, analysis starts over with it.
    func setCrepe(_ on: Bool) {
        guard on != crepe else { return }
        prefs.set(on, forKey: Pref.crepe)
        crepe = on
        if preset.melodicKey { replaceAnalyzer(); analysis = AnalysisResult(); heldKey = nil }
    }

    /// Switch how notes are heard for chords and key. While listening in Band or Guitar, analysis starts over.
    func setBasicPitch(_ on: Bool) {
        guard on != basicPitch else { return }
        prefs.set(on, forKey: Pref.basicPitch)
        basicPitch = on
        if !preset.melodicKey { replaceAnalyzer(); analysis = AnalysisResult(); heldKey = nil }
    }

    /// Switch how the beat is followed. While listening in Band or Guitar, analysis starts over.
    func setBeatThis(_ on: Bool) {
        guard on != beatThis else { return }
        prefs.set(on, forKey: Pref.beatThis)
        beatThis = on
        if preset.readsBeat { replaceAnalyzer(); analysis = AnalysisResult(); heldKey = nil; tempoMultiplier = 1 }
    }

    /// Dark, light, or nil to follow the system.
    func setTheme(_ dark: Bool?) {
        if let dark { prefs.set(dark, forKey: Pref.dark) } else { prefs.removeObject(forKey: Pref.dark) }
        darkTheme = dark
    }

    // ---- History ----

    /// Saves the song just heard, if a key was found; called before the analysis is thrown away.
    private func saveSong() {
        guard let record = SongRecord.from(analysis: analysis, heldKey: heldKey, preset: preset, tapBpm: tapBpm, tempoMultiplier: tempoMultiplier,
                                           transpose: transpose, nowMillis: Int(Date().timeIntervalSince1970 * 1000)) else { return }
        updateHistory { try $0.add(record) }
    }

    func deleteSong(_ atMillis: Int) { updateHistory { try $0.remove(atMillis) } }
    func restoreSong(_ record: SongRecord) { updateHistory { try $0.add(record) } }
    func clearHistory() { updateHistory { try $0.clear() } }

    private func updateHistory(_ change: @escaping (SongHistory) throws -> [SongRecord]) {
        historyQueue.async { [songHistory] in
            do {
                let songs = try change(songHistory)
                DispatchQueue.main.async { self.history = songs }
            } catch {
                DispatchQueue.main.async { self.error = "Could not save the song list: \(error.localizedDescription)" }
            }
        }
    }

    // ---- Clips ----

    func startClip() {
        guard let s = session else { return }
        if !prefs.bool(forKey: Pref.clipNotice) { clipNoticePending = true; return }
        let r = ClipRecorder(dir: clips.dir, sampleRate: s.capture.sampleRate, preset: preset, analysisStartSeconds: analysis.secondsHeard) { message in
            DispatchQueue.main.async { self.error = message }
        }
        audioQueue.async {
            guard s.recorder == nil else { r.discard(); return }
            s.lastReportedSecond = -1
            s.recorder = r
        }
        recording = true
        recordingSeconds = 0
        error = nil
    }

    /// The user has read the recording notice; remember it and start the clip they asked for.
    func acceptClipNotice() {
        prefs.set(true, forKey: Pref.clipNotice)
        clipNoticePending = false
        startClip()
    }

    func dismissClipNotice() { clipNoticePending = false }

    func stopClip() {
        guard let s = session else { return }
        audioQueue.async { if let r = s.recorder { self.finishClip(s, r) } }
    }

    /// On the audio queue: the recorder is detached first, so only one caller finishes it.
    nonisolated private func finishClip(_ s: Session, _ r: ClipRecorder) {
        guard s.recorder === r else { return }
        s.recorder = nil
        let result = s.analyzer.result
        DispatchQueue.main.async { self.recording = false; self.recordingSeconds = 0 }
        r.finish(result) { saved in
            guard saved else { return }
            DispatchQueue.main.async { self.clipCount += 1; self.clipAwaitingLabels = r.name }
        }
    }

    func saveLabels(key: String, tempo: String, meter: String, notes: String) {
        guard let name = clipAwaitingLabels else { return }
        clipAwaitingLabels = nil
        let clips = self.clips
        DispatchQueue.global(qos: .utility).async { clips.saveLabels(name, key: key, tempo: tempo, meter: meter, notes: notes) }
    }

    func skipLabels() { clipAwaitingLabels = nil }

    /// The clips go to the share sheet as they are (iOS has no built-in zip).
    func shareClips() { shareFiles = clips.files() }

    func shareConsumed() { shareFiles = nil }

    func deleteClips() {
        let clips = self.clips
        DispatchQueue.global(qos: .utility).async {
            clips.deleteAll()
            let count = clips.count
            DispatchQueue.main.async { self.clipCount = count }
        }
    }

    func reportPermissionDenied() { error = S.permissionDenied }

    /// For screenshot previews only: shows a prepared state.
    func show(analysis: AnalysisResult, listening: Bool = true) {
        self.analysis = analysis
        self.listening = listening
    }

    private enum Pref {
        static let preset = "preset"
        static let dark = "dark"
        static let nashville = "nashville"
        static let clipNotice = "clipNoticeAccepted"
        static let reference = "referenceHz"
        static let naming = "naming"
        static let crepe = "crepe"
        static let basicPitch = "basicPitch"
        static let beatThis = "beatThis"
    }
}
