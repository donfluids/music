"""The proposed detune fix ("adopt+refine" in the measurements), as edits to a SCRATCH COPY of
the repo at 9ad92ca (never the real checkout). Kotlin only; the Swift port is the same three edits
in ios/KeyCatchCore (PitchTracker.swift, KeyLevelTracker.swift, MusicAnalyzer.swift).

  1. KeyLevelTracker: the level curve lives on a 10-cent lattice and leans toward half a semitone
     for scattered singers. Level.steadyCents is the centre moved by the notes' mean signed
     distance from the scale, so a steady level is known to a few cents. The curve itself, which
     the wandering case uses, is unchanged.
  2. SingerModel.adopt(cents): move the grid to another estimate of where the singer sits,
     keeping the circular sums, the shown lean and the relabelling consistent.
  3. MusicAnalyzer.updateKey: when the singers hold one level (within 40 cents, and there now),
     scatter no more than 25 cents around it, and its steady offset is 10 cents or more from the
     singer model's grid, the grid adopts it. Everything downstream (key notes, relabelling, the
     tuning shown) then follows.

usage: python -I apply_fix.py SCRATCH_REPO
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
    repo = sys.argv[1]
    if os.path.exists(os.path.join(repo, ".git")):
        raise SystemExit("refusing: this looks like a real checkout; use a scratch copy")
    d = os.path.join(repo, "analysis/src/main/kotlin/com/donfluids/music/dsp")
    sub(f"{d}/KeyLevelTracker.kt", """            cachedPath = refine(use)
""", """            cachedPath = refine(use)
            cachedSettle = settle(use, cachedPath)
