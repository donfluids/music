"""Shared helpers for tools/keyeval: key indices, profiles, scoring, the live reading schedule.

Key index: 0..11 = C..B major, 12..23 = C..B minor (tonic + 12), as KeyCatch's Key(tonic, mode)
and tools/skey.
"""
import json
import math
import os
from collections import Counter

import numpy as np

PITCH = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]
FIRST, EVERY = 10.0, 4.0

# Key profiles, tonic first, (major, minor). The app's numbers (K-K in KeyDetector.kt, Temperley 1999
# in PitchTracker.kt's MelodicKeyFinder) and the literature profiles as given in AMADS (MIT licence),
# github.com/music-computing/amads @ f250bbd99b9ab18543d3c92ac110e2c12cee58f8, amads/pitch/key/profiles.py:
# KrumhanslKessler, Temperley, AardenEssen, TemperleyDeClerq (major/minor = melody, harmony_*),
# AlbrechtShanahan.
PROFILES = {
    "kk": ((6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88),
           (6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)),
    "t99": ((5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0),
            (5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0)),
    "ae": ((17.7661, 0.145624, 14.9265, 0.160186, 19.8049, 11.3587, 0.291248, 22.062, 0.145624, 8.15494, 0.232998, 4.95122),
           (18.2648, 0.737619, 14.0499, 16.8599, 0.702494, 14.4362, 0.702494, 18.6161, 4.56621, 1.93186, 7.37619, 1.75623)),
    "tdcm": ((0.223, 0.001, 0.158, 0.015, 0.194, 0.071, 0.002, 0.169, 0.003, 0.119, 0.008, 0.035),
             (0.317, 0.001, 0.09, 0.159, 0.046, 0.097, 0.007, 0.131, 0.009, 0.047, 0.087, 0.009)),
    "tdch": ((0.231, 0.002, 0.091, 0.004, 0.149, 0.111, 0.004, 0.193, 0.004, 0.126, 0.011, 0.076),
             (0.202, 0.006, 0.102, 0.127, 0.047, 0.113, 0.005, 0.177, 0.046, 0.051, 0.09, 0.034)),
    "as": ((0.238, 0.006, 0.111, 0.006, 0.137, 0.094, 0.016, 0.214, 0.009, 0.08, 0.008, 0.081),
           (0.220, 0.006, 0.104, 0.123, 0.019, 0.103, 0.012, 0.214, 0.062, 0.022, 0.061, 0.052)),
}
PROFILE_NAMES = {"kk": "Krumhansl-Kessler", "t99": "Temperley 1999", "ae": "Aarden-Essen",
                 "tdcm": "Temperley-de Clercq melody", "tdch": "Temperley-de Clercq harmony", "as": "Albrecht-Shanahan"}
# deClercq & Temperley 2011 overall root distribution (rock), AMADS DeClerqTemperley.roots, same commit
DT_ROOTS = (0.328, 0.005, 0.036, 0.026, 0.019, 0.226, 0.003, 0.163, 0.04, 0.072, 0.081, 0.004)


def key_name(k):
    if k is None or k < 0:
        return "-"
    return PITCH[k % 12] + ("m" if k >= 12 else "")


def parse_name(s):
    """'C# Major', 'Bb minor', 'A:minor', 'Eb major', 'F#m' -> index (as tools/skey)."""
    s = s.strip().replace(":", " ")
    names = {"C": 0, "C#": 1, "DB": 1, "D": 2, "D#": 3, "EB": 3, "E": 4, "FB": 4, "E#": 5, "F": 5, "F#": 6, "GB": 6,
             "G": 7, "G#": 8, "AB": 8, "A": 9, "A#": 10, "BB": 10, "B": 11, "CB": 11, "B#": 0}
    parts = s.split()
    if len(parts) == 1:
        p = parts[0]
        minor = p.endswith("m") and not p.endswith("bm") or p.endswith("min")
        t = p.rstrip("min") if p.endswith("min") else (p[:-1] if p.endswith("m") else p)
        return names[t.upper()] + (12 if minor else 0)
    t, m = parts[0], parts[1].lower()
    return names[t.upper()] + (12 if m.startswith("min") else 0)


def relative(k):
    return (k % 12 + 3) % 12 if k >= 12 else (k + 9) % 12 + 12


def parallel(k):
    return k % 12 if k >= 12 else k + 12


def category(est, ref):
    if est is None or est < 0:
        return "none"
    if est == ref:
        return "exact"
    if (est >= 12) == (ref >= 12) and (est - ref) % 12 in (5, 7):
        return "fifth"
    if est == relative(ref):
        return "relative"
    if est == parallel(ref):
        return "parallel"
    return "other"


def mirex(est, ref):
    return {"exact": 1.0, "fifth": 0.5, "relative": 0.3, "parallel": 0.2}.get(category(est, ref), 0.0)


def moments_for(duration, final=False):
    """10, 14, 18, ... s until the clip's end; the short sung test tunes also at their last whole second."""
    m = []
    t = FIRST
    while t <= duration - 0.05:
        m.append(t)
        t += EVERY
    if final:
        last = float(int(duration - 0.05))
        if last >= 4 and last not in m:
            m.append(last)
    return m


class Tally:
    def __init__(self):
        self.n = 0
        self.c = Counter()
        self.mx = 0.0
        self.clips = set()

    def add(self, est, ref, clip=None):
        self.n += 1
        self.c[category(est, ref)] += 1
        self.mx += mirex(est, ref)
        if clip is not None:
            self.clips.add(clip)

    @property
    def exact(self):
        return 100.0 * self.c["exact"] / max(self.n, 1)

    @property
    def rel(self):
        return 100.0 * self.c["relative"] / max(self.n, 1)

    @property
    def mirex(self):
        return 100.0 * self.mx / max(self.n, 1)


