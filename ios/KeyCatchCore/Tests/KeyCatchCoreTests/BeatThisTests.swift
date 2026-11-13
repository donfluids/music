import Foundation
import XCTest
@testable import KeyCatchCore

/// Stands in for Beat This!: it knows when the beats and downbeats are, works out which frames
/// the window it is given covers (one call a second from 4 s, as BeatTracker runs it), and
/// answers a clear logit on the frame nearest each beat. What happens around the network
/// (front end, windows, postprocessing, reading, dating, the analyser's fields) is tested for real.
private final class ScoreBeatNetwork: BeatNetwork {
    let beats: [Double]
    let downbeats: [Double]
    private(set) var calls = 0

    init(bpm: Double, beatsPerBar: Int, seconds: Double, start: Double = 0) {
        let period = 60.0 / bpm
        var b: [Double] = []
        var d: [Double] = []
        var k = 0
        while start + Double(k) * period < seconds {
            b.append(start + Double(k) * period)
            if k % beatsPerBar == 0 { d.append(start + Double(k) * period) }
            k += 1
        }
        beats = b
        downbeats = d
    }

    func logits(_ spect: [Float]) -> (beat: [Float], downbeat: [Float])? {
        XCTAssertEqual(spect.count, BeatThis.INPUT_FRAMES * BeatThis.MELS)
        let moment = Double(BeatThis.FIRST_READING_SECONDS + calls)
        calls += 1
        let last = Int(((moment * 22050 - 512) / 441).rounded(.down))
        let first = last - BeatThis.FRAMES + 1
        func row(_ times: [Double]) -> [Float] {
            (0..<BeatThis.INPUT_FRAMES).map { f in
                let g = Double(first + f - BeatThis.BORDER)
                return times.contains { abs($0 * 50 - g) < 0.5 } ? 4 : -4
            }
        }
        return (row(beats), row(downbeats))
    }
}

private final class FailingBeatNetwork: BeatNetwork {
    private(set) var calls = 0
    func logits(_ spect: [Float]) -> (beat: [Float], downbeat: [Float])? { calls += 1; return nil }
}

final class BeatThisTests: XCTestCase {

    // ---- Front end ----

    func testFrontEndMatchesTheNumPyReference() throws {
        let ref = try BeatThisFixtures.json("beat-this-frontend")
        let want = try XCTUnwrap(ref["frames"] as? [Any]).map { BeatThisFixtures.nums($0) }
        let signal = BeatThisFixtures.signal(Int(BeatThisFixtures.num(ref["samples"])!))
        let fe = BeatThisFrontEnd()
        // Odd chunks: frames are made as the samples arrive, whatever the chunk size.
        var i = 0
        while i < signal.count { let n = min(333, signal.count - i); for k in i..<(i + n) { fe.push(signal[k]) }; i += n }
        XCTAssertEqual(fe.framesMade, want.count)
        var worst = 0.0
        for t in 0..<want.count {
            let got = fe.frame(t)
            for b in 0..<BeatThis.MELS { worst = max(worst, abs(Double(got[b]) - want[t][b])) }
        }
        print("Beat This! front end: largest difference from NumPy \(worst)")
        // The official float32 front end is 1.7e-4 from the float64 one.
        XCTAssertLessThan(worst, 1e-3)
    }

    func testFilterbankLooksLikeTorchaudio() {
        let fb = BeatThisFrontEnd.filterbank
        XCTAssertEqual(fb.count, 128)
        // Peak 1 at most (no area normalisation), lowest band from about 30 Hz, highest to 11 kHz.
        for (_, w) in fb { XCTAssertFalse(w.isEmpty); XCTAssertLessThanOrEqual(w.max()!, 1) }
        XCTAssertEqual(fb[0].0, 2)
        let top = fb[127]
        XCTAssertEqual(top.0 + top.1.count - 1, 510)
    }

    func testWindowHasBordersAndSilenceBeforeTheStart() {
        let fe = BeatThisFrontEnd()
        for i in 0..<(22050 * 2) { fe.push(Float(sin(Double(i) * 0.3))) }
        var input = [Float](repeating: 1, count: BeatThis.INPUT_FRAMES * BeatThis.MELS)
        let last = fe.framesMade - 1
        fe.window(endingAt: last, into: &input)
        func row(_ r: Int) -> ArraySlice<Float> { input[(r * BeatThis.MELS)..<((r + 1) * BeatThis.MELS)] }
        // 6 border rows, then 400 - frames silent rows, the frames, and 6 border rows.
        for r in 0..<(BeatThis.BORDER + BeatThis.FRAMES - fe.framesMade) { XCTAssertTrue(row(r).allSatisfy { $0 == 0 }, "row \(r)") }
        XCTAssertEqual(Array(row(BeatThis.BORDER + BeatThis.FRAMES - 1)), fe.frame(last))
        for r in (BeatThis.BORDER + BeatThis.FRAMES)..<BeatThis.INPUT_FRAMES { XCTAssertTrue(row(r).allSatisfy { $0 == 0 }) }
    }

