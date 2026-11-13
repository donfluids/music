import Foundation

/// The Basic Pitch network itself: on iPhone it runs through Core ML.
public protocol NoteNetwork: AnyObject {
    /// Note probabilities for frames [from] until [to] of [window] ([BasicPitch.WINDOW] samples
    /// at [BasicPitch.SAMPLE_RATE], oldest first; the window gives [BasicPitch.FRAMES] frames):
    /// (to - from) rows of [BasicPitch.NOTES], lowest key (A0) first.
    func notes(window: [Float], from: Int, to: Int) -> [[Float]]
}

/// The notes being played, heard by Basic Pitch (through [NoteNetwork]), turned into pitch-class
/// energy for the chord and key finders in place of the spectral chroma.
///
/// Audio is resampled to 22,050 Hz into a 2-second window. Every [runEverySeconds] the model
/// runs on that window and the frames from just before its last 0.17 s are kept (the model's
/// kernels need that much of what follows; the official inference trims the same amount).
/// A frame for time t is therefore ready by t + [delaySeconds]; the analyser reads that far
/// behind and dates what it finds to when it was played.
///
/// With a [worker], the model runs there instead of on the caller's (audio) thread, and the
/// delay grows to cover how long a run takes on this phone.
public final class HeardNotes {
    /// Frames at the end of each window that are not used: the kernels' reach, as the official inference trims.
    public static let KEEP_END = BasicPitch.FRAMES - 15
    private static let HISTORY = 512

    private let model: NoteNetwork
    public let inputRate: Int
    public let runEverySeconds: Double
    /// Window RMS below which the model is not run: silence normalised up is all noise.
    private let minLevel: Double
    /// Note probability that counts as nothing; above it, the excess counts.
    private let floor: Float
    /// Extra weight for the lowest note sounding below middle C, which usually names the root.
    /// On 180 GuitarSet accompaniments 1 gave the most right chords and keys (0, 1 and 2 tried),
    /// and on 300 IDMT progressions it cost nothing.
    private let bassBoost: Float
    /// Seconds added to each frame's date; 0.1 was tried and helped GuitarSet less than it cost IDMT.
    private let dateShift: Double
    /// Shift the audio onto concert pitch by [tuningCents] before the model hears it. Off: fed
    /// the chroma's tuning grid, it lost on GuitarSet both in tune and 39 cents sharp (key 0.81
    /// to 0.69 there). The grid reads overtones, which run sharp on strings, and near half a
    /// semitone it can land on the wrong side; the model copes with the detuning better alone.
    private let followTuning: Bool
    /// A serial queue to run the model on; nil runs it inline on the caller's thread.
    private let worker: DispatchQueue?

    private let resampler: StreamResampler
    private var ring = [Float](repeating: 0, count: BasicPitch.WINDOW)
    /// Input time of each sample in [ring], in seconds.
    private var ringTime = [Double](repeating: 0, count: BasicPitch.WINDOW)
    private var written = 0
    private let runFrames: Int
    /// The first run comes as soon as its kept frames reach back to the first sample, on a window
    /// that is silence before it (the official inference pads the start the same way). Waiting
    /// for a full window lost the first 1.3 s of every song.
    private let firstRun: Int
    private var nextRun: Int
    private var notes: [[Float]]

    /// A window waiting for the model, with the input time of each frame it will give.
    private final class Job {
        var window: [Float]
        var times: [Double]
        var generation = 0
        init(_ window: [Float], _ times: [Double]) { self.window = window; self.times = times }
    }
    /// The free jobs (at most three windows in flight), guarded by [poolLock].
    private var free: [Job] = []
    private let poolLock = NSLock()
    private var generation = 0

    private let baseDelay: Double

    // Emitted frames: time (seconds of input audio) and pitch-class energy, in a ring.
    // [lock] guards these, [generation] and the diagnostics below.
    private let lock = NSLock()
    private var times = [Double](repeating: -Double.infinity, count: HeardNotes.HISTORY)
    private var chroma = [[Float]](repeating: [Float](repeating: 0, count: 12), count: HeardNotes.HISTORY)
    private var head = 0

