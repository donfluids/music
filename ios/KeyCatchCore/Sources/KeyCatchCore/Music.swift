import Foundation

// Keys, chords and note names: the music theory the analyser reports in.

public enum Mode: String, CaseIterable, Hashable, Sendable {
    case major, minor
    public var label: String { rawValue }
}

/// A musical key: a tonic pitch class (0 = C) and a mode.
public struct Key: Hashable, Sendable, CustomStringConvertible {
    public let tonic: Int
    public let mode: Mode

    public init(_ tonic: Int, _ mode: Mode) { self.tonic = tonic; self.mode = mode }

    /// Spelled with sharps or flats according to the key signature.
    public var name: String { Notes.spell(tonic, flats: usesFlats) }
    public var label: String { "\(name) \(mode.label)" }
    public var description: String { label }

    /// Relative major or minor.
    public var relative: Key {
        mode == .major ? Key(floorMod(tonic - 3, 12), .minor) : Key(floorMod(tonic + 3, 12), .major)
    }

    /// The same key moved by `semitones`.
    public func transposed(_ semitones: Int) -> Key { Key(floorMod(tonic + semitones, 12), mode) }

    /// Number of sharps (positive) or flats (negative) in the key signature.
    public var signature: Int {
        let majorTonic = mode == .major ? tonic : floorMod(tonic + 3, 12)
        // Position on the circle of fifths: C=0, G=1, D=2 ... F=-1, Bb=-2 ...
        let fifths = floorMod(majorTonic * 7, 12)
        return fifths <= 6 ? fifths : fifths - 12
    }

    public var usesFlats: Bool { signature < 0 }

    /// Pitch classes of the diatonic scale, tonic first.
    public var scale: [Int] {
        (mode == .major ? Key.majorSteps : Key.naturalMinorSteps).map { floorMod(tonic + $0, 12) }
    }

    /// Scale note names spelled for this key.
    public var scaleNames: [String] { scale.map { Notes.spell($0, flats: usesFlats) } }

    /// Diatonic triads with roman numerals, e.g. I = C, ii = Dm.
    public var chords: [Chord] {
        let qualities = mode == .major ? Key.majorTriads : Key.minorTriads
        let numerals = mode == .major ? Key.majorNumerals : Key.minorNumerals
        let s = scale
        return s.indices.map { i in
            Chord(numeral: numerals[i], name: Notes.spell(s[i], flats: usesFlats) + qualities[i].suffix, quality: qualities[i])
        }
    }

    public static func all() -> [Key] { Mode.allCases.flatMap { m in (0..<12).map { Key($0, m) } } }

    static let majorSteps = [0, 2, 4, 5, 7, 9, 11]
    static let naturalMinorSteps = [0, 2, 3, 5, 7, 8, 10]
    static let majorTriads: [ChordQuality] = [.major, .minor, .minor, .major, .major, .minor, .diminished]
    static let minorTriads: [ChordQuality] = [.minor, .diminished, .major, .minor, .minor, .major, .major]
    static let majorNumerals = ["I", "ii", "iii", "IV", "V", "vi", "vii°"]
    static let minorNumerals = ["i", "ii°", "III", "iv", "v", "VI", "VII"]
}

public enum ChordQuality: Hashable, Sendable {
    case major, minor, diminished
    public var suffix: String { switch self { case .major: ""; case .minor: "m"; case .diminished: "°" } }
}

public struct Chord: Hashable, Sendable {
    public let numeral: String
    public let name: String
    public let quality: ChordQuality
}

public enum Notes {
    static let sharp = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    static let flat = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"]

    public static func spell(_ pitchClass: Int, flats: Bool) -> String {
        let pc = floorMod(pitchClass, 12)
        return flats ? flat[pc] : sharp[pc]
    }

    /// Neutral spelling for raw pitch classes (the chroma bars).
    public static func neutral(_ pitchClass: Int) -> String { sharp[floorMod(pitchClass, 12)] }
}

/// Chord types the detector can name. `intervals` are semitones above the root.
public enum ChordType: CaseIterable, Hashable, Sendable {
    case major, minor, dominantSeventh, majorSeventh, minorSeventh, sus4, sus2

    public var suffix: String {
        switch self {
        case .major: ""
        case .minor: "m"
        case .dominantSeventh: "7"
        case .majorSeventh: "maj7"
        case .minorSeventh: "m7"
        case .sus4: "sus4"
        case .sus2: "sus2"
        }
    }