    /// The synthetic band through the resampler and front end, against the Python pipeline's
    /// input to the network at 10 s (soxr and the float64 log-mel; stored as float16).
    func testLiveFrontEndMatchesPythonOnTheBand() throws {
        let ref = try BeatThisFixtures.json("beat-this-coreml")
        let band = BeatThisFixtures.band()
        var squares = 0.0
        for v in band { squares += Double(v) * Double(v) }
        XCTAssertEqual(squares, BeatThisFixtures.num(ref["bandSumSquares"])!, accuracy: 1e-6 * squares, "the synthetic band must match Python's")
        let size = BeatThis.INPUT_FRAMES * BeatThis.MELS
        let want = Array(try BeatThisFixtures.halves("beat-this-coreml-input")[size..<(2 * size)])
        let resampler = StreamResampler(44100, BeatThis.SAMPLE_RATE, cutoffHz: BeatThis.RESAMPLER_CUTOFF_HZ)
        let fe = BeatThisFrontEnd()
        var made = 0
        let moment = 10 * BeatThis.SAMPLE_RATE
        resampler.push(band, 0, band.count) { v in if made < moment { fe.push(v); made += 1 } }
        XCTAssertEqual(made, moment)
        var got = [Float](repeating: 0, count: size)
        fe.window(endingAt: floorDiv(moment - 512, BeatThis.HOP), into: &got)
        var worstLow: Float = 0, worst: Float = 0
        for r in 0..<BeatThis.INPUT_FRAMES {
            for b in 0..<BeatThis.MELS {
                let d = abs(got[r * BeatThis.MELS + b] - want[r * BeatThis.MELS + b])
                worst = max(worst, d)
                if b < 120 { worstLow = max(worstLow, d) }
            }
        }
        print("Beat This! live front end vs Python on the band: largest difference \(worst), below 9.4 kHz \(worstLow)")
        // Only the resampler differs (and float16 storage, 0.004 at these values): the top bands
        // see soxr's steeper filter, the rest hardly anything.
        XCTAssertLessThan(worstLow, 0.05)
        XCTAssertLessThan(worst, 1.5)
    }

    // ---- Postprocessing and reading ----

    func testPeaksFollowTheOfficialRules() {
        var x = [Float](repeating: -1, count: 40)
        x[5] = 2                    // a peak
        x[7] = 1                    // within 3 frames of a bigger one: not a peak
        x[15] = 0.5; x[16] = 0.5    // a plateau: both frames are maxima, merged to 15.5
        x[25] = 1; x[26] = 1; x[27] = 1 // three equal frames: 25 and 26 merge to 25.5; 27 is 1.5 from that mean
        x[35] = -0.5                // a maximum but not above 0
        XCTAssertEqual(BeatPostprocessing.peakFrames(x), [5, 15.5, 25.5, 27])
        // Downbeats move to the nearest beat (the first of two equally near) and are not repeated.
        var b = [Float](repeating: -5, count: 200), d = [Float](repeating: -5, count: 200)
        for f in [10, 35, 60, 85] { b[f] = 3 }
        d[12] = 2; d[20] = 2; d[47] = 2 // 0.24 s and 0.4 s go to 0.2 s, 0.94 s to 0.7 s
        let (beats, downs) = BeatPostprocessing.beatsAndDownbeats(beat: b, downbeat: d)
        XCTAssertEqual(beats, [0.2, 0.7, 1.2, 1.7])
        XCTAssertEqual(downs, [0.2, 0.7])
    }