    private var delayValue: Double
    private var millisValue = 0.0
    private var skippedValue = 0

    public init(
        _ network: NoteNetwork,
        _ inputRate: Int,
        runEverySeconds: Double = 0.5,
        minLevel: Double = 0.002,
        floor: Float = 0.2,
        bassBoost: Float = 1,
        dateShift: Double = 0.0,
        followTuning: Bool = false,
        worker: DispatchQueue? = nil
    ) {
        self.model = network
        self.inputRate = inputRate
        self.runEverySeconds = runEverySeconds
        self.minLevel = minLevel
        self.floor = floor
        self.bassBoost = bassBoost
        self.dateShift = dateShift
        self.followTuning = followTuning
        self.worker = worker
        resampler = StreamResampler(inputRate, BasicPitch.SAMPLE_RATE)
        let runFrames = Int(runEverySeconds * Double(BasicPitch.SAMPLE_RATE) / Double(BasicPitch.HOP)).coerceIn(1, HeardNotes.KEEP_END)
        self.runFrames = runFrames
        firstRun = BasicPitch.WINDOW - (HeardNotes.KEEP_END - runFrames) * BasicPitch.HOP
        nextRun = firstRun
        notes = [[Float]](repeating: [Float](repeating: 0, count: BasicPitch.NOTES), count: runFrames)
        for _ in 0..<3 { free.append(Job([Float](repeating: 0, count: BasicPitch.WINDOW), [Double](repeating: 0, count: runFrames))) }
        baseDelay = Double(runFrames + BasicPitch.FRAMES - HeardNotes.KEEP_END) * Double(BasicPitch.HOP) / Double(BasicPitch.SAMPLE_RATE) * 1.03 + 0.01
        delayValue = baseDelay + (worker != nil ? 0.25 : 0.0)
    }

    /// Audio time, in seconds, after which the frames for a moment are known. Only grows.
    public var delaySeconds: Double { lock.lock(); defer { lock.unlock() }; return delayValue }

    /// How far the music sits from concert pitch, in cents (the analyser's tuning grid). The
    /// model was trained on music at concert pitch; a band 40 cents sharp would fall between
    /// its keys, so the audio is shifted back before it hears it.
    public var tuningCents: Double {
        get { resampler.shiftCents }
        set { if followTuning { resampler.shiftCents = newValue } }
    }

    /// Running average cost, in milliseconds per second of audio, for the on-screen diagnostics.
    public var millisPerSecond: Double { lock.lock(); defer { lock.unlock() }; return millisValue }
    /// Windows the worker could not keep up with and skipped.
    public var skipped: Int { lock.lock(); defer { lock.unlock() }; return skippedValue }

    public func reset() {
        resampler.reset()
        for i in 0..<ring.count { ring[i] = 0 }
        for i in 0..<ringTime.count { ringTime[i] = 0 }
        written = 0
        nextRun = firstRun
        lock.lock()
        generation += 1
        for i in 0..<times.count { times[i] = -Double.infinity }
        head = 0
        lock.unlock()
    }

    public func push(_ samples: [Float], _ offset: Int, _ count: Int) {
        let size = ring.count
        resampler.push(samples, offset, count) { v in
            let at = written % size
            ring[at] = v
            ringTime[at] = resampler.lastTime
            written += 1
            if written >= nextRun { schedule(); nextRun += runFrames * BasicPitch.HOP }
        }
    }

