import Foundation

/// The constants of Basic Pitch (Bittner, Bosch, Rubinstein, Meseguer-Brocal and Ewert, ICASSP
/// 2022; Apache licence), the note-transcription model [HeardNotes] listens through. On iPhone
/// the model itself runs through Core ML (see [NoteNetwork]); only its framing lives here.
public enum BasicPitch {
    public static let SAMPLE_RATE = 22050
    public static let HOP = 256
    /// Two seconds less one hop, as the model was trained.
    public static let WINDOW = 2 * SAMPLE_RATE - HOP
    public static let FRAMES = 172
    public static let NOTES = 88
    /// MIDI number of the lowest key.
    public static let LOWEST_MIDI = 21
}