    func testReadingsMatchThePythonReference() throws {
        let ref = try BeatThisFixtures.json("beat-this-readings")
        let logits = try BeatThisFixtures.halves("beat-this-readings-logits")
        let frames = BeatThis.FRAMES
        var at = 0
        for clip in try XCTUnwrap(ref["clips"] as? [[String: Any]]) {
            let reader = BeatReader()
            let readings = (clip["readings"] as! [[String: Any]]).map(BeatThisFixtures.Reading.init)
            for want in readings {
                let beat = Array(logits[at..<(at + frames)])
                let downbeat = Array(logits[(at + frames)..<(at + 2 * frames)])
                at += 2 * frames
                let got = reader.read(now: want.t, firstFrame: want.firstFrame, beat: beat, downbeat: downbeat)
                let what = "\(clip["id"]!) at \(want.t) s"
                XCTAssertEqual(got.beats.count, want.beats.count, what)
                for (a, b) in zip(got.beats, want.beats) { XCTAssertEqual(a, b, accuracy: 1e-9, what) }
                XCTAssertEqual(got.downbeats.count, want.downbeats.count, what)
                for (a, b) in zip(got.downbeats, want.downbeats) { XCTAssertEqual(a, b, accuracy: 1e-9, what) }
                XCTAssertEqual(got.bpm == nil, want.bpm == nil, what)
                if let a = got.bpm, let b = want.bpm { XCTAssertEqual(a, b, accuracy: 1e-9, what) }
                if let a = got.periodSeconds, let b = want.period { XCTAssertEqual(a, b, accuracy: 1e-12, what) }
                if let a = got.lastBeatSeconds, let b = want.last { XCTAssertEqual(a, b, accuracy: 1e-12, what) }
                XCTAssertEqual(got.beatsPerBar, want.beatsPerBar, what)
                XCTAssertEqual(got.lastBeatIndexInBar, want.index, what)
                XCTAssertEqual(got.locked, want.locked, what)
                XCTAssertEqual(got.strength, want.strength, accuracy: 1e-9, what)
                for bb in BeatReader.BARS { XCTAssertEqual(got.barScores[bb]!, want.scores[bb]!, accuracy: 1e-9, what) }
            }
        }
        XCTAssertEqual(at, logits.count)
    }

    /// Logits for a window starting at frame `first`: beats every `period` frames, a downbeat
    /// every `bar` beats with the given phase, and `probability` on the downbeats.
    private func comb(first: Int, period: Int, bar: Int, phase: Int, downbeatLogit: Float = 3) -> ([Float], [Float]) {
        var b = [Float](repeating: -6, count: BeatThis.FRAMES), d = [Float](repeating: -6, count: BeatThis.FRAMES)
        for f in 0..<BeatThis.FRAMES where floorMod(first + f, period) == 0 {
            b[f] = 4
            if floorMod((first + f) / period - phase, bar) == 0 { d[f] = downbeatLogit }
        }
        return (b, d)
    }

    func testCombCountsTheBarAndFollowsAChange() {
        let reader = BeatReader()
        var r = BeatReading()
        // 120 BPM in 3/4: beats every 25 frames, a downbeat every third beat.
        for m in 4...8 {
            let first = (m * 22050 - 512) / 441 - 399
            let (b, d) = comb(first: first, period: 25, bar: 3, phase: 0)
            r = reader.read(now: Double(m), firstFrame: first, beat: b, downbeat: d)
        }
        XCTAssertEqual(r.bpm ?? 0, 120, accuracy: 1e-9)
        XCTAssertEqual(r.beatsPerBar, 3)
        XCTAssertEqual(r.meterLabel, "3/4")
        XCTAssertTrue(r.locked)
        // The newest beat's place in the bar: beat k of the song is at frame 25 k, downbeats at k % 3 == 0.
        let k = Int((r.lastBeatSeconds! * 2).rounded())
        XCTAssertEqual(r.lastBeatIndexInBar, k % 3)
        // The band goes to 4/4: the pooled scores halve each second, so it follows within a few readings.
        var switched = 0
        for m in 9...14 {
            let first = (m * 22050 - 512) / 441 - 399
            let (b, d) = comb(first: first, period: 25, bar: 4, phase: 0)
            r = reader.read(now: Double(m), firstFrame: first, beat: b, downbeat: d)
            if r.beatsPerBar == 4 && switched == 0 { switched = m }
        }
        XCTAssertEqual(r.beatsPerBar, 4)
        XCTAssertGreaterThanOrEqual(switched, 9)
        XCTAssertLessThanOrEqual(switched, 11)
    }

    func testNoBeatsMeansNoTempo() {
        let reader = BeatReader()
        let quiet = [Float](repeating: -8, count: BeatThis.FRAMES)
        let r = reader.read(now: 4, firstFrame: -199, beat: quiet, downbeat: quiet)
        XCTAssertNil(r.bpm)
        XCTAssertNil(r.beatsPerBar)
        XCTAssertNil(r.meterLabel)
        XCTAssertFalse(r.locked)
    }