""")
    sub(f"{d}/KeyLevelTracker.kt", """        /** How far notes sat from the scale once the wandering is removed, in cents. */
        val spreadCents: Double,
    ) {""", """        /** How far notes sat from the scale once the wandering is removed, in cents. */
        val spreadCents: Double,
        /**
         * Where singers who have not wandered sit within the semitone, in cents from concert pitch
         * (-50 to 50): the centre, placed more finely than the curve's 10-cent steps.
         */
        val steadyCents: Double = 0.0,
    ) {""")
    sub(f"{d}/KeyLevelTracker.kt", """        return Level(moved, centre, now, low, high, classify(use, path), spread)""",
        """        val steady = centre + cachedSettle
        return Level(moved, centre, now, low, high, classify(use, path), spread, steady - 100.0 * Math.round(steady / 100.0))""")
    sub(f"{d}/KeyLevelTracker.kt", """    private var cachedPath = DoubleArray(0)
""", """    private var cachedPath = DoubleArray(0)
    private var cachedSettle = 0.0
""")
    sub(f"{d}/KeyLevelTracker.kt", """    private fun addToProfile(""", """    /**
     * The curve moves in 10-cent steps, and for a scattered singer the decoding leans toward half
     * a semitone, where notes between two scale steps cost least. Sitting 39 cents sharp then often
     * reads as 50, the boundary between two keys. The notes' mean signed distance from the scale,
     * along the curve, is how far to move a steady level to place it within a few cents. Notes far
     * from every scale step (chromatic or careless) are left out of the mean.
     */
    private fun settle(notes: List<SungNote>, path: DoubleArray): Double {
        val limit = 1.5 * maxOf(spreadForTemplate, floorSpread, 20.0)
        var sw = 0.0; var sd = 0.0
        for (i in notes.indices) {
            val d = signedDistanceToScale(notes[i].concertPitch * 100.0, path[i])
            if (abs(d) > limit) continue
            val w = notes[i].durationSeconds.coerceIn(0.1, 1.5)
            sw += w; sd += w * d
        }
        return if (sw > 0.0) sd / sw else 0.0
    }

    /** Like [distanceToScale], with the sign: positive when the note is above the nearest scale step. */
    private fun signedDistanceToScale(cents: Double, tonicCents: Double): Double {
        val rel = Math.floorMod(Math.round((cents - tonicCents) * 10).toInt(), 12000) / 10.0
        var best = 600.0
        for (deg in MAJOR_DEGREES) {
            var d = rel - deg * 100.0
            if (d > 600.0) d -= 1200.0
            if (d < -600.0) d += 1200.0
            if (abs(d) < abs(best)) best = d
        }
        return best
    }

    private fun addToProfile(""")
    sub(f"{d}/PitchTracker.kt", """    fun reset() {
        offsetCents = 0.0; currentCents = 0.0""", """    /**
     * Moves the grid to [cents] from concert pitch: another estimate of where the singer sits,
     * from the whole tune rather than the last few notes (see MusicAnalyzer). The remembered notes
     * turn with it and the shown lean is kept, as when the grid moves on its own.
     */
    fun adopt(cents: Double) {
        val step = ((cents - offsetCents + 50.0).mod(100.0)) - 50.0
        offsetCents += step
        leanCents -= step
        val turn = -2 * PI * step / 100.0
        val c = kotlin.math.cos(turn); val s = kotlin.math.sin(turn)
        val nc = sumCos * c - sumSin * s
        sumSin = sumCos * s + sumSin * c
        sumCos = nc
        if (offsetCents > 50.0) { offsetCents -= 100.0; relabelSemitones++ }
        else if (offsetCents <= -50.0) { offsetCents += 100.0; relabelSemitones-- }
        currentCents = offsetCents + leanCents
    }

    fun reset() {
        offsetCents = 0.0; currentCents = 0.0""")
    sub(f"{d}/MusicAnalyzer.kt", """            keyLevel = levelTracker.analyse(notes)?.takeIf { l ->
                notes.size >= 16 && notes.sumOf { it.durationSeconds } >= 6.0 &&
                    (l.highCents - l.lowCents >= 70.0 || kotlin.math.abs(l.nowCents - l.centreCents) >= 70.0)
            }
""", """            val level = levelTracker.analyse(notes)
            val enough = notes.size >= 16 && notes.sumOf { it.durationSeconds } >= 6.0
            keyLevel = level?.takeIf { l ->
                enough && (l.highCents - l.lowCents >= 70.0 || kotlin.math.abs(l.nowCents - l.centreCents) >= 70.0)
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
            heldLevel = level?.takeIf { l ->
                keyLevel == null && enough && l.spreadCents <= 25.0 &&
                    l.highCents - l.lowCents <= 40.0 && kotlin.math.abs(l.nowCents - l.centreCents) <= 30.0
            }
            heldLevel?.let { l ->
                val away = ((l.steadyCents - singer.offsetCents + 50.0).mod(100.0)) - 50.0
                if (kotlin.math.abs(away) >= 10.0) singer.adopt(l.steadyCents)
            }
""")
    # The grid taken from the level makes the singer model's own scatter look tighter than the
    # singing is; decisions keep the level's scatter as well.
    sub(f"{d}/MusicAnalyzer.kt", """    private fun keySpreadCents(): Double = maxOf(keyLevel?.spreadCents ?: 0.0, singer.spreadCents)""",
        """    private fun keySpreadCents(): Double = maxOf((keyLevel ?: heldLevel)?.spreadCents ?: 0.0, singer.spreadCents)""")
    sub(f"{d}/MusicAnalyzer.kt", """    private var keyLevel: KeyLevelTracker.Level? = null
""", """    private var keyLevel: KeyLevelTracker.Level? = null
    /** The key level, when the singers hold it steadily and the singer model's grid follows it. */
    private var heldLevel: KeyLevelTracker.Level? = null
""")
    sub(f"{d}/MusicAnalyzer.kt", """        levelTracker.reset()
""", """        levelTracker.reset()
        heldLevel = null
""")
    print("fix applied:", repo)


if __name__ == "__main__":
    main()
