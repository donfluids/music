import Foundation

/// The constants of Beat This! (Foscarin, Schlüter and Widmer, ISMIR 2024; MIT licence), the
/// beat and downbeat model the Band and Guitar presets can follow the beat with ("Accurate" in
/// Settings). On iPhone the network runs through Core ML (see [BeatNetwork]); its front end,
/// postprocessing and the reading the screen needs live here, in plain Swift. The spec, and the
/// Python reference every part is checked against, are in tools/beatthis (README sections 2 and
/// 5, bt_live.py).
public enum BeatThis {
    public static let SAMPLE_RATE = 22050
    public static let N_FFT = 1024
    /// 20 ms: 50 frames per second.
    public static let HOP = 441
    public static let FPS = 50
    public static let MELS = 128
    /// Zero frames on each side of a window, as the official inference pads a short excerpt.
    public static let BORDER = 6
    /// The window the network sees: the newest 8 s of frames.
    public static let FRAMES = 400
    /// The converted models take exactly this many frames.
    public static let INPUT_FRAMES = FRAMES + 2 * BORDER
    /// The first reading comes after this many seconds, then one every second.
    public static let FIRST_READING_SECONDS = 4
    static let F_MIN = 30.0
    static let F_MAX = 11000.0
    static let LOG_MULTIPLIER: Float = 1000
    /// The resampler's cutoff. Measured against the official soxr resampling on music: 9.5 kHz
    /// changed the logits by up to 10 and moved 5 % of the beats; 10.5 kHz by 0.8, 1 % of beats.
    public static let RESAMPLER_CUTOFF_HZ = 10500.0
}

/// The two sizes of Beat This! the app carries: small for Band (drums make the beat easy, and it
/// is a tenth of the size), the full one for Guitar, where it is clearly better without drums.
public enum BeatThisModel: String, Sendable, CaseIterable {
    case small
    case full

    /// The Core ML package. Xcode generates a Swift class named after each model package, so a
    /// package must not share its name with a source file or type of the engine.
    public var resourceName: String { self == .small ? "BeatThisSmall" : "BeatThisFull" }

    /// For the Details fold.
    public var title: String { self == .small ? "Beat This! small" : "Beat This!" }
}

/// The Beat This! network itself: on iPhone it runs through Core ML.
public protocol BeatNetwork: AnyObject {
    /// Beat and downbeat logits for [BeatThis.INPUT_FRAMES] frames of [BeatThis.MELS] log-mel
    /// values (time-major, oldest first): two arrays of [BeatThis.INPUT_FRAMES] logits, or nil
    /// when the network failed.
    func logits(_ spect: [Float]) -> (beat: [Float], downbeat: [Float])?
}

/// The Beat This! front end (the official LogMelSpect): 22,050 Hz mono, a centred STFT (n_fft
/// 1024, hop 441, periodic Hann), |X| / sqrt(1024), 128 triangular Slaney mel filters from 30 to
/// 11,000 Hz without area normalisation, then ln(1 + 1000 mel). Frames are made one by one as
/// the audio arrives: frame t is centred on sample 441 t and is ready once sample 441 t + 511
/// is in. Samples before the first are silence (the official code reflects the signal there;
/// only the first two frames see the difference). The newest frames are kept in a ring.
public final class BeatThisFrontEnd {
    /// Each mel band's first FFT bin and its weights from there on.
    public static let filterbank: [(Int, [Float])] = {
        func hzToMel(_ f: Double) -> Double { f >= 1000.0 ? 15.0 + log(f / 1000.0) / (log(6.4) / 27.0) : 3.0 * f / 200.0 }
        func melToHz(_ m: Double) -> Double { m >= 15.0 ? 1000.0 * exp(log(6.4) / 27.0 * (m - 15.0)) : 200.0 * m / 3.0 }
        let bins = BeatThis.N_FFT / 2 + 1
        // As torchaudio: bin k sits at k * (sr / 2) / 512 Hz with the integer Nyquist, 11,025 Hz.
        let step = Double(BeatThis.SAMPLE_RATE / 2) / Double(bins - 1)
        let lo = hzToMel(BeatThis.F_MIN)
        let hi = hzToMel(BeatThis.F_MAX)
        let points = BeatThis.MELS + 2
        // As numpy's linspace: i * step + start, the last point exactly the end.
        let melStep = (hi - lo) / Double(points - 1)
        let edges = (0..<points).map { i in melToHz(i == points - 1 ? hi : Double(i) * melStep + lo) }
        return (0..<BeatThis.MELS).map { i in
            var first = -1
            var weights: [Float] = []
            for k in 0..<bins {
                let f = Double(k) * step
                let down = (f - edges[i]) / (edges[i + 1] - edges[i])
                let up = (edges[i + 2] - f) / (edges[i + 2] - edges[i + 1])
                let w = max(0.0, min(down, up))
                if w > 0 {
                    if first < 0 { first = k }
                    weights.append(Float(w))
                } else if first >= 0 {
                    break
                }
            }
            return (max(first, 0), weights)
        }
    }()