    public var intervals: [Int] {
        switch self {
        case .major: [0, 4, 7]
        case .minor: [0, 3, 7]
        case .dominantSeventh: [0, 4, 7, 10]
        case .majorSeventh: [0, 4, 7, 11]
        case .minorSeventh: [0, 3, 7, 10]
        case .sus4: [0, 5, 7]
        case .sus2: [0, 2, 7]
        }
    }

    public var isMinor: Bool { self == .minor || self == .minorSeventh }
    public var isSus: Bool { self == .sus4 || self == .sus2 }
}

/// A chord identified by its root pitch class (0 = C) and type.
public struct ChordLabel: Hashable, Sendable, CustomStringConvertible {
    public let root: Int
    public let type: ChordType

    public init(_ root: Int, _ type: ChordType) { self.root = root; self.type = type }
    public init(_ root: Int, minor: Bool) { self.init(root, minor ? .minor : .major) }

    /// Minor-third chords (m, m7); everything else is treated as major-family for key finding.
    public var minor: Bool { type.isMinor }

    /// The same chord moved by `semitones`.
    public func transposed(_ semitones: Int) -> ChordLabel { ChordLabel(floorMod(root + semitones, 12), type) }

    /// The plain triad this chord stands on, for comparisons that ignore extensions.
    public var triad: ChordLabel { ChordLabel(root, minor: minor) }

    public func name(flats: Bool) -> String { Notes.spell(root, flats: flats) + type.suffix }

    /// Name spelled to suit `key`, or with sharps when no key is known.
    public func name(key: Key?) -> String { name(flats: key?.usesFlats ?? false) }

    /// Roman numeral relative to `key`: upper case for major, lower for minor, with a flat sign
    /// for non-diatonic roots (bVII, bIII ...). Extensions are appended.
    public func numeral(in key: Key) -> String {
        let interval = floorMod(root - key.tonic, 12)
        let base = key.mode == .major ? ChordLabel.majorDegrees[interval] : ChordLabel.minorDegrees[interval]
        let numeral = minor ? base.lowercased() : base
        switch type {
        case .major, .minor: return numeral
        case .dominantSeventh, .minorSeventh: return numeral + "7"
        case .majorSeventh: return numeral + "maj7"
        case .sus4: return numeral + "sus4"
        case .sus2: return numeral + "sus2"
        }
    }

    /// Nashville number relative to `key`: "1", "2m", "4", "5", "6m", "b7", with extensions
    /// appended ("57", "2m7", "4sus4"). Minor keys count from their own tonic.
    public func nashville(in key: Key) -> String {
        let interval = floorMod(root - key.tonic, 12)
        return (key.mode == .major ? ChordLabel.majorNumbers[interval] : ChordLabel.minorNumbers[interval]) + type.suffix
    }

    public var description: String { name(flats: false) }

    static let majorDegrees = ["I", "bII", "II", "bIII", "III", "IV", "#IV", "V", "bVI", "VI", "bVII", "VII"]
    static let minorDegrees = ["I", "bII", "II", "III", "#III", "IV", "#IV", "V", "VI", "#VI", "VII", "#VII"]
    static let majorNumbers = ["1", "b2", "2", "b3", "3", "4", "#4", "5", "b6", "6", "b7", "7"]
    static let minorNumbers = ["1", "b2", "2", "3", "#3", "4", "#4", "5", "6", "#6", "7", "#7"]
}

/// A stretch of the song during which one chord (or no chord) was heard.
public struct ChordSegment: Hashable, Sendable {
    public let chord: ChordLabel?
    public let startSeconds: Float
    public let endSeconds: Float
    public init(_ chord: ChordLabel?, _ startSeconds: Float, _ endSeconds: Float) {
        self.chord = chord; self.startSeconds = startSeconds; self.endSeconds = endSeconds
    }
    public var duration: Float { endSeconds - startSeconds }
}

/// How note names are written: letters (C D E) or fixed Do (Do Re Mi, where C is always Do, as
/// in Italy, France, Spain and Latin America). Names are spelled with letters everywhere else;
/// this only rewrites them for display.
public enum NoteNaming: String, CaseIterable, Sendable {
    case letters = "letters"
    case fixedDo = "fixed_do"

    public var id: String { rawValue }

