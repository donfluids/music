import AVFoundation
import Foundation

/// The microphone, as mono 32-bit float samples at the hardware rate (48 kHz on most iPhones).
///
/// The session uses measurement mode: no automatic gain, noise suppression or echo
/// cancellation, which would flatten the dynamics the beat tracker relies on (Android asks for
/// the unprocessed source for the same reason). Samples are copied off the audio engine's
/// thread and handed to `onAudio` on one serial queue, in order, with the time the last of
/// them was captured (uptime nanoseconds, the clock the screen animates the beat with).
final class AudioCapture {
    private let engine = AVAudioEngine()
    private let queue: DispatchQueue
    private(set) var sampleRate = 48_000
    private var interruptionObserver: NSObjectProtocol?
    private var running = false

    var onAudio: (([Float], Int, Int) -> Void)?
    var onError: ((String) -> Void)?

    init(queue: DispatchQueue) { self.queue = queue }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: [])
        try session.setActive(true)
        let input = engine.inputNode
        let format = input.inputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw CaptureError.noMicrophone }
        sampleRate = Int(format.sampleRate)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            guard let self, let channels = buffer.floatChannelData else { return }
            let n = Int(buffer.frameLength)
            // The first channel: the iPhone's microphone is mono, and a headset's is too.
            let samples = Array(UnsafeBufferPointer(start: channels[0], count: n))
            let now = Int(DispatchTime.now().uptimeNanoseconds)
            self.queue.async { self.onAudio?(samples, n, now) }
        }
        engine.prepare()
        try engine.start()
        running = true
        // A phone call or another app taking the microphone ends the session, as on Android.
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: session, queue: .main
        ) { [weak self] note in
            guard let self, self.running,
                  let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            self.onError?("Listening stopped: another app is using the microphone.")
        }
    }

    func stop() {
        guard running else { return }
        running = false
        if let o = interruptionObserver { NotificationCenter.default.removeObserver(o) }
        interruptionObserver = nil
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        // Wait for the samples already queued, so nothing arrives after stop returns.
        queue.sync {}
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    enum CaptureError: LocalizedError {
        case noMicrophone
        var errorDescription: String? { "Could not open the microphone" }
    }
}