    public let capacity: Int
    private let fft = Fft(BeatThis.N_FFT)
    private let window = Fft.hannWindow(BeatThis.N_FFT)
    private var audio = [Float](repeating: 0, count: BeatThis.N_FFT)
    private var written = 0
    private var re = [Float](repeating: 0, count: BeatThis.N_FFT)
    private var im = [Float](repeating: 0, count: BeatThis.N_FFT)
    private var magnitude = [Float](repeating: 0, count: BeatThis.N_FFT / 2 + 1)
    private var frames: [Float]
    /// Frames made so far; frame t is in the ring until `capacity` newer ones are made.
    public private(set) var framesMade = 0

    public init(capacity: Int = BeatThis.FRAMES + 100) {
        self.capacity = capacity
        frames = [Float](repeating: 0, count: capacity * BeatThis.MELS)
    }

    public func reset() {
        for i in audio.indices { audio[i] = 0 }
        written = 0
        framesMade = 0
    }

    /// Adds one sample; makes a frame when this sample completes one.
    public func push(_ v: Float) {
        audio[written % BeatThis.N_FFT] = v
        written += 1
        if written == BeatThis.HOP * framesMade + BeatThis.N_FFT / 2 { makeFrame() }
    }

    private func makeFrame() {
        let n = BeatThis.N_FFT
        // The ring's write position is the oldest of the last 1024 samples (zeros before the start).
        let pos = written % n
        for i in 0..<n {
            re[i] = audio[(pos + i) % n] * window[i]
            im[i] = 0
        }
        fft.transform(&re, &im)
        let scale = 1 / Float(n).squareRoot()
        for k in 0...(n / 2) { magnitude[k] = (re[k] * re[k] + im[k] * im[k]).squareRoot() * scale }
        let base = (framesMade % capacity) * BeatThis.MELS
        for (i, band) in BeatThisFrontEnd.filterbank.enumerated() {
            var s: Float = 0
            let (first, weights) = band
            for j in weights.indices { s += weights[j] * magnitude[first + j] }
            frames[base + i] = log1p(BeatThis.LOG_MULTIPLIER * s)
        }
        framesMade += 1
    }

    /// Frame `t` (0 = the first), or zeros when it is not in the ring.
    public func frame(_ t: Int) -> [Float] {
        guard t >= 0, t < framesMade, t >= framesMade - capacity else { return [Float](repeating: 0, count: BeatThis.MELS) }
        let base = (t % capacity) * BeatThis.MELS
        return Array(frames[base..<(base + BeatThis.MELS)])
    }

    /// The network's input for the [BeatThis.FRAMES] frames ending at `last`: border zeros, the
    /// frames (zeros for those before the first), border zeros. `out` holds INPUT_FRAMES x MELS.
    public func window(endingAt last: Int, into out: inout [Float]) {
        let m = BeatThis.MELS
        for i in out.indices { out[i] = 0 }
        let first = last - BeatThis.FRAMES + 1
        for r in 0..<BeatThis.FRAMES {
            let t = first + r
            guard t >= 0, t < framesMade, t >= framesMade - capacity else { continue }
            let src = (t % capacity) * m
            let dst = (BeatThis.BORDER + r) * m
            for i in 0..<m { out[dst + i] = frames[src + i] }
        }
    }
}