    // ---- Simple or compound ----

    func testFeelMatchesThePythonReference() throws {
        let ref = try BeatThisFixtures.json("beat-this-feel")
        let onsets = BeatThisFixtures.nums(ref["onsets"]).map { Float($0) }
        let fps = BeatThisFixtures.num(ref["rate"])! / BeatThisFixtures.num(ref["hop"])!
        let capacity = Int((8.0 * fps + 0.5).rounded(.down))
        let feel = BeatFeel()
        var shown = 0
        for row in try XCTUnwrap(ref["readings"] as? [[String: Any]]) {
            let t = BeatThisFixtures.num(row["t"])!
            if BeatThisFixtures.num(row["bpm"]) != nil, let last = BeatThisFixtures.num(row["last"]), let period = BeatThisFixtures.num(row["period"]) {
                let hEnd = Int((t * fps).rounded(.down)) - 1
                let h0 = max(0, hEnd - capacity + 1)
                let (e, pf) = BeatFeel.energies(onsets: Array(onsets[h0...hEnd]), firstHop: h0, framesPerSecond: fps, lastBeatSeconds: last, periodSeconds: period)
                let want = BeatThisFixtures.nums(row["energies"])
                XCTAssertEqual(e == nil, want.isEmpty, "at \(t) s")
                if let e {
                    for i in 0..<4 { XCTAssertEqual(e[i], want[i], accuracy: 1e-9 * max(1, abs(want[i])), "at \(t) s") }
                    XCTAssertEqual(pf, BeatThisFixtures.num(row["periodFrames"])!, accuracy: 1e-9)
                    feel.update(e, periodFrames: pf)
                }
            }
            XCTAssertEqual(feel.compound, row["compound"] as? Bool, "at \(t) s")
            if feel.compound { shown += 1 }
        }
        // This 12/8 song is one of the few where the evidence gets strong enough.
        XCTAssertGreaterThan(shown, 0)
    }

    /// 8 s of onset signal at 44.1 kHz / 512: a strong onset on each beat (0.6 s apart) and the
    /// given ones between, at fractions of the beat.
    private func onsets(between: [(Double, Float)], period: Double = 0.6) -> ([Float], Double) {
        let fps = 44100.0 / 512.0
        let n = Int((8.0 * fps + 0.5).rounded(.down))
        var x = [Float](repeating: 0.05, count: n)
        func hop(_ at: Double) -> Int { Int((at * fps).rounded()) - 1 }
        func put(_ at: Double, _ v: Float) { let h = hop(at); if h >= 0 && h < n { x[h] = max(x[h], v) } }
        var t = 0.1
        var last = 0.0
        while hop(t) < n {
            put(t, 1)
            last = t
            for (f, v) in between { put(t + f * period, v) }
            t += period
        }
        return (x, last)
    }

    private func feelAfter(_ readings: Int, _ x: [Float], _ last: Double, period: Double = 0.6) -> [Bool] {
        let feel = BeatFeel()
        return (0..<readings).map { _ in
            let (e, pf) = BeatFeel.energies(onsets: x, firstHop: 0, framesPerSecond: 44100.0 / 512.0, lastBeatSeconds: last, periodSeconds: period)
            return feel.update(e!, periodFrames: pf)
        }
    }

    func testFeelHearsTripletsSlowly() {
        let (x, last) = onsets(between: [(1.0 / 3, 0.6), (2.0 / 3, 0.6)])
        let flags = feelAfter(30, x, last)
        // Pooled evidence first (about 14 readings), then 5 strong readings in a row.
        XCTAssertFalse(flags[0..<17].contains(true), "\(flags)")
        XCTAssertTrue(flags[18...].allSatisfy { $0 }, "\(flags)")
    }

    func testFeelStaysSimpleForStraightOrSwungEighths() {
        let (straight, l1) = onsets(between: [(0.5, 0.6)])
        XCTAssertFalse(feelAfter(40, straight, l1).contains(true))
        // A swung eighth sits at two thirds alone: that is still simple time.
        let (swung, l2) = onsets(between: [(2.0 / 3, 0.6)])
        XCTAssertFalse(feelAfter(40, swung, l2).contains(true))
        // Triplets with a busier halfway point: not strong enough.
        let (mixed, l3) = onsets(between: [(1.0 / 3, 0.5), (0.5, 0.4), (2.0 / 3, 0.5)])
        XCTAssertFalse(feelAfter(40, mixed, l3).contains(true))
    }

