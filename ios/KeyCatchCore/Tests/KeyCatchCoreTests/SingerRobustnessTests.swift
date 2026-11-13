import Foundation
import XCTest
@testable import KeyCatchCore

/// One C major hymn-like tune, sung the way real congregations sing it: off-pitch notes,
/// slow slides, scoops, drifting flat. The app may be unsure; it must never be sure and wrong.
final class SingerRobustnessTests: XCTestCase {
    private let rate = Synth.sampleRate
    private let cMajor = Key(0, .major)
    private let tune: [(Int, Int)] = [(55, 1), (60, 2), (64, 1), (60, 1), (64, 2), (62, 1), (60, 2), (57, 1), (55, 3),
        (55, 1), (60, 2), (64, 1), (60, 1), (64, 2), (62, 1), (67, 3), (64, 1), (67, 2), (64, 1), (60, 1),
        (64, 2), (62, 1), (60, 2), (57, 1), (55, 2), (55, 1), (60, 2), (64, 1), (60, 1), (64, 2), (62, 1), (60, 4)]
    private let beat = 0.45
    private let seeds: [Int64] = [1, 2, 3, 4, 5]

    /// `noteErrCents`: each note's own random error; `glideMs`: slide into each note; `driftCents`: flat or sharp by the end.
    private struct Singing: CustomStringConvertible {
        let noteErrCents: Double, glideMs: Double, driftCents: Double
        var vibratoCents: Double = 25.0
        var scoopCents: Double = 0.0
        init(_ noteErrCents: Double, _ glideMs: Double, _ driftCents: Double, vibratoCents: Double = 25.0, scoopCents: Double = 0.0) {
            self.noteErrCents = noteErrCents; self.glideMs = glideMs; self.driftCents = driftCents
            self.vibratoCents = vibratoCents; self.scoopCents = scoopCents
        }
        var description: String {
            "Singing(noteErrCents=\(noteErrCents), glideMs=\(glideMs), driftCents=\(driftCents), vibratoCents=\(vibratoCents), scoopCents=\(scoopCents))"
        }
    }