/// The official "minimal" postprocessing (beat_this/model/postprocessor.py), for one window.
public enum BeatPostprocessing {
    /// Frames picked from one logit sequence: a frame equal to the maximum of the 7 frames around
    /// it (beyond the ends counts as nothing) with a logit above 0; runs of picked frames that
    /// are at most one frame from their running mean become that mean (so it can be fractional).
    public static func peakFrames(_ x: [Float]) -> [Double] {
        let n = x.count
        var picked: [Int] = []
        for t in 0..<n where x[t] > 0 {
            var m = -Float.infinity
            for j in max(0, t - 3)..<min(n, t + 4) where x[j] > m { m = x[j] }
            if x[t] == m { picked.append(t) }
        }
        var out: [Double] = []
        guard let first = picked.first else { return out }
        var p = Double(first)
        var c = 1
        for p2 in picked.dropFirst() {
            if Double(p2) - p <= 1 {
                c += 1
                p += (Double(p2) - p) / Double(c)
            } else {
                out.append(p)
                p = Double(p2)
                c = 1
            }
        }
        out.append(p)
        return out
    }

    /// Beat and downbeat times, in seconds from the window's first frame. Each downbeat is moved
    /// to the nearest beat (the first of two equally near), and duplicates are dropped.
    public static func beatsAndDownbeats(beat: [Float], downbeat: [Float]) -> (beats: [Double], downbeats: [Double]) {
        let fps = Double(BeatThis.FPS)
        let beats = peakFrames(beat).map { $0 / fps }
        var downs = peakFrames(downbeat).map { $0 / fps }
        if !beats.isEmpty {
            for i in downs.indices {
                var best = 0
                for j in beats.indices where abs(beats[j] - downs[i]) < abs(beats[best] - downs[i]) { best = j }
                downs[i] = beats[best]
            }
        }
        var unique: [Double] = []
        for d in downs.sorted() where unique.last != d { unique.append(d) }
        return (beats, unique)
    }
}

/// What one window says, as the screen reads it.
public struct BeatReading: Sendable {
    /// Seconds of input audio at the window's end.
    public var moment: Double = 0
    /// Beats and downbeats in the window, in seconds of input audio.
    public var beats: [Double] = []
    public var downbeats: [Double] = []
    public var bpm: Double? = nil
    /// The median of the last 8 beat intervals.
    public var periodSeconds: Double? = nil
    /// The newest beat; the next ones are extrapolated from it by whole periods.
    public var lastBeatSeconds: Double? = nil
    /// 2, 3 or 4, from the downbeat comb pooled over readings.
    public var beatsPerBar: Int? = nil
    /// Bar position (0 = downbeat) of the beat at `lastBeatSeconds`.
    public var lastBeatIndexInBar = 0
    /// The last three readings agree within 4 %.
    public var locked = false
    /// Mean beat probability at the picked beats, 0..1.
    public var strength = 0.0
    /// The comb's pooled score per bar length.
    public var barScores: [Int: Double] = [:]
    /// Each beat divides in three (6/8, 9/8, 12/8), from the slow decision of [BeatFeel].
    public var compound = false

    public init() {}

    /// The time signature to show, or nil without one.
    public var meterLabel: String? {
        guard let b = beatsPerBar else { return nil }
        if compound { return b == 2 ? "6/8" : (b == 3 ? "9/8" : "12/8") }
        return "\(b)/4"
    }

    /// How far the winning bar length leads the next, 0..1.
    public var meterConfidence: Double {
        guard let b = beatsPerBar, let best = barScores[b], best > 0 else { return 0 }
        let next = barScores.filter { $0.key != b }.values.max() ?? 0
        return ((best - next) / best).coerceIn(0.0, 1.0)
    }
}

/// Turns each window's logits into a reading (tools/beatthis/bt_live.py `Reader`, after
/// live_eval.py `bt_readouts`): tempo from the median of the last 8 beat intervals (3 beats
/// needed), the newest beat, and beats per bar by a comb on the downbeat probability. For 2, 3
/// and 4 beats per bar and each phase, the mean downbeat probability on the bar grid minus the
/// mean on the other beats; each bar length keeps its best phase's score, the scores halve at
/// every reading so a few windows are pooled, and the highest wins.
public final class BeatReader {
    public static let BARS = [2, 3, 4]
    private let decay: Double
    private let intervals: Int
    private var scores: [Int: Double] = [:]
    private var recent: [Double?] = []
    private var previous: (last: Double, index: Int, period: Double)? = nil

    public init(decay: Double = 0.5, intervals: Int = 8) {
        self.decay = decay
        self.intervals = intervals
        reset()
    }