    func testFeelStartsOverWhenTheBeatChangesLevel() {
        let (x, last) = onsets(between: [(1.0 / 3, 0.6), (2.0 / 3, 0.6)])
        let feel = BeatFeel()
        for _ in 0..<30 {
            let (e, pf) = BeatFeel.energies(onsets: x, firstHop: 0, framesPerSecond: 44100.0 / 512.0, lastBeatSeconds: last, periodSeconds: 0.6)
            feel.update(e!, periodFrames: pf)
        }
        XCTAssertTrue(feel.compound)
        // The beat is now read at the eighth: a third of the period. The evidence starts over.
        let (e, pf) = BeatFeel.energies(onsets: x, firstHop: 0, framesPerSecond: 44100.0 / 512.0, lastBeatSeconds: last, periodSeconds: 0.2)
        XCTAssertFalse(feel.update(e!, periodFrames: pf))
    }

    // ---- In the analyser ----

    private func clicks(_ bpm: Double, _ seconds: Double, bar: Int) -> [Float] {
        Synth.clickTrack(bpm, seconds, accents: [1] + [Float](repeating: 0.5, count: bar - 1))
    }

    func testBandFollowsTheBeatWithBeatThis() {
        let net = ScoreBeatNetwork(bpm: 120, beatsPerBar: 3, seconds: 20)
        var made: (Int, Int)? = nil
        let analyzer = MusicAnalyzer(sampleRate: Synth.sampleRate, preset: .band, beats: { rate, hop in
            made = (rate, hop)
            return BeatTracker(net, rate, hopSize: hop, name: BeatThisModel.small.title)
        })
        XCTAssertEqual(made?.0, Synth.sampleRate)
        XCTAssertEqual(made?.1, 512)
        var firstBpmAt: Float? = nil
        let result = Synth.analyze(clicks(120, 12.5, bar: 3), analyzer) { r in
            if r.bpm != nil && firstBpmAt == nil { firstBpmAt = r.secondsHeard }
        }
        XCTAssertEqual(net.calls, 9) // 4 s .. 12 s
        // The first reading comes at 4 s.
        XCTAssertEqual(firstBpmAt ?? 0, 4, accuracy: 0.05)
        XCTAssertEqual(result.bpm ?? 0, 120, accuracy: 0.01)
        XCTAssertTrue(result.tempoLocked)
        XCTAssertEqual(result.beatsPerBar, 3)
        XCTAssertEqual(result.meterLabel, "3/4")
        XCTAssertFalse(result.meterCompound)
        XCTAssertGreaterThan(result.meterConfidence, 0)
        XCTAssertEqual(result.beatPeriodMillis, 500, accuracy: 0.01)
        XCTAssertEqual(result.beatModel, "Beat This! small")
        // The published beat is the latest one at or before now, on the clock of the audio.
        let lastBeat = Double(result.lastBeatAtNanos) / 1e9
        let k = (lastBeat / 0.5).rounded()
        XCTAssertEqual(lastBeat, k * 0.5, accuracy: 0.011)
        XCTAssertLessThanOrEqual(lastBeat, Double(result.secondsHeard) + 0.01)
        XCTAssertGreaterThan(lastBeat, Double(result.secondsHeard) - 0.51)
        XCTAssertEqual(result.lastBeatIndexInBar, Int(k) % 3)
    }

    func testGuitarUsesItsOwnModelAndVoicesNone() {
        XCTAssertEqual(AnalysisPreset.band.beatModel, .small)
        XCTAssertEqual(AnalysisPreset.guitar.beatModel, .full)
        XCTAssertNil(AnalysisPreset.voices.beatModel)
        var asked = false
        let voices = MusicAnalyzer(sampleRate: Synth.sampleRate, preset: .voices, beats: { rate, hop in
            asked = true
            return BeatTracker(ScoreBeatNetwork(bpm: 120, beatsPerBar: 4, seconds: 10), rate, hopSize: hop)
        })
        let r = Synth.analyze(clicks(120, 6, bar: 4), voices)
        XCTAssertFalse(asked, "Voices taps its tempo and never builds the tracker")
        XCTAssertNil(r.beatModel)
    }