def corr_rank(x, prof):
    """Pearson correlation of a 12-vector with the 24 rotated profiles (the app's KeyDetector)."""
    x = np.asarray(x, dtype=float)
    xm = x - x.mean()
    vx = (xm * xm).sum()
    out = np.zeros(24)
    if vx < 1e-12:
        return out
    for mode in (0, 1):
        p = np.asarray(prof[mode], dtype=float)
        pm = p - p.mean()
        vp = (pm * pm).sum()
        for t in range(12):
            out[t + 12 * mode] = (xm * np.roll(pm, t)).sum() / math.sqrt(vx * vp)
    return out


def best(scores):
    """argmax with the app's order on ties (major keys C..B first, then minor)."""
    return int(np.argmax(scores))


def read_jsonl(path):
    rows = {}
    for line in open(path):
        d = json.loads(line)
        if "moment" in d:
            rows[d["moment"]] = d
    return rows


def table(header, rows):
    out = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    for r in rows:
        out.append("| " + " | ".join(r) + " |")
    return "\n".join(out)


def load_moments(skey_work, iid):
    """The reading moments tools/skey used for this clip (work/res/<id>.json), else None."""
    f = os.path.join(skey_work, "res", iid + ".json")
    if os.path.exists(f):
        return [float(m) for m in json.load(open(f))["moments"]]
    return None


# ---- the app's ChordKeyEstimator (app/src/main/kotlin/com/donfluids/music/dsp/ChordKeyEstimator.kt), ported
def _major_w(i, mi):
    table = {(0, 0): 1.0, (5, 0): 0.8, (7, 0): 0.8, (9, 1): 0.6, (2, 1): 0.5, (4, 1): 0.4, (10, 0): 0.25,
             (5, 1): 0.15, (2, 0): 0.1, (4, 0): 0.0, (9, 0): 0.0}
    return table.get((i, mi), -0.3)


def _minor_w(i, mi):
    table = {(0, 1): 1.0, (5, 1): 0.7, (7, 0): 0.7, (7, 1): 0.5, (8, 0): 0.6, (3, 0): 0.5, (10, 0): 0.5,
             (5, 0): 0.15, (2, 1): 0.4}
    return table.get((i, mi), -0.3)


def chord_key_suggest(segs):
    """segs: [(root, minor, start, end)]. Returns 24 scores (the app's rule; sus/7th refinements
    left out, since the tokens here are triad families)."""
    chords = [(r, mi, e - s) for r, mi, s, e in segs if e - s > 0]
    if not chords:
        return None
    total = sum(d for _, _, d in chords)
    out = np.zeros(24)
    for k in range(24):
        tn, md = k % 12, k // 12
        w = _minor_w if md else _major_w
        sc = sum(w((r - tn) % 12, mi) * d for r, mi, d in chords) / total
        cad = 0
        prev = None
        for r, mi, _ in chords:
            c = (r, mi)
            if prev is not None and prev != c and (prev[0] - tn) % 12 == 7 and (prev[1] == 0 or md == 1) \
                    and c[0] == tn and c[1] == md:
                cad += 1
            prev = c
        sc += min(cad * 0.03, 0.15)
        if chords[0][0] == tn and chords[0][1] == md:
            sc += 0.05
        if chords[-1][0] == tn and chords[-1][1] == md:
            sc += 0.05
        out[k] = sc
    return out


def rs200_root_counts(clt_zip):
    """Duration-weighted (seconds) counts of (root relative to the tonic, major/minor family) per
    mode from the RS200 harmonic analyses (de Clercq & Temperley, CC BY 4.0), single-key songs
    only; both transcribers' analyses are used. Mode per song (fixed rule): minor if at least 2/3
    of the tonic-chord time is a minor tonic chord, major if at most 1/3, else the song is left
    out. A chord is minor-family when its Roman numeral (after any b/#) starts lowercase
    (i, ii, ..., also diminished and half-diminished)."""
    import zipfile
    z = zipfile.ZipFile(clt_zip)
    counts = np.zeros((2, 24))
    used = [0, 0]
    for n in z.namelist():
        if n.startswith("__") or not n.endswith(".clt"):
            continue
        rows = []
        for line in z.read(n).decode("utf-8", "replace").splitlines():
            r = line.split()
            if len(r) >= 7:
                try:
                    rows.append((float(r[0]), r[2], int(r[3]), int(r[5])))
                except ValueError:
                    pass
        if len({r[3] for r in rows}) != 1 or len(rows) < 4:
            continue
        toks = []
        tmin = tmaj = 0.0
        for i, (t, rn, rel, _) in enumerate(rows[:-1]):
            d = rows[i + 1][0] - t
            if d <= 0:
                continue
            mi = 1 if rn.lstrip("b#")[:1].islower() else 0
            toks.append((rel % 12, mi, d))
            if rel % 12 == 0:
                if mi:
                    tmin += d
                else:
                    tmaj += d
        tot = tmin + tmaj
        if tot <= 0 or 1 / 3 < tmin / tot < 2 / 3:
            continue
        mode = 1 if tmin / tot >= 2 / 3 else 0
        used[mode] += 1
        for rel, mi, d in toks:
            counts[mode, rel + 12 * mi] += d
    return counts, used