    public func reset() {
        scores = [2: 0, 3: 0, 4: 0]
        recent = []
        previous = nil
    }

    /// - Parameters:
    ///   - now: seconds of input audio at the window's end
    ///   - firstFrame: frame number (50 per second of input) of the window's first frame
    ///   - beat, downbeat: the window's [BeatThis.FRAMES] logits, borders removed
    public func read(now: Double, firstFrame: Int, beat: [Float], downbeat: [Float]) -> BeatReading {
        let fps = Double(BeatThis.FPS)
        let (local, localDowns) = BeatPostprocessing.beatsAndDownbeats(beat: beat, downbeat: downbeat)
        var r = BeatReading()
        r.moment = now
        let offset = Double(firstFrame) / fps
        r.beats = local.map { $0 + offset }
        r.downbeats = localDowns.map { $0 + offset }
        let heard = r.beats.filter { $0 <= now + 1e-9 }
        if heard.count >= 3 {
            let tail = Array(heard.suffix(intervals + 1))
            var ibis: [Double] = []
            for i in 1..<tail.count { ibis.append(tail[i] - tail[i - 1]) }
            ibis.sort()
            let n = ibis.count
            let period = n % 2 == 1 ? ibis[n / 2] : (ibis[n / 2 - 1] + ibis[n / 2]) / 2
            r.periodSeconds = period
            if period > 0 { r.bpm = 60.0 / period }
            r.lastBeatSeconds = heard.last
        }
        // Downbeat probability at each picked beat: the largest logit within one frame of it.
        let q: [Double] = local.map { v in
            let c = Int((v * fps).rounded(.toNearestOrEven))
            var m = -Float.infinity
            for j in max(0, c - 1)..<min(downbeat.count, c + 2) where downbeat[j] > m { m = downbeat[j] }
            return 1.0 / (1.0 + exp(-Double(m)))
        }
        var phases: [Int: Int] = [:]
        for bb in BeatReader.BARS {
            scores[bb] = scores[bb]! * decay
            if q.count >= 2 * bb {
                var best = -1.0
                for ph in 0..<bb {
                    var on = 0.0, onCount = 0, off = 0.0, offCount = 0
                    for i in q.indices {
                        if floorMod(i - ph, bb) == 0 { on += q[i]; onCount += 1 } else { off += q[i]; offCount += 1 }
                    }
                    let v = on / Double(onCount) - off / Double(offCount)
                    if v > best { best = v; phases[bb] = ph }
                }
                scores[bb] = scores[bb]! + best
            }
        }
        r.barScores = scores
        if scores.values.contains(where: { $0 != 0 }) {
            // The first of equal scores, in the order 2, 3, 4.
            var bpb = BeatReader.BARS[0]
            for bb in BeatReader.BARS where scores[bb]! > scores[bpb]! { bpb = bb }
            r.beatsPerBar = bpb
        }
        // Where the newest beat sits in the bar: this window's best comb phase for the chosen
        // bar length, or without one the previous reading's position moved on by whole beats.
        if let bpb = r.beatsPerBar, let last = r.lastBeatSeconds {
            if let ph = phases[bpb] {
                r.lastBeatIndexInBar = floorMod(q.count - 1 - ph, bpb)
            } else if let p = previous, p.period > 0 {
                r.lastBeatIndexInBar = floorMod(p.index + Int(((last - p.last) / p.period + 0.5).rounded(.down)), bpb)
            }
        }
        recent.append(r.bpm)
        if recent.count > 3 { recent.removeFirst() }
        let known = recent.compactMap { $0 }
        r.locked = recent.count == 3 && known.count == 3 && known.max()! / known.min()! < 1.04
        if let last = r.lastBeatSeconds, let period = r.periodSeconds { previous = (last, r.lastBeatIndexInBar, period) }
        if !local.isEmpty {
            var s = 0.0
            for v in local {
                let c = Int((v * fps).rounded(.toNearestOrEven)).coerceIn(0, beat.count - 1)
                s += 1.0 / (1.0 + exp(-Double(beat[c])))
            }
            r.strength = s / Double(local.count)
        }
        return r
    }
}