    private func schedule() {
        poolLock.lock()
        let job = free.isEmpty ? nil : free.removeFirst()
        poolLock.unlock()
        guard let job else { lock.lock(); skippedValue += 1; lock.unlock(); return }
        // Oldest first: the ring's write position is the oldest sample.
        let size = ring.count
        let pos = written % size
        ring.withUnsafeBufferPointer { src in
            job.window.withUnsafeMutableBufferPointer { dst in
                for i in 0..<(size - pos) { dst[i] = src[pos + i] }
                for i in 0..<pos { dst[size - pos + i] = src[i] }
            }
        }
        let from = HeardNotes.KEEP_END - runFrames
        // Each frame's centre, in input time (the resampler may be stretching time to shift pitch).
        for k in 0..<runFrames { job.times[k] = ringTime[(pos + (from + k) * BasicPitch.HOP) % size] + dateShift }
        lock.lock()
        job.generation = generation
        lock.unlock()
        if let worker { worker.async { self.compute(job) } } else { compute(job) }
    }

    private func compute(_ job: Job) {
        let started = DispatchTime.now().uptimeNanoseconds
        let window = job.window
        let from = HeardNotes.KEEP_END - runFrames
        var sq = 0.0
        let tail = (BasicPitch.FRAMES - from) * BasicPitch.HOP
        for i in (window.count - tail)..<window.count { sq += Double(window[i]) * Double(window[i]) }
        let loud = (sq / Double(tail)).squareRoot() >= minLevel
        if loud {
            let rows = model.notes(window: window, from: from, to: HeardNotes.KEEP_END)
            precondition(rows.count == runFrames && rows.allSatisfy { $0.count == BasicPitch.NOTES }, "expected \(runFrames) rows of \(BasicPitch.NOTES) notes")
            notes = rows
        } else {
            for k in 0..<notes.count { for n in 0..<BasicPitch.NOTES { notes[k][n] = 0 } }
        }
        lock.lock()
        if job.generation == generation {
            for k in 0..<runFrames {
                var c = [Float](repeating: 0, count: 12)
                let row = notes[k]
                for n in 0..<BasicPitch.NOTES {
                    let p = row[n] - floor
                    if p > 0 { c[(n + BasicPitch.LOWEST_MIDI) % 12] += p / (1 - floor) }
                }
                if bassBoost > 0 {
                    for n in 0..<(60 - BasicPitch.LOWEST_MIDI) where row[n] > 0.5 { c[(n + BasicPitch.LOWEST_MIDI) % 12] += bassBoost * row[n]; break }
                }
                chroma[head] = c
                times[head] = job.times[k]
                head = (head + 1) % HeardNotes.HISTORY
            }
        }
        lock.unlock()
        poolLock.lock()
        if free.count < 3 { free.append(job) }
        poolLock.unlock()
        let seconds = Double(DispatchTime.now().uptimeNanoseconds - started) / 1e9
        if loud {
            lock.lock()
            millisValue = millisValue == 0.0 ? seconds * 1000 / runEverySeconds : 0.8 * millisValue + 0.2 * seconds * 1000 / runEverySeconds
            // On a worker, results arrive a run's time late: read further behind if needed.
            if worker != nil { delayValue = max(delayValue, min(baseDelay + 1.5 * seconds + 0.05, baseDelay + 1.5)) }
            lock.unlock()
        }
    }

    /// Adds the pitch-class energy of the frames played between [fromSeconds] and [toSeconds]
    /// to [out], averaged per frame. Returns how many frames there were.
    @discardableResult
    public func pitchClasses(fromSeconds: Double, toSeconds: Double, out: inout [Float]) -> Int {
        var n = 0
        var sum = [Float](repeating: 0, count: 12)
        lock.lock()
        for i in 0..<HeardNotes.HISTORY {
            let t = times[i]
            if t >= fromSeconds && t < toSeconds { n += 1; let c = chroma[i]; for pc in 0..<12 { sum[pc] += c[pc] } }
        }
        lock.unlock()
        if n > 0 { for pc in 0..<12 { out[pc] += sum[pc] / Float(n) } }
        return n
    }
}

/// Streaming windowed-sinc resampler (44.1 or 48 kHz down to 22,050 Hz) that can also shift
/// pitch a little: with [shiftCents] set, input time advances 2^(-cents/1200) as fast as output
/// time, so music that many cents sharp comes out at concert pitch. The filter is tabulated for
/// [PHASES] fractional positions, so nothing is computed per tap but the products.
public final class StreamResampler {
    public static let PHASES = 256

