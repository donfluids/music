import Foundation
import KeyCatchCore

/// Prepared screens for the screenshots taken in CI, chosen with a launch argument:
/// `-screenshot 4-band` (the list is in .github/workflows/ios.yml). The same states as the Android app's ScreenshotTest, so the two can be
/// compared side by side. Nothing is listened to or saved in this mode.
enum Screenshots {
    /// The state asked for, or nil in normal use.
    static let state: String? = UserDefaults.standard.string(forKey: "screenshot")
    static var active: Bool { state != nil }
    /// The Details fold starts open in the states that show it.
    static var detailsOpen: Bool { state?.hasSuffix("details") ?? false }

    @MainActor static func apply(to m: AppModel) {
        guard let state else { return }
        let now = Int(DispatchTime.now().uptimeNanoseconds)
        let notes = (0..<12).map { SungNote(midi: 64 + $0 % 5, startSeconds: Double($0) * 0.5, durationSeconds: 0.45) }
        func chord(_ root: Int, _ type: ChordType = .major) -> ChordLabel { ChordLabel(root, type) }
        let e = Key(4, .major)

        var settled = AnalysisResult()
        settled.secondsHeard = 40; settled.level = 0.5
        settled.key = e; settled.displayKey = e; settled.keyNow = e; settled.keyRangeLow = e; settled.keyRangeHigh = e
        settled.keyConfidence = 0.8; settled.displayKeyConfidence = 0.8; settled.keyHeldSeconds = 20
        settled.pairDecided = true; settled.modeDecided = true
        settled.tuningCents = 12; settled.tuningConfidence = 0.6; settled.sungNotes = notes; settled.keyFromMelody = true
        settled.currentChord = chord(9)
        settled.chordSegments = [ChordSegment(chord(4), 30, 34), ChordSegment(chord(9), 34, 36), ChordSegment(chord(11), 36, 38), ChordSegment(chord(9), 38, 40)]

        func voices(_ a: AnalysisResult, tap: Float? = 96, taps: Int = 6) {
            m.preset = .voices
            m.show(analysis: a)
            m.tapBpm = tap; m.tapCount = tap == nil ? 0 : taps; m.tapAnchorNanos = now
        }

        func band() -> AnalysisResult {
            let g = Key(7, .major)
            var a = AnalysisResult()
            a.secondsHeard = 40; a.level = 0.6
            a.key = g; a.displayKey = g; a.keyNow = g; a.keyRangeLow = g; a.keyRangeHigh = g
            a.keyConfidence = 0.8; a.displayKeyConfidence = 0.8; a.keyHeldSeconds = 25; a.pairDecided = true; a.modeDecided = true
            a.bpm = 120; a.tempoStrength = 0.6; a.tempoLocked = true; a.beatPeriodMillis = 500; a.lastBeatAtNanos = now
            a.meterLabel = "4/4"; a.beatsPerBar = 4; a.meterConfidence = 0.7; a.currentChord = chord(2)
            a.tuningConfidence = 0.6; a.tuningCents = 4
            a.chroma = [0.3, 0.05, 0.9, 0.1, 0.4, 0.2, 0.15, 1.0, 0.05, 0.5, 0.05, 0.6]
            a.songKey = g
            a.chordSegments = [ChordSegment(chord(7), 30, 32), ChordSegment(chord(4, .minor), 32, 34), ChordSegment(chord(0), 34, 36), ChordSegment(chord(2), 36, 40)]
            a.pitchModel = nil; a.notesModel = "Basic Pitch"; a.notesMillisPerSecond = 30
            return a
        }

        switch state {
        case "0-idle": m.preset = .voices
        case "0-idle-band": m.preset = .band
        case "1-voices-settled": voices(settled)
        case "2-voices-wandering":
            let cs = Key(1, .major)
            var a = AnalysisResult()
            a.secondsHeard = 70; a.level = 0.4
            a.key = cs; a.displayKey = cs; a.keyNow = Key(2, .major); a.keyRangeLow = Key(0, .major); a.keyRangeHigh = Key(2, .major)
            a.keyMove = .rising; a.keyConfidence = 0.6; a.displayKeyConfidence = 0.6; a.keyHeldSeconds = 30
            a.pairDecided = true; a.modeDecided = true
            a.tuningCents = -30; a.tuningConfidence = 0.5; a.sungNotes = notes; a.keyFromMelody = true; a.currentChord = chord(2)
            voices(a, tap: nil)
        case "3-voices-early":
            let f = Key(5, .major)
            var a = AnalysisResult()
            a.secondsHeard = 6; a.level = 0.5
            a.key = f; a.displayKey = f; a.keyNow = f; a.keyRangeLow = f; a.keyRangeHigh = f
            a.keyConfidence = 0.3; a.displayKeyConfidence = 0.3; a.keyHeldSeconds = 2
            voices(a, tap: nil)
        case "4-band", "13-band-details":
            m.preset = .band
            m.show(analysis: band())
        case "5-voices-settled-dark":
            m.darkTheme = true
            voices(settled)
        case "6-voices-tapping": voices(settled, tap: 92, taps: 2)
        case "7-voices-do-re-mi-442":
            m.naming = .fixedDo; m.referenceHz = 442
            voices(settled)
        case "8-settings":
            m.referenceHz = 442
            voices(settled)
            m.showSettings = true
        case "9-history":
            let nowMs = Int(Date().timeIntervalSince1970 * 1000)
            let day = 86_400_000
            m.history = [
                SongRecord(atMillis: nowMs - 600_000, key: Key(7, .major), bpm: 72, bpmTapped: true, seconds: 245, presetId: "voices"),
                SongRecord(atMillis: nowMs - 1_500_000, key: Key(4, .minor), transpose: 2, bpm: 124, meter: "4/4", seconds: 198, presetId: "band"),
                SongRecord(atMillis: nowMs - day, key: Key(10, .major), bpm: 96, meter: "6/8", seconds: 312, presetId: "band"),
                SongRecord(atMillis: nowMs - day - 900_000, key: Key(2, .major), seconds: 180, presetId: "voices"),
            ]
            m.showHistory = true
        case "12-what-to-expect": m.showLimits = true
        default: break
        }
    }
}