    /// Rewrites a letter-spelled name ("F#m7", "Bb") in this style ("Fa#m7", "Sib").
    public func of(_ letterName: String) -> String {
        guard self == .fixedDo, let first = letterName.first, let i = NoteNaming.lettersOrder.firstIndex(of: first) else { return letterName }
        return NoteNaming.solfege[NoteNaming.lettersOrder.distance(from: NoteNaming.lettersOrder.startIndex, to: i)] + letterName.dropFirst()
    }

    public func name(_ key: Key) -> String { of(key.name) }
    public func label(_ key: Key) -> String { "\(name(key)) \(key.mode.label)" }

    /// A chord spelled to suit `key` (sharps when there is none).
    public func chord(_ chord: ChordLabel, key: Key?) -> String { of(chord.name(key: key)) }

    static let lettersOrder = "CDEFGAB"
    static let solfege = ["Do", "Re", "Mi", "Fa", "Sol", "La", "Si"]

    public static func byId(_ id: String?) -> NoteNaming { allCases.first { $0.id == id } ?? .letters }
}

/// A bundle of analysis settings for one kind of sound source. The pipeline is the same for
/// every preset; only the bands, weights and memory change.
public struct AnalysisPreset: Hashable, Sendable, Identifiable {
    public let id: String
    public let title: String
    public let description: String
    /// Frequency band the onset detector listens to.
    public let onsetMinHz: Double
    public let onsetMaxHz: Double
    /// Log-compression strength of the onset detector; lower keeps loud/soft contrast.
    public let onsetGamma: Float
    /// Extra weight given to onsets below this frequency (kick drum, bass). 0 disables.
    public let onsetLowBandMaxHz: Double
    public let onsetLowBandWeight: Float
    /// Frequency band the key finder reads.
    public let chromaMinHz: Double
    public let chromaMaxHz: Double
    /// Peaks above this frequency count less, to keep overtones from blurring the key.
    public let chromaRollOffHz: Double
    /// How many seconds of sound the key estimate remembers.
    public let keyMemorySeconds: Double
    public let tempoPriorCenterBpm: Double
    public let tempoPriorWidthOctaves: Double
    /// Beats needed before a time signature is shown.
    public let meterMinBeats: Int
    /// Find the key from the sung melody (pitch-tracked notes) rather than from chroma alone.
    public var melodicKey: Bool = false
    /// Whether the screen shows a tempo and time signature read from the sound. Singing alone
    /// rarely gives a pulse, so with this off the player taps the tempo in instead.
    public var readsBeat: Bool = true

    /// Drums, guitar, keys and voices through a monitor. Kick and snare drive the beat.
    public static let band = AnalysisPreset(
        id: "band", title: "Band", description: "Drums, guitar, keys and voices. The kick and snare set the beat.",
        onsetMinHz: 30, onsetMaxHz: 8000, onsetGamma: 20, onsetLowBandMaxHz: 250, onsetLowBandWeight: 1.5,
        chromaMinHz: 55, chromaMaxHz: 3000, chromaRollOffHz: 1000, keyMemorySeconds: 8,
        tempoPriorCenterBpm: 120, tempoPriorWidthOctaves: 0.55, meterMinBeats: 8)

    /// Guitar-led with no drums: strums are the beat, string overtones are ignored.
    public static let guitar = AnalysisPreset(
        id: "guitar", title: "Guitar", description: "Guitar and voices, no drums. Strums set the beat.",
        onsetMinHz: 60, onsetMaxHz: 6000, onsetGamma: 10, onsetLowBandMaxHz: 0, onsetLowBandWeight: 0,
        chromaMinHz: 70, chromaMaxHz: 2500, chromaRollOffHz: 800, keyMemorySeconds: 8,
        tempoPriorCenterBpm: 120, tempoPriorWidthOctaves: 0.55, meterMinBeats: 8)

    /// Singing with little or no rhythm section: syllables are the only onsets.
    public static let voices = AnalysisPreset(
        id: "voices", title: "Voices", description: "Mostly singing. The key comes from the melody, and you tap the tempo.",
        onsetMinHz: 150, onsetMaxHz: 5000, onsetGamma: 10, onsetLowBandMaxHz: 0, onsetLowBandWeight: 0,
        chromaMinHz: 80, chromaMaxHz: 2000, chromaRollOffHz: 1000, keyMemorySeconds: 10,
        tempoPriorCenterBpm: 120, tempoPriorWidthOctaves: 0.55, meterMinBeats: 12,
        melodicKey: true, readsBeat: false)

    public static let all = [band, guitar, voices]

    public static func byId(_ id: String?) -> AnalysisPreset { all.first { $0.id == id } ?? .band }
}