    private func sing(_ st: Singing, _ seed: Int64) -> [Float] {
        var rnd = JavaRandom(seed)
        let r = Double(rate)
        let total = Double(tune.reduce(0) { $0 + $1.1 }) * beat + 1.0
        var out = [Float](repeating: 0, count: Int(total * r))
        var t0 = 0.3; var phase = 0.0; var prevCents = Double.nan
        for (midi, beats) in tune {
            let len = Double(beats) * beat
            let err = rnd.nextGaussian() * st.noteErrCents
            let n = Int(len * r)
            for i in 0..<n {
                let t = Double(i) / r
                let at = t0 + t
                var target = Double(midi) * 100.0 + err + st.driftCents * (at / total)
                let g = st.glideMs / 1000.0
                if !prevCents.isNaN && t < g { target = prevCents + (target - prevCents) * (t / g) }
                if st.scoopCents > 0 && t < 0.15 { target -= st.scoopCents * (1 - t / 0.15) }
                let vib = t > 0.25 ? st.vibratoCents * sin(2 * Double.pi * 5.3 * t) : 0.0
                phase += 2 * Double.pi * 440.0 * pow(2.0, ((target + vib) / 100.0 - 69) / 12.0) / r
                let env = min(1.0, t / 0.03) * (beats >= 3 ? min(1.0, Double(n - i) / r / 0.15) : 1.0)
                let idx = Int(at * r)
                if idx < out.count {
                    let s: Double = sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase) + 0.15 * sin(4 * phase)
                    out[idx] += Float(0.18 * env * s)
                }
            }
            prevCents = Double(midi) * 100.0 + err + st.driftCents * ((t0 + len) / total)
            t0 += len
        }
        return out
    }

    private func hear(_ st: Singing) -> [AnalysisResult] {
        seeds.map { seed in
            let audio = sing(st, seed)
            let a = MusicAnalyzer(sampleRate: rate, preset: .voices)
            var i = 0
            while i < audio.count {
                let n = min(1024, audio.count - i)
                a.process(Array(audio[i..<(i + n)]), count: n, nowNanos: Int(Double(i + n) / Double(rate) * 1e9))
                i += n
            }
            return a.result
        }
    }

    private func describe(_ rs: [AnalysisResult]) -> String {
        rs.map { "\($0.displayKey?.label ?? "null") now=\($0.keyNow?.label ?? "null") pair=\($0.pairDecided) mode=\($0.modeDecided) spread=\(Int($0.singerSpreadCents)) tuning=\(Int($0.tuningCents))" }
            .joined(separator: ", ")
    }

    /// The one rule for every kind of singing: decided means right.
    private func neverSureAndWrong(_ rs: [AnalysisResult], file: StaticString = #filePath, line: UInt = #line) {
        for r in rs {
            if r.modeDecided { XCTAssertEqual(r.displayKey, cMajor, describe(rs), file: file, line: line) }
            if r.pairDecided { XCTAssertEqual(RelativeKeyResolver.pair(r.displayKey!).first, cMajor, describe(rs), file: file, line: line) }
        }
    }

    func testGoodSingersSlidesAndScoopsAreReadAndDecided() {
        for st in [Singing(0.0, 40.0, 0.0), Singing(15.0, 80.0, 0.0), Singing(10.0, 250.0, 0.0), Singing(15.0, 80.0, 0.0, scoopCents: 150.0)] {
            let rs = hear(st)
            neverSureAndWrong(rs)
            XCTAssertTrue(rs.allSatisfy { $0.displayKey == cMajor && $0.modeDecided }, "\(st): \(describe(rs))")
        }
    }

    func testACleanSingerMeasuresTightAndAScatteredOneLoose() {
        XCTAssertTrue(hear(Singing(0.0, 40.0, 0.0)).allSatisfy { $0.singerSpreadCents < 10 }, describe(hear(Singing(0.0, 40.0, 0.0))))
        let poor = hear(Singing(50.0, 150.0, 0.0))
        XCTAssertTrue(poor.allSatisfy { $0.singerSpreadCents > 25 }, describe(poor))
    }

    func testAnAverageSingerGetsTheRightPair() {
        // About 30 cents off per note. Always shown in the right pair; decided nearly always.
        let rs = hear(Singing(30.0, 120.0, 0.0))
        neverSureAndWrong(rs)
        XCTAssertTrue(rs.allSatisfy { RelativeKeyResolver.pair($0.displayKey!).first == cMajor }, describe(rs))
        XCTAssertTrue(rs.filter { $0.pairDecided }.count >= 4, describe(rs))
    }

    func testADriftingSingerIsFollowedAndTheTuningDescribesTheLatestNotes() {
        // Sung in C, 80 cents flat by the end: nearer B than C. The key may say C (the song) or
        // B (where the singers are now); never another pair. The tuning says how far the latest
        // notes sit from the semitones they are named on, so it must match where they really
        // are, 80 flat of C or 20 sharp of B, which are the same pitch.
        let b = cMajor.transposed(-1)
        let rs = hear(Singing(15.0, 80.0, -80.0))
        for r in rs {
            let pairMajor = RelativeKeyResolver.pair(r.displayKey!).first
            XCTAssertTrue(pairMajor == cMajor || pairMajor == b, describe(rs))
            if r.modeDecided { XCTAssertTrue(r.displayKey == cMajor || r.displayKey == b, describe(rs)) }
            let m = floorMod(roundToInt(r.tuningCents - 20), 100)
            let off = min(m, 100 - m)
            XCTAssertTrue(off <= 30, describe(rs))
        }
    }

    func testAPoorOrDriftingAndSlidingSingerIsABestGuessNeverAFalseCertainty() {
        for st in [Singing(50.0, 150.0, 0.0), Singing(45.0, 200.0, -70.0, vibratoCents: 40.0, scoopCents: 100.0)] {
            let rs = hear(st)
            neverSureAndWrong(rs)
            XCTAssertTrue(!rs.contains { $0.modeDecided && $0.displayKey != cMajor }, "\(st): \(describe(rs))")
        }
    }
}