/// Simple or compound, decided slowly on the Beat This! beat grid (tools/beatthis README section
/// 5; bt_live.py `Feel`). The network does not say whether a beat divides in two or three, so the
/// onset signal the app already has is read at the beat, halfway, and a third and two thirds of
/// the way to the next beat, as MeterDetector does. That evidence is weak (12/8 looks much like
/// 4/4 with swing), so it is pooled over about 20 s and compound is shown only when it is strong:
/// on 289 GTZAN songs in simple time it showed compound at under 1 % of moments.
public final class BeatFeel {
    static let POOL = 0.95
    static let MIN_WEIGHT = 10.0
    static let ON_RATIO = 2.0
    static let OFF_RATIO = 1.1
    static let BALANCE = 0.5
    static let BEAT_SHARE = 0.25
    static let HOLD = 5
    static let PERIOD_TOLERANCE = 0.08

    private var pooled = [Double](repeating: 0, count: 4)
    private var weight = 0.0
    private var period = 0.0
    public private(set) var compound = false
    private var on = 0
    private var off = 0

    public init() {}

    public func reset() {
        for i in 0..<4 { pooled[i] = 0 }
        weight = 0
        period = 0
        compound = false
        on = 0
        off = 0
    }

    /// Adds one reading's per-beat evidence (beat, half, first third, second third; see
    /// [energies]) for a beat period of `periodFrames` onset frames. Returns the decision.
    @discardableResult
    public func update(_ energies: [Double], periodFrames: Double) -> Bool {
        // A new beat level means new thirds: start over, and show simple until they are clear.
        if period > 0 && abs(periodFrames / period - 1.0) > BeatFeel.PERIOD_TOLERANCE {
            reset()
            period = periodFrames
        }
        period = period == 0 ? periodFrames : 0.8 * period + 0.2 * periodFrames
        for i in 0..<4 { pooled[i] = BeatFeel.POOL * pooled[i] + energies[i] }
        weight = BeatFeel.POOL * weight + 1.0
        let beat = pooled[0], half = pooled[1], first = pooled[2], second = pooled[3]
        let third = 0.5 * (first + second)
        let both = min(first, second) > BeatFeel.BALANCE * max(first, second)
        let strong = weight >= BeatFeel.MIN_WEIGHT && both && third > BeatFeel.ON_RATIO * half && third > BeatFeel.BEAT_SHARE * beat
        let keep = both && third > BeatFeel.OFF_RATIO * half
        on = strong ? on + 1 : 0
        off = keep ? 0 : off + 1
        if !compound && on >= BeatFeel.HOLD { compound = true }
        if compound && off >= BeatFeel.HOLD { compound = false }
        return compound
    }

    /// One reading's evidence from the raw onset signal (one value per hop, oldest first, the
    /// first at hop `firstHop`): the history is smoothed and detrended as TempoTracker does, then
    /// MeterDetector's four sums over every whole beat back from the newest are divided by the
    /// number of beats. Returns nil when the history is too short, and the period in hops.
    public static func energies(onsets: [Float], firstHop: Int, framesPerSecond fps: Double,
                                lastBeatSeconds: Double, periodSeconds: Double) -> ([Double]?, Double) {
        let n = onsets.count
        let pf = periodSeconds * fps
        let newest = lastBeatSeconds * fps - 1 - Double(firstHop)
        if pf < 4.0 || Double(n) < 3 * fps || newest < 0 { return (nil, pf) }
        // TempoTracker.estimate(): 1-2-1 smoothing, then minus a moving average of +-0.75 s.
        var lin = [Double](repeating: 0, count: n)
        for i in 0..<n {
            lin[i] = 0.25 * Double(onsets[max(0, i - 1)]) + 0.5 * Double(onsets[i]) + 0.25 * Double(onsets[min(n - 1, i + 1)])
        }
        let trend = max(1, Int((0.75 * fps + 0.5).rounded(.down)))
        var cumulative = [Double](repeating: 0, count: n + 1)
        for i in 0..<n { cumulative[i + 1] = cumulative[i] + lin[i] }
        var x = [Double](repeating: 0, count: n)
        for i in 0..<n {
            let lo = max(0, i - trend), hi = min(n - 1, i + trend)
            x[i] = lin[i] - (cumulative[hi + 1] - cumulative[lo]) / Double(hi - lo + 1)
        }
        func windowMax(_ center: Double, _ half: Double) -> Double {
            let lo = max(0, Int((center - half + 0.5).rounded(.down)))
            let hi = min(n - 1, Int((center + half + 0.5).rounded(.down)))
            if lo > hi { return 0 }
            var m = -Double.infinity
            for i in lo...hi where x[i] > m { m = x[i] }
            return max(0, m)
        }
        let sub = pf / 20.0
        var e = [Double](repeating: 0, count: 4)
        var t = newest
        while t - pf >= 0 {
            let start = t - pf
            e[0] += windowMax(start, pf / 8.0)
            e[1] += windowMax(start + pf / 2.0, sub)
            e[2] += windowMax(start + pf / 3.0, sub)
            e[3] += windowMax(start + 2.0 * pf / 3.0, sub)
            t -= pf
        }
        let beats = max(1.0, (newest / pf).rounded(.down))
        return (e.map { $0 / beats }, pf)
    }
}