    func testFallsBackToTheStandardTrackerWhenTheNetworkFails() {
        let audio = Synth.clickTrack(110.0, 16.0, accents: [1, 0.4, 0.7, 0.4])
        let standard = Synth.analyze(audio, MusicAnalyzer(sampleRate: Synth.sampleRate, preset: .band))
        let net = FailingBeatNetwork()
        let failing = MusicAnalyzer(sampleRate: Synth.sampleRate, preset: .band, beats: { BeatTracker(net, $0, hopSize: $1) })
        var before: AnalysisResult? = nil
        let result = Synth.analyze(audio, failing) { r in if r.secondsHeard < 3.9 { before = r } }
        XCTAssertEqual(net.calls, 1, "a failed network is not asked again")
        XCTAssertNotNil(before?.beatModel, "named while it is expected to work")
        XCTAssertNil(result.beatModel)
        XCTAssertEqual(result.bpm, standard.bpm)
        XCTAssertEqual(result.meterLabel, standard.meterLabel)
        XCTAssertEqual(result.lastBeatAtNanos, standard.lastBeatAtNanos)
        XCTAssertEqual(result.beatsPerBar, standard.beatsPerBar)
        XCTAssertEqual(result.lastBeatIndexInBar, standard.lastBeatIndexInBar)
    }

    func testTheStandardTrackerIsUnchangedWithoutBeatThis() {
        let audio = Synth.clickTrack(130.0, 12.0, accents: [1, 0.4, 0.4])
        let r = Synth.analyze(audio, MusicAnalyzer(sampleRate: Synth.sampleRate, preset: .guitar))
        XCTAssertNil(r.beatModel)
        XCTAssertEqual(r.beatMillisPerSecond, 0)
        XCTAssertEqual(Double(r.bpm ?? 0), 130, accuracy: 4)
    }

    func testChordsKeepTheirOwnBeatGrid() {
        // The chord grid stays with the standard tracker, so chords come out the same either way.
        var audio = Synth.progression([[60, 64, 67], [57, 60, 64], [53, 57, 60], [55, 59, 62]], secondsEach: 2.0)
        audio = Synth.mix(audio, Synth.clickTrack(120, 8, accents: [1, 0.5, 0.5, 0.5]))
        let plain = Synth.analyze(audio, MusicAnalyzer(sampleRate: Synth.sampleRate, preset: .band))
        let bt = Synth.analyze(audio, MusicAnalyzer(sampleRate: Synth.sampleRate, preset: .band, beats: {
            BeatTracker(ScoreBeatNetwork(bpm: 120, beatsPerBar: 4, seconds: 10), $0, hopSize: $1)
        }))
        XCTAssertEqual(plain.chordSegments, bt.chordSegments)
        XCTAssertEqual(plain.displayKey, bt.displayKey)
        XCTAssertEqual(bt.beatsPerBar, 4)
    }

    func testOnAWorkerTheBeatsStayWhereTheyWerePlayed() {
        let worker = DispatchQueue(label: "beat-test")
        let net = ScoreBeatNetwork(bpm: 100, beatsPerBar: 4, seconds: 12)
        let tracker = BeatTracker(net, 48000, worker: worker)
        let audio = (0..<(48000 * 9 + 4800)).map { i in Float(0.1 * sin(Double(i) * 0.05)) }
        var i = 0
        while i < audio.count {
            let n = min(512, audio.count - i)
            tracker.pushOnset(0)
            tracker.push(audio, i, n)
            i += n
        }
        worker.sync {}
        XCTAssertEqual(tracker.readings, 6) // 4 s .. 9 s
        let r = tracker.latest!
        XCTAssertEqual(r.moment, 9)
        XCTAssertEqual(r.bpm ?? 0, 100, accuracy: 0.01)
        XCTAssertEqual(r.lastBeatSeconds! / 0.6, (r.lastBeatSeconds! / 0.6).rounded(), accuracy: 0.02)
        XCTAssertEqual(r.beatsPerBar, 4)
        XCTAssertEqual(tracker.skipped, 0)
        XCTAssertFalse(tracker.failed)
        tracker.reset()
        XCTAssertNil(tracker.latest)
    }

    func testSilenceSkipsTheNetwork() {
        let net = ScoreBeatNetwork(bpm: 100, beatsPerBar: 4, seconds: 12)
        let tracker = BeatTracker(net, 44100)
        tracker.push([Float](repeating: 0, count: 44100 * 6), 0, 44100 * 6)
        XCTAssertEqual(net.calls, 0)
        XCTAssertEqual(tracker.readings, 2)
        XCTAssertNil(tracker.latest?.bpm)
    }
}
