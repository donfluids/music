import Foundation
import XCTest
@testable import KeyCatchCore

/// Stands in for the CREPE network: answers a peak shaped like CREPE's training targets (a
/// Gaussian of 25 cents over the 20-cent bins) at [answerHz], whatever the frame, and keeps the
/// last frame it was given.
private final class FakeCrepe: CrepeNetwork {
    var answerHz = 440.0
    var peak: Float = 0.9
    private(set) var calls = 0
    private(set) var lastFrame: [Float] = []

    func activations(frame16k: [Float]) -> [Float] {
        calls += 1
        lastFrame = frame16k
        let cents = 1200 * log2(answerHz / 10.0)
        return (0..<CrepePitch.BINS).map { k in
            let d = CrepePitch.centsOf(k) - cents
            return Float(Double(peak) * exp(-d * d / (2 * 25.0 * 25.0)))
        }
    }
}

final class CrepePitchTests: XCTestCase {
    private let rate = 44100

    private func voice(_ hz: Double, _ seconds: Double = 0.3) -> [Float] {
        let n = Int(seconds * Double(rate))
        return (0..<n).map { i in
            let t = Double(i) / Double(rate)
            let a: Double = 0.3 * sin(2 * Double.pi * hz * t)
            let b: Double = 0.15 * sin(4 * Double.pi * hz * t)
            let c: Double = 0.08 * sin(6 * Double.pi * hz * t)
            return Float(a + b + c)
        }
    }

    private func cents(_ a: Double, _ b: Double) -> Double { 1200 * log2(a / b) }

    func testDecodesASyntheticActivationPeak() {
        // The weighted average of the bins around the strongest lands on the peak, between bins
        // too, and the strongest activation is the confidence.
        let net = FakeCrepe()
        let crepe = CrepePitch(net, rate)
        for hz in [87.31, 196.0, 440.0, 451.0, 880.0] {
            net.answerHz = hz
            let a = crepe.activationsOf(frame16k: voice(hz, 0.1))
            XCTAssertEqual(a.count, CrepePitch.BINS)
            XCTAssertEqual(cents(crepe.hz, hz), 0.0, accuracy: 1.0, "\(hz) read \(crepe.hz)")
            XCTAssertEqual(crepe.clarity, Double(a.max()!), accuracy: 1e-9)
            XCTAssertTrue(crepe.clarity > 0.8)
        }
    }

    func testTheNetworkHearsANormalisedSixteenKilohertzFrame() {
        // A 1 kHz tone at 44.1 kHz reaches the network as 1024 samples of the same tone at
        // 16 kHz, ending (halfTaps + 1) input samples before the newest, with zero mean and unit
        // variance.
        let net = FakeCrepe()
        let crepe = CrepePitch(net, rate)
        let f = 1000.0
        let x = (0..<(rate / 4)).map { Float(0.25 * sin(2 * Double.pi * f * Double($0) / Double(rate))) }
        _ = crepe.process(x, end: x.count)
        let frame = net.lastFrame
        XCTAssertEqual(frame.count, CrepePitch.FRAME)
        let mean = frame.reduce(0.0) { $0 + Double($1) } / Double(frame.count)
        let variance = frame.reduce(0.0) { $0 + (Double($1) - mean) * (Double($1) - mean) } / Double(frame.count)
        XCTAssertEqual(mean, 0.0, accuracy: 1e-4)
        XCTAssertEqual(variance, 1.0, accuracy: 1e-3)
        let ratio = Double(rate) / 16000.0
        let halfTaps = Int(16 * ratio) + 1
        let newest = Double(x.count - 1)
        var worst = 0.0
        for j in 0..<CrepePitch.FRAME {
            let at = newest - (Double(CrepePitch.FRAME - 1 - j) * ratio + Double(halfTaps) + 1)
            worst = max(worst, abs(Double(frame[j]) - 2.0.squareRoot() * sin(2 * Double.pi * f * at / Double(rate))))
        }
        XCTAssertTrue(worst < 0.02, "worst \(worst)")
    }

    func testFindsTheFundamentalAcrossTheSingingRange() {
        // The fake network answers 10 cents sharp, about as coarse as CREPE can be: alone that
        // stands; refined by the autocorrelation peak it is within 3 cents.
        for refine in [false, true] {
            let net = FakeCrepe()
            let crepe = CrepePitch(net, rate, refine: refine)
            for hz in [87.31, 110.0, 196.0, 261.63, 440.0, 659.25, 880.0] {
                net.answerHz = hz * pow(2.0, 10.0 / 1200)
                let x = voice(hz)
                XCTAssertTrue(crepe.process(x, end: x.count), "\(hz) voiced")
                let err = cents(crepe.hz, hz)
                XCTAssertTrue(abs(err) < (refine ? 3.0 : 15.0), "\(hz) refine=\(refine) read \(crepe.hz) (\(err) cents)")
                XCTAssertTrue(crepe.periodicity > 0.9, "\(hz) periodicity \(crepe.periodicity)")
            }
        }
    }

    func testSilenceAndNoiseAreNotVoiced() {
        let net = FakeCrepe()
        let crepe = CrepePitch(net, rate)
        let silence = [Float](repeating: 0, count: rate / 4)
        XCTAssertFalse(crepe.process(silence, end: silence.count))
        // The level gate: silence never reaches the network.
        XCTAssertEqual(net.calls, 0)
        // The fake confidently names a pitch in the noise; the periodicity check turns it down.
        var rnd = JavaRandom(4)
        let noise = (0..<(rate / 4)).map { _ in Float(rnd.nextGaussian() * 0.1) }
        XCTAssertFalse(crepe.process(noise, end: noise.count), "noise read as \(crepe.hz) at \(crepe.clarity)")
        XCTAssertEqual(net.calls, 1)
        XCTAssertTrue(crepe.periodicity < 0.5, "periodicity \(crepe.periodicity)")
        // And a network that is not sure is not voiced, however periodic the sound.
        net.peak = 0.3
        let x = voice(220.0)
        XCTAssertFalse(crepe.process(x, end: x.count))
    }

    func testTheOldTrackerReadsALowNoteTenTimesTooHigh() {
        // Kept as a record of why the tracker was replaced: at 110 Hz the autocorrelation
        // tracker's "first strong peak" search accepted the shortest lag against an unfilled
        // neighbour. CREPE reads the note (here a fake network answering 110 Hz, refined).
        let x = voice(110.0)
        let old = PitchTracker(rate)
        _ = old.process(x, end: x.count)
        let net = FakeCrepe()
        net.answerHz = 110.0
        let crepe = CrepePitch(net, rate)
        _ = crepe.process(x, end: x.count)
        XCTAssertEqual(crepe.hz, 110.0, accuracy: 1.0)
        print("autocorrelation tracker read \(old.hz) Hz for 110 Hz")
    }
}