/// Follows the beat with Beat This! (through [BeatNetwork]) as the audio comes in: the audio is
/// resampled to 22,050 Hz into the log-mel front end, and from 4 s on, once a second, the newest
/// 8 s of frames go through the network and the reader. The onset signal the analyser computes
/// anyway is kept alongside, for the simple-or-compound decision.
///
/// With a [worker] (a serial queue), the network runs there instead of on the caller's (audio)
/// thread. Every time in a reading is in seconds of input audio, so a late answer still puts the
/// beats where they were played.
public final class BeatTracker {
    /// For the Details fold.
    public let name: String
    public let inputRate: Int
    public let hopSize: Int
    private let network: BeatNetwork
    private let worker: DispatchQueue?
    private let resampler: StreamResampler
    private let frontEnd = BeatThisFrontEnd()
    private var resampled = 0
    private var nextRun = BeatThis.FIRST_READING_SECONDS * BeatThis.SAMPLE_RATE

    // The raw onset signal, one value per hop, in a ring (8 s and some).
    private let onsetFps: Double
    private let onsetHistory: Int
    private var onsets: [Float]
    private var onsetCount = 0

    /// A window waiting for the network.
    private final class Job {
        var input = [Float](repeating: 0, count: BeatThis.INPUT_FRAMES * BeatThis.MELS)
        var onsets: [Float] = []
        var firstHop = 0
        var moment = 0.0
        var firstFrame = 0
        var generation = 0
    }
    /// The free jobs (at most two windows in flight), guarded by [poolLock].
    private var free: [Job] = [Job(), Job()]
    private let poolLock = NSLock()

    // Only touched by whoever runs the jobs (the worker, or the caller without one).
    private let reader = BeatReader()
    private let feel = BeatFeel()
    private var readerGeneration = 0
    private var logitsBeat = [Float](repeating: 0, count: BeatThis.FRAMES)
    private var logitsDownbeat = [Float](repeating: 0, count: BeatThis.FRAMES)

    // [lock] guards these.
    private let lock = NSLock()
    private var generation = 0
    private var latestValue: BeatReading? = nil
    private var failedValue = false
    private var millisValue = 0.0
    private var skippedValue = 0
    private var readingsValue = 0

    public init(_ network: BeatNetwork, _ inputRate: Int, hopSize: Int = 512, name: String = "Beat This!", worker: DispatchQueue? = nil) {
        self.network = network
        self.inputRate = inputRate
        self.hopSize = hopSize
        self.name = name
        self.worker = worker
        resampler = StreamResampler(inputRate, BeatThis.SAMPLE_RATE, cutoffHz: BeatThis.RESAMPLER_CUTOFF_HZ)
        onsetFps = Double(inputRate) / Double(hopSize)
        onsetHistory = Int((8.0 * onsetFps + 0.5).rounded(.down))
        onsets = [Float](repeating: 0, count: onsetHistory + 64)
    }

    /// The newest reading, or nil before the first.
    public var latest: BeatReading? { lock.lock(); defer { lock.unlock() }; return latestValue }
    /// True once the network has failed; the analyser then goes back to its own tracker.
    public var failed: Bool { lock.lock(); defer { lock.unlock() }; return failedValue }
    /// Running average cost, in milliseconds per second of audio, for the on-screen diagnostics.
    public var millisPerSecond: Double { lock.lock(); defer { lock.unlock() }; return millisValue }
    /// Windows the worker could not keep up with and skipped.
    public var skipped: Int { lock.lock(); defer { lock.unlock() }; return skippedValue }
    /// Readings made since the start (or the last reset).
    public var readings: Int { lock.lock(); defer { lock.unlock() }; return readingsValue }

