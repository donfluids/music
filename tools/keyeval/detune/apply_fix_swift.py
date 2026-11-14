"""The Swift port of apply_fix.py, as edits to a SCRATCH COPY of ios/KeyCatchCore at 9ad92ca
(never the real checkout); 6e4dad9 has the same Swift sources: Level.steadyCents and KeyLevelTracker.settle, SingerModel.adopt, and
the held level in MusicAnalyzer, line for line as in Kotlin.

usage: python -I apply_fix_swift.py SCRATCH_KEYCATCHCORE_DIR
"""
import os
import sys


def sub(path, old, new):
    s = open(path).read()
    if new in s:
        return
    assert s.count(old) == 1, (path, old[:70])
    open(path, "w").write(s.replace(old, new))


def main():
    root = sys.argv[1]
    if os.path.exists(os.path.join(root, ".git")) or os.path.exists(os.path.join(root, "..", ".git")):
        raise SystemExit("refusing: this looks like a real checkout; use a scratch copy")
    d = os.path.join(root, "Sources/KeyCatchCore")
    sub(f"{d}/KeyLevelTracker.swift", """            cachedPath = refine(use)
""", """            cachedPath = refine(use)
            cachedSettle = settle(use, cachedPath)
""")
    sub(f"{d}/KeyLevelTracker.swift", """        public let spreadCents: Double

        public init(notes: [SungNote], centreCents: Double, nowCents: Double, lowCents: Double, highCents: Double, move: Move, spreadCents: Double) {""",
        """        public let spreadCents: Double
        /// Where singers who have not wandered sit within the semitone, in cents from concert pitch
        /// (-50 to 50): the centre, placed more finely than the curve's 10-cent steps.
        public let steadyCents: Double

        public init(notes: [SungNote], centreCents: Double, nowCents: Double, lowCents: Double, highCents: Double, move: Move, spreadCents: Double, steadyCents: Double = 0.0) {
            self.steadyCents = steadyCents""")
    sub(f"{d}/KeyLevelTracker.swift", """        return Level(notes: moved, centreCents: centre, nowCents: now, lowCents: low, highCents: high, move: classify(use, path), spreadCents: spread)""",
        """        let steady = centre + cachedSettle
        return Level(notes: moved, centreCents: centre, nowCents: now, lowCents: low, highCents: high, move: classify(use, path), spreadCents: spread,
                     steadyCents: steady - 100.0 * Double(kotlinRound(steady / 100.0)))""")
    sub(f"{d}/KeyLevelTracker.swift", """    private var cachedPath: [Double] = []
""", """    private var cachedPath: [Double] = []
    private var cachedSettle = 0.0
""")
    sub(f"{d}/KeyLevelTracker.swift", """    private func distanceToScale(_ cents: Double, _ tonicCents: Double) -> Double {""",
        """    /// The curve moves in 10-cent steps, and for a scattered singer the decoding leans toward half
    /// a semitone, where notes between two scale steps cost least. Sitting 39 cents sharp then often
    /// reads as 50, the boundary between two keys. The notes' mean signed distance from the scale,
    /// along the curve, is how far to move a steady level to place it within a few cents. Notes far
    /// from every scale step (chromatic or careless) are left out of the mean.
    private func settle(_ notes: [SungNote], _ path: [Double]) -> Double {
        let limit = 1.5 * max(spreadForTemplate, floorSpread, 20.0)
        var sw = 0.0; var sd = 0.0
        for i in notes.indices {
            let d = signedDistanceToScale(notes[i].concertPitch * 100.0, path[i])
            if abs(d) > limit { continue }
            let w = notes[i].durationSeconds.coerceIn(0.1, 1.5)
            sw += w; sd += w * d
        }
        return sw > 0.0 ? sd / sw : 0.0
    }

    /// Like `distanceToScale`, with the sign: positive when the note is above the nearest scale step.
    private func signedDistanceToScale(_ cents: Double, _ tonicCents: Double) -> Double {
        let rel = Double(floorMod(kotlinRound((cents - tonicCents) * 10), 12000)) / 10.0
        var best = 600.0
        for deg in KeyLevelTracker.MAJOR_DEGREES {
            var d = rel - Double(deg) * 100.0
            if d > 600.0 { d -= 1200.0 }
            if d < -600.0 { d += 1200.0 }
            if abs(d) < abs(best) { best = d }
        }
        return best
    }

    private func distanceToScale(_ cents: Double, _ tonicCents: Double) -> Double {""")
    sub(f"{d}/PitchTracker.swift", """    public func reset() {
        offsetCents = 0.0; currentCents = 0.0; leanCents = 0.0; relabelSemitones = 0""",
        """    /// Moves the grid to `cents` from concert pitch: another estimate of where the singer sits,
    /// from the whole tune rather than the last few notes (see MusicAnalyzer). The remembered notes
    /// turn with it and the shown lean is kept, as when the grid moves on its own.
    public func adopt(_ cents: Double) {
        var step = (cents - offsetCents + 50.0).truncatingRemainder(dividingBy: 100.0)
        if step < 0 { step += 100.0 }
        step -= 50.0
        offsetCents += step
        leanCents -= step
        let turn = -2 * Double.pi * step / 100.0
        let c = cos(turn); let s = sin(turn)
        let nc = sumCos * c - sumSin * s
        sumSin = sumCos * s + sumSin * c
        sumCos = nc
        if offsetCents > 50.0 { offsetCents -= 100.0; relabelSemitones += 1 }
        else if offsetCents <= -50.0 { offsetCents += 100.0; relabelSemitones -= 1 }
        currentCents = offsetCents + leanCents
    }

    public func reset() {
        offsetCents = 0.0; currentCents = 0.0; leanCents = 0.0; relabelSemitones = 0""")
    sub(f"{d}/MusicAnalyzer.swift", """            keyLevel = levelTracker.analyse(notes).flatMap { l in
                (notes.count >= 16 && notes.reduce(0.0, { $0 + $1.durationSeconds }) >= 6.0 &&
                    (l.highCents - l.lowCents >= 70.0 || abs(l.nowCents - l.centreCents) >= 70.0)) ? l : nil
            }
""", """            let level = levelTracker.analyse(notes)
            let enough = notes.count >= 16 && notes.reduce(0.0, { $0 + $1.durationSeconds }) >= 6.0
            keyLevel = level.flatMap { l in
                (enough && (l.highCents - l.lowCents >= 70.0 || abs(l.nowCents - l.centreCents) >= 70.0)) ? l : nil
            }
            // Singers who stay put but sit well off the grid: a scattered singer's notes, one at a
            // time, barely move the singer model (30 cents of scatter 39 cents sharp leaves it near
            // 0, and a third of the notes then read a semitone high). The key level reads the whole
            // tune against a scale and sees where they sit; the grid takes that.
            // Only for singing that holds one level (most of it within 40 cents, and there now):
            // a singer on the move is the singer model's to follow. And only for a singer scattering
            // 25 cents or less around it: beyond that the notes say little about where in the
            // semitone they sit, and a poor singer drifting flat was then shown a wrong key as
            // certain (SingerRobustnessTest; 30 and 35 cents both failed it).
            heldLevel = level.flatMap { l in
                (keyLevel == nil && enough && l.spreadCents <= 25.0 &&
                    l.highCents - l.lowCents <= 40.0 && abs(l.nowCents - l.centreCents) <= 30.0) ? l : nil
            }
            if let l = heldLevel {
                var away = (l.steadyCents - singer.offsetCents + 50.0).truncatingRemainder(dividingBy: 100.0)
                if away < 0 { away += 100.0 }
                away -= 50.0
                // Not to a level near the boundary between two keys: a level read just past 50 would
                // carry every note across it (a hymn sung 45 cents sharp was then shown D flat as
                // certain). The singer model's own grid crosses there only on its own evidence.
                if abs(away) >= 10.0 && abs(l.steadyCents) <= 40.0 { singer.adopt(l.steadyCents) }
            }
""")
    # The grid taken from the level makes the singer model's own scatter look tighter than the
    # singing is; decisions keep the level's scatter as well.
    sub(f"{d}/MusicAnalyzer.swift", """    private func keySpreadCents() -> Double { max(keyLevel?.spreadCents ?? 0.0, singer.spreadCents) }""",
        """    private func keySpreadCents() -> Double { max((keyLevel ?? heldLevel)?.spreadCents ?? 0.0, singer.spreadCents) }""")
    sub(f"{d}/MusicAnalyzer.swift", """    private var keyLevel: KeyLevelTracker.Level? = nil
""", """    private var keyLevel: KeyLevelTracker.Level? = nil
    /// The key level, when the singers hold it steadily and the singer model's grid follows it.
    private var heldLevel: KeyLevelTracker.Level? = nil
""")
    sub(f"{d}/MusicAnalyzer.swift", """        levelTracker.reset()
""", """        levelTracker.reset()
        heldLevel = nil
""")
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import test_edits
    test_edits.apply(os.path.join(root, "Tests/KeyCatchCoreTests/SingerRobustnessTests.swift"), test_edits.SW_SUBS)
    print("fix applied:", root)


if __name__ == "__main__":
    main()