    private let inRate: Int
    private let outRate: Int
    private let step: Double
    private let halfTaps: Int
    private let taps: Int
    private let table: [[Float]]
    /// Cents to lower the pitch by; positive for music that sits sharp.
    public var shiftCents: Double = 0.0 {
        didSet { advance = step * pow(2.0, -shiftCents / 1200.0) }
    }
    private var advance: Double
    private var history: [Float]
    private var historyStart = 0   // input index of history[0]
    private var historyLength = 0
    /// Input position (in input samples) of the next output sample.
    private var position = 0.0
    /// Input time, in seconds, of the sample [next] last returned.
    public private(set) var lastTime = 0.0

    public init(_ inRate: Int, _ outRate: Int, cutoffHz: Double = 9500.0) {
        precondition(inRate >= outRate, "only downsampling")
        self.inRate = inRate
        self.outRate = outRate
        let step = Double(inRate) / Double(outRate)
        self.step = step
        let halfTaps = Int(32 * step)
        self.halfTaps = halfTaps
        let taps = 2 * halfTaps + 1
        self.taps = taps
        table = (0...StreamResampler.PHASES).map { p -> [Float] in
            let frac = Double(p) / Double(StreamResampler.PHASES)
            let fc = cutoffHz / Double(inRate)
            return (0..<taps).map { k -> Float in
                let t = Double(k - halfTaps) - frac
                let u = t / Double(halfTaps + 1)
                let window = 0.42 + 0.5 * cos(Double.pi * u) + 0.08 * cos(2 * Double.pi * u)
                let x = 2.0 * fc * t
                let sinc = abs(x) < 1e-9 ? 1.0 : sin(Double.pi * x) / (Double.pi * x)
                return Float(2.0 * fc * sinc * window)
            }
        }
        advance = step
        history = [Float](repeating: 0, count: taps + 8192)
    }

    public func reset() {
        historyStart = 0; historyLength = 0; position = 0.0; lastTime = 0.0
        for i in 0..<history.count { history[i] = 0 }
    }

    public func push(_ samples: [Float], _ offset: Int, _ count: Int, _ emit: (Float) -> Void) {
        var i = 0
        while i < count {
            let n = append(samples, offset + i, count - i)
            i += n
            while true {
                let v = next()
                if v.isNaN { break }
                emit(v)
            }
        }
    }

    /// Appends as many samples as fit; returns how many.
    @discardableResult
    public func append(_ samples: [Float], _ offset: Int, _ count: Int) -> Int {
        // Drop what no future output can reach.
        let keepFrom = Int(position.rounded(.down)) - halfTaps
        let drop = (keepFrom - historyStart).coerceIn(0, historyLength)
        let n = min(count, history.count - (historyLength - drop))
        let historyLength = self.historyLength
        history.withUnsafeMutableBufferPointer { h in
            if drop > 0 {
                for k in 0..<(historyLength - drop) { h[k] = h[drop + k] }
            }
            samples.withUnsafeBufferPointer { s in
                for k in 0..<n { h[historyLength - drop + k] = s[offset + k] }
            }
        }
        self.historyLength = historyLength - drop + n
        historyStart += drop
        return n
    }

    /// The next output sample, or NaN when more input is needed.
    public func next() -> Float {
        let base = Int(position.rounded(.down))
        let first = base - halfTaps
        if first + taps > historyStart + historyLength { return Float.nan }
        let w = table[roundToInt((position - Double(base)) * Double(StreamResampler.PHASES))]
        var s: Float = 0
        let offset = first - historyStart
        history.withUnsafeBufferPointer { h in
            w.withUnsafeBufferPointer { wp in
                for k in 0..<taps {
                    let idx = offset + k
                    if idx >= 0 { s += h[idx] * wp[k] }
                }
            }
        }
        lastTime = position / Double(inRate)
        position += advance
        return s
    }
}