    public func reset() {
        resampler.reset()
        frontEnd.reset()
        resampled = 0
        nextRun = BeatThis.FIRST_READING_SECONDS * BeatThis.SAMPLE_RATE
        onsetCount = 0
        lock.lock()
        generation += 1
        latestValue = nil
        readingsValue = 0
        lock.unlock()
    }

    /// The analyser's onset strength for the hop just heard.
    public func pushOnset(_ v: Float) {
        onsets[onsetCount % onsets.count] = v
        onsetCount += 1
    }

    public func push(_ samples: [Float], _ offset: Int, _ count: Int) {
        // A failed network is not asked again; the analyser has gone back to its own tracker.
        if failed { return }
        resampler.push(samples, offset, count) { v in
            frontEnd.push(v)
            resampled += 1
            if resampled == nextRun { schedule(); nextRun += BeatThis.SAMPLE_RATE }
        }
    }

    private func schedule() {
        poolLock.lock()
        let job = free.isEmpty ? nil : free.removeFirst()
        poolLock.unlock()
        guard let job else { lock.lock(); skippedValue += 1; lock.unlock(); return }
        let moment = Double(resampled) / Double(BeatThis.SAMPLE_RATE)
        // The newest frame whose 1024 samples are all in.
        let last = floorDiv(resampled - BeatThis.N_FFT / 2, BeatThis.HOP)
        frontEnd.window(endingAt: last, into: &job.input)
        job.moment = moment
        job.firstFrame = last - BeatThis.FRAMES + 1
        // The onset hops complete by this moment, the newest 8 s of them.
        let hopEnd = min(onsetCount - 1, Int((moment * onsetFps).rounded(.down)) - 1)
        let hopStart = max(0, hopEnd - onsetHistory + 1, onsetCount - onsets.count)
        job.firstHop = hopStart
        job.onsets.removeAll(keepingCapacity: true)
        if hopEnd >= hopStart { for h in hopStart...hopEnd { job.onsets.append(onsets[h % onsets.count]) } }
        lock.lock()
        job.generation = generation
        lock.unlock()
        if let worker { worker.async { self.compute(job) } } else { compute(job) }
    }

    private func compute(_ job: Job) {
        let started = DispatchTime.now().uptimeNanoseconds
        // Silence goes no further: the network finds no beat in it.
        var loudest: Float = 0
        for v in job.input where v > loudest { loudest = v }
        var ok = true
        if loudest < 1e-4 {
            for i in 0..<BeatThis.FRAMES { logitsBeat[i] = -10; logitsDownbeat[i] = -10 }
        } else if let out = network.logits(job.input), out.beat.count == BeatThis.INPUT_FRAMES, out.downbeat.count == BeatThis.INPUT_FRAMES {
            for i in 0..<BeatThis.FRAMES {
                logitsBeat[i] = out.beat[BeatThis.BORDER + i]
                logitsDownbeat[i] = out.downbeat[BeatThis.BORDER + i]
            }
        } else {
            ok = false
        }
        if ok {
            if job.generation != readerGeneration { reader.reset(); feel.reset(); readerGeneration = job.generation }
            var r = reader.read(now: job.moment, firstFrame: job.firstFrame, beat: logitsBeat, downbeat: logitsDownbeat)
            if r.bpm != nil, let last = r.lastBeatSeconds, let period = r.periodSeconds {
                let (e, pf) = BeatFeel.energies(onsets: job.onsets, firstHop: job.firstHop, framesPerSecond: onsetFps,
                                                lastBeatSeconds: last, periodSeconds: period)
                if let e { feel.update(e, periodFrames: pf) }
            }
            r.compound = feel.compound
            lock.lock()
            if job.generation == generation { latestValue = r; readingsValue += 1 }
            lock.unlock()
        }
        poolLock.lock()
        free.append(job)
        poolLock.unlock()
        let ms = Double(DispatchTime.now().uptimeNanoseconds - started) / 1e6
        lock.lock()
        if !ok { failedValue = true }
        // One run per second of audio, so the cost per run is the cost per second.
        if loudest >= 1e-4 && ok { millisValue = millisValue == 0 ? ms : 0.8 * millisValue + 0.2 * ms }
        lock.unlock()
    }
}
