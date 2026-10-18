"""Writes Grooves.kt: four-bar loops excerpted from the Groove MIDI Dataset."""
import sys
sys.path.insert(0, sys.argv[2])
import gmd
root, out = sys.argv[1], sys.argv[3]
ROWS = {r['id']: r for r in gmd.rows(root)}
# App style -> (performance id, start in quarter notes), chosen by pick.py and checked by eye.
PICKS = {
    'WORSHIP_SLOW':   ('drummer8/eval_session/8', 28.0),
    'ROCK_8':         ('drummer7/session2/1', 0.0),
    'POWER_ROCK':     ('drummer7/session2/45', 96.0),
    'POP_16':         ('drummer8/eval_session/10', 28.0),
    'GOSPEL_SHUFFLE': ('drummer8/session2/22', 56.0),
}
BARS, BEATS = 4, 4
# Loudness of each drum's strong hits (85th percentile) in the app's kit; ghost notes scale with it.
TARGET = {'KICK': 1.0, 'SNARE': 0.95, 'RIM': 0.65, 'HAT': 0.6, 'OPEN_HAT': 0.5, 'RIDE': 0.6, 'CRASH': 0.75, 'TOM_HI': 0.85, 'TOM_LO': 0.9}

def p85(xs):
    xs = sorted(xs); return xs[int(0.85 * (len(xs) - 1))]

blocks = []
for style, (gid, start) in PICKS.items():
    r = ROWS[gid]
    hits = gmd.read(root, r)
    length = BARS * BEATS
    win = [(t - start, d, v) for t, d, v in hits if start - 0.15 <= t < start + length - 0.15]
    # The loop's first downbeat, played a hair early or late, is its anchor: put it on the beat.
    win = [(0.0 if t < 0.03 else t, d, v) for t, d, v in win]
    scale = {d: TARGET[d] / p85([v for t, dd, v in win if dd == d]) for d in {d for t, d, v in win}}
    lines = ['        GrooveHit(%.3ff, Drum.%s, %.2ff)' % (t, d, min(1.0, v * scale[d])) for t, d, v in sorted(win)]
    blocks.append('''    /** %s: %s at %s bpm, four bars from beat %d. */
    val %s = Groove(
        source = "%s", genre = "%s", recordedBpm = %s, bars = %d, beatsPerBar = %d,
        hits = listOf(
%s,
        ),
    )''' % (style, r['style'], r['bpm'], int(start), style, gid, r['style'], r['bpm'], BARS, BEATS, ',\n'.join(lines)))

open(out, 'w').write('''package com.donfluids.piano

// Generated from the Groove MIDI Dataset; do not edit by hand.
//
// Drum grooves are excerpts from the Groove MIDI Dataset by Google LLC (Magenta),
// https://magenta.tensorflow.org/datasets/groove, licensed under the Creative Commons
// Attribution 4.0 International License, https://creativecommons.org/licenses/by/4.0/.
// Changes: four-bar excerpts of the performances named below, with each loop's first downbeat
// moved onto the beat, Roland TD-11 drum notes mapped to Mini Piano's drum voices, and
// velocities scaled to Mini Piano's drum levels.
//
// Gillick, Roberts, Engel, Eck, Bamman. "Learning to Groove with Inverse Sequence
// Transformations." International Conference on Machine Learning (ICML), 2019.

/** A drum hit in a recorded groove: position in beats from the start of the loop, drum, loudness 0..1. */
class GrooveHit(val beat: Float, val drum: Drum, val velocity: Float)

/**
 * A recorded drum loop. Positions keep the drummer's own timing, so hits land a little ahead
 * of or behind the grid as they were played; they are in beats, so the loop follows any tempo.
 */
class Groove(
    /** The performance in the Groove MIDI Dataset this loop is cut from. */
    val source: String,
    val genre: String,
    val recordedBpm: Int,
    val bars: Int,
    val beatsPerBar: Int,
    val hits: List<GrooveHit>,
)

/** Recorded grooves for the band's styles. */
object Grooves {
%s
}
''' % '\n\n'.join(blocks))
print("wrote", out)
