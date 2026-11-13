#if canImport(CoreML)
import CoreML
import Foundation

/// Loads the two learned models that ship in this package. Each is a Core ML package, compiled
/// on the device the first time it is needed and kept in Application Support afterwards, so
/// later launches load it at once. Core ML picks the Neural Engine where it can.
public enum KeyCatchModels {
    public enum Failure: Error { case missing(String), unexpectedOutput(String) }

    /// The compiled model for `name` ("CrepeTiny", "BasicPitchNotes", "BeatThisSmall" or "BeatThisFull"). Xcode generates a Swift
    /// class named after each model package, so a package must not share its name with a source
    /// file or type of the engine (hence not "BasicPitch", which BasicPitch.swift already is).
    public static func load(_ name: String, computeUnits: MLComputeUnits = .all) throws -> MLModel {
        let compiled = try compiledURL(name)
        let config = MLModelConfiguration()
        config.computeUnits = computeUnits
        return try MLModel(contentsOf: compiled, configuration: config)
    }

    static func compiledURL(_ name: String) throws -> URL {
        guard let source = Bundle.module.url(forResource: name, withExtension: "mlpackage") else { throw Failure.missing(name) }
        let fm = FileManager.default
        let dir = try fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("KeyCatchModels", isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        // A fingerprint of the package's manifest is part of the name: a rebuilt model gets new item
        // ids there, so new weights are compiled afresh even when the app's version number is unchanged.
        let manifest = (try? Data(contentsOf: source.appendingPathComponent("Manifest.json"))) ?? Data()
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in manifest { hash = (hash ^ UInt64(byte)) &* 0x100_0000_01b3 }
        let stamp = String(hash, radix: 16)
        let target = dir.appendingPathComponent("\(name)-\(stamp).mlmodelc", isDirectory: true)
        if fm.fileExists(atPath: target.path) { return target }
        // Older compilations of this model are no longer needed.
        for old in (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        where old.lastPathComponent.hasPrefix("\(name)-") { try? fm.removeItem(at: old) }
        let temp = try MLModel.compileModel(at: source)
        try? fm.removeItem(at: target)
        try fm.moveItem(at: temp, to: target)
        return target
    }

    /// IEEE 754 half to float, by hand: Swift's Float16 is missing on Intel Macs.
    static func halfToFloat(_ h: UInt16) -> Float {
        let sign = UInt32(h >> 15) << 31
        let exponent = Int((h >> 10) & 0x1F)
        let mantissa = UInt32(h & 0x3FF)
        if exponent == 0 {
            if mantissa == 0 { return Float(bitPattern: sign) }
            return (sign == 0 ? 1 : -1) * Float(mantissa) * powf(2, -24)
        }
        if exponent == 31 { return Float(bitPattern: sign | 0x7F80_0000 | (mantissa << 13)) }
        return Float(bitPattern: sign | (UInt32(exponent - 15 + 127) << 23) | (mantissa << 13))
    }

    /// Copies an output array into Floats in row-major order, whatever its element type and strides.
    static func floats(_ array: MLMultiArray) -> [Float] {
        let count = array.count
        var out = [Float](repeating: 0, count: count)
        let shape = array.shape.map { $0.intValue }
        let strides = array.strides.map { $0.intValue }
        func offset(_ flat: Int) -> Int {
            var rest = flat, off = 0
            for d in stride(from: shape.count - 1, through: 0, by: -1) { off += (rest % shape[d]) * strides[d]; rest /= shape[d] }
            return off
        }
        switch array.dataType {
        case .float32:
            let p = array.dataPointer.assumingMemoryBound(to: Float.self)
            for i in 0..<count { out[i] = p[offset(i)] }
        case .float16:
            let p = array.dataPointer.assumingMemoryBound(to: UInt16.self)
            for i in 0..<count { out[i] = halfToFloat(p[offset(i)]) }
        case .double:
            let p = array.dataPointer.assumingMemoryBound(to: Double.self)
            for i in 0..<count { out[i] = Float(p[offset(i)]) }
        default:
            for i in 0..<count { out[i] = array[i].floatValue }
        }
        return out
    }
}

/// CREPE-tiny through Core ML: 1024 normalised samples at 16 kHz in, 360 activations out.
public final class CoreMLCrepe: CrepeNetwork {
    private let model: MLModel
    private let input: MLMultiArray

    public init(model: MLModel) throws {
        self.model = model
        input = try MLMultiArray(shape: [1, 1024], dataType: .float32)
    }

    public convenience init() throws { try self.init(model: KeyCatchModels.load("CrepeTiny")) }

    public func activations(frame16k: [Float]) -> [Float] {
        let p = input.dataPointer.assumingMemoryBound(to: Float.self)
        for i in 0..<1024 { p[i] = i < frame16k.count ? frame16k[i] : 0 }
        guard let features = try? MLDictionaryFeatureProvider(dictionary: ["frame": MLFeatureValue(multiArray: input)]),
              let out = try? model.prediction(from: features),
              let a = out.featureValue(for: "activations")?.multiArrayValue
        else { return [Float](repeating: 0, count: 360) }
        return KeyCatchModels.floats(a)
    }
}

/// Basic Pitch through Core ML (Spotify's own export): 43,844 samples at 22,050 Hz in; 172
/// frames each of contour (264 bins), notes and onsets (88 keys) out. Only the notes are used.
public final class CoreMLBasicPitch: NoteNetwork {
    private let model: MLModel
    private let input: MLMultiArray
    /// The output holding the note probabilities. Spotify's export names its outputs
    /// Identity (contour), Identity_1 and Identity_2; the tests check which one is the notes.
    public let noteOutput: String

    public init(model: MLModel, noteOutput: String = "Identity_1") throws {
        self.model = model
        self.noteOutput = noteOutput
        input = try MLMultiArray(shape: [1, NSNumber(value: BasicPitch.WINDOW), 1], dataType: .float32)
    }

    public convenience init() throws { try self.init(model: KeyCatchModels.load("BasicPitchNotes")) }

    /// All outputs for one window, keyed by name, each flattened row-major.
    public func outputs(window: [Float]) -> [String: [Float]] {
        let p = input.dataPointer.assumingMemoryBound(to: Float.self)
        for i in 0..<BasicPitch.WINDOW { p[i] = i < window.count ? window[i] : 0 }
        guard let features = try? MLDictionaryFeatureProvider(dictionary: ["input_2": MLFeatureValue(multiArray: input)]),
              let out = try? model.prediction(from: features)
        else { return [:] }
        var result: [String: [Float]] = [:]
        for name in out.featureNames { if let a = out.featureValue(for: name)?.multiArrayValue { result[name] = KeyCatchModels.floats(a) } }
        return result
    }

    public func notes(window: [Float], from: Int, to: Int) -> [[Float]] {
        let all = outputs(window: window)[noteOutput] ?? [Float](repeating: 0, count: BasicPitch.FRAMES * BasicPitch.NOTES)
        return (from..<to).map { t in Array(all[(t * BasicPitch.NOTES)..<((t + 1) * BasicPitch.NOTES)]) }
    }
}
/// Beat This! through Core ML (converted by tools/beatthis/convert_coreml.py, FP16): input
/// "spect", (1, 412, 128) log-mel frames; outputs "beat" and "downbeat", (1, 412) logits.
public final class CoreMLBeatThis: BeatNetwork {
    private let model: MLModel
    private let input: MLMultiArray

    public init(model: MLModel) throws {
        self.model = model
        input = try MLMultiArray(shape: [1, NSNumber(value: BeatThis.INPUT_FRAMES), NSNumber(value: BeatThis.MELS)], dataType: .float32)
    }

    public convenience init(_ which: BeatThisModel) throws { try self.init(model: KeyCatchModels.load(which.resourceName)) }

    public func logits(_ spect: [Float]) -> (beat: [Float], downbeat: [Float])? {
        let count = BeatThis.INPUT_FRAMES * BeatThis.MELS
        guard spect.count == count else { return nil }
        let p = input.dataPointer.assumingMemoryBound(to: Float.self)
        spect.withUnsafeBufferPointer { s in for i in 0..<count { p[i] = s[i] } }
        guard let features = try? MLDictionaryFeatureProvider(dictionary: ["spect": MLFeatureValue(multiArray: input)]),
              let out = try? model.prediction(from: features),
              let beat = out.featureValue(for: "beat")?.multiArrayValue,
              let downbeat = out.featureValue(for: "downbeat")?.multiArrayValue
        else { return nil }
        return (KeyCatchModels.floats(beat), KeyCatchModels.floats(downbeat))
    }
}
#endif
