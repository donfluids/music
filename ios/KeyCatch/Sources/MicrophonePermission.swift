import AVFoundation

/// Asks for the microphone the first time; afterwards reports the answer the person gave.
/// The completion runs on the main thread.
enum MicrophonePermission {
    static func request(_ done: @escaping @MainActor (Bool) -> Void) {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: Task { @MainActor in done(true) }
        case .denied: Task { @MainActor in done(false) }
        default:
            AVAudioApplication.requestRecordPermission { granted in Task { @MainActor in done(granted) } }
        }
    }
}
