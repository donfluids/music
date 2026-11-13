"""Oracle key finding on GuitarSet's annotations (JAMS notes, chords, beats): what each method
gets with perfect transcription, at the live schedule (10, 14, 18, ... s, as tools/skey), so method
error can be told from transcription error.

Methods (all named and fixed here before scoring; trained ones use leave-one-progression-out
cross-validation, 15 folds: GuitarSet's 6 players play the same 30 lead sheets, 15 progressions
x 2 tempos/keys, and every take of a progression is held out together; only held-out numbers):
  C1 <profile> [8s]   duration-weighted pitch-class histogram of the notes heard so far (onset
                      before the moment, duration cut at the moment), Pearson correlation with
                      the 24 rotated profiles (the app's KeyDetector rule). "8s": each note
                      weighted by exp(-age / 8 s) (the app's Guitar key memory), age from the
                      note's end; without: the whole take so far.
  C4-pcp (CV)         learned 12-bin profile per mode, log-likelihood (Madsen & Widmer's
                      learned PCP, TdC's cross-entropy match), duration-weighted.
  C4-int (CV)         learned interval profile: P(next pitch class | this pitch class, key) per
                      mode (144 per mode), consecutive notes by onset, weighted by the next
                      note's duration (Madsen & Widmer 2007's interval profiles).
  C3 (comp chords; instructed lead-sheet chords; tokens = chord root relative to the tonic x
      major/minor triad family (min, min7, hdim7, dim count as minor, as the app's ChordLabel)):
      C3-M5 (CV)      each chord once
      C3-M6 (CV)      one token per half bar (chord sounding at its start): duration
      C3-M7 (CV)      each chord once, separate profiles for chords starting on the downbeat
                      of an odd-numbered bar ("strong") and the rest
      C3-M8 (CV)      half-bar tokens, strong = half bars starting on odd-numbered downbeats
      C3-dt (fixed)   tonic from deClercq-Temperley's overall rock root distribution
                      (AMADS DeClerqTemperley.roots) over half-bar tokens (log-likelihood);
                      minor when most half bars rooted on that tonic are minor chords.
      C3-app rule     the app's ChordKeyEstimator (hand weights, cadence and first/last bonuses)
      C3-M6s RS200 (fixed)  chord durations in seconds, root x family profiles counted from the
                      RS200 rock corpus (keyeval_common.rs200_root_counts): no GuitarSet in training
Smoothing for the trained profiles: add 0.5 to every count.

usage: python -I oracle_guitarset.py ANN_DIR SKEY_WORK OUT.json [--rs200 rs200_harmony_clt.zip]   (prints markdown tables)
"""
import glob
import json
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

from keyeval_common import DT_ROOTS, PROFILES, Tally, best, category, chord_key_suggest, corr_rank, parse_name, rs200_root_counts, table  # noqa: E402

PC_ROOT = {"C": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "F": 5, "F#": 6, "Gb": 6, "G": 7, "G#": 8,
           "Ab": 8, "A": 9, "A#": 10, "Bb": 10, "B": 11, "Cb": 11}
MINORISH = ("min", "hdim", "dim")
ALPHA = 0.5


def chord_token(v):
    """'D#:maj' -> (root, minor) or None for N."""
    if v in ("N", "X") or ":" not in v and v not in PC_ROOT:
        return None
    root, _, qual = v.partition(":")
    q = qual.split("/")[0].split("(")[0]
    return PC_ROOT[root], 1 if q.startswith(MINORISH) else 0


def load_take(path):
    j = json.load(open(path))
    notes = []
    chords = []
    beats = []
    for a in j["annotations"]:
        ns = a["namespace"]
        if ns == "note_midi":
            for d in a["data"]:
                notes.append((d["time"], d["time"] + d["duration"], int(round(d["value"]))))
        elif ns == "chord" and not chords and a["annotation_metadata"].get("data_source", "") == "":
            for d in a["data"]:  # the instructed (lead sheet) chords
                tok = chord_token(d["value"])
                if tok is not None:
                    chords.append((d["time"], d["time"] + d["duration"], tok))
        elif ns == "beat_position":
            for d in a["data"]:
                beats.append((d["time"], d["value"]["position"], d["value"]["num_beats"]))
        elif ns == "key_mode":
            key = parse_name(a["data"][0]["value"])
    notes.sort()
    return dict(notes=notes, chords=chords, beats=beats, key=key)


def half_bars(beats):
    """(time, strong) for each half bar: downbeats and the middle beat of even-length bars;
    strong = the downbeat of an odd-numbered bar (1st, 3rd, ...), counted from the first downbeat."""
    out = []
    bar = -1
    for t, pos, nb in beats:
        if pos == 1:
            bar += 1
            out.append((t, bar >= 0 and bar % 2 == 0))
        elif nb % 2 == 0 and pos == nb // 2 + 1 and bar >= 0:
            out.append((t, False))
    return out


def chord_at(chords, t):
    for s, e, tok in chords:
        if s - 1e-6 <= t < e:
            return tok
    return None


def pc_hist(notes, t, tau=None):
    h = np.zeros(12)
    for s, e, p in notes:
        if s >= t:
            break
        e2 = min(e, t)
        w = e2 - s
        if tau:
            w *= math.exp(-(t - e2) / tau)
        h[p % 12] += w
    return h


def transitions(notes, t=None):
    """(from_pc, to_pc, weight) for consecutive notes by onset (heard before t)."""
    out = []
    prev = None
    for s, e, p in notes:
        if t is not None and s >= t:
            break
        if prev is not None:
            w = (min(e, t) if t is not None else e) - s
            out.append((prev % 12, p % 12, w))
        prev = p
    return out


def rel_tokens_train(tok_list, key):
    """Counts of (relative root, minor) for training from one take."""
    tonic, mode = key % 12, key // 12
    c = np.zeros((2, 24))
    for (r, mi), strong in tok_list:
        c[0 if strong else 1, ((r - tonic) % 12) + 12 * mi] += 1
    return mode, c


def loglik_keys(counts_by_key_fn):
    return np.array([counts_by_key_fn(k) for k in range(24)])


class Trained:
    """Counts per mode, transposed to the tonic, add-ALPHA smoothed."""

    def __init__(self, shape):
        self.c = np.zeros((2,) + shape)

    def prob(self):
        p = self.c + ALPHA
        return p / p.sum(axis=-1, keepdims=True)


def main():
    ann, skey_work, out_json = sys.argv[1], sys.argv[2], sys.argv[3]
    takes = {}
    for f in sorted(glob.glob(os.path.join(ann, "*.jams"))):
        stem = os.path.basename(f)[:-5]
        kind = "solo" if stem.endswith("_solo") else "comp"
        r = json.load(open(os.path.join(skey_work, "res", f"guitarset_{stem}.json")))
        t = load_take(f)
        t.update(kind=kind, moments=[float(m) for m in r["moments"]], prog=stem.split("_")[1].split("-")[0])
        takes[stem] = t
    progs = sorted({t["prog"] for t in takes.values()})
    print(len(takes), "takes,", len(progs), "progressions (CV folds)", file=sys.stderr)

    est = defaultdict(dict)  # method -> stem -> [estimates per moment]

    # ---- C1: fixed profiles on notes
    for stem, t in takes.items():
        for name, prof in PROFILES.items():
            for tau, suf in ((None, ""), (8.0, " 8s")):
                est[f"C1 {name}{suf}"][stem] = [best(corr_rank(pc_hist(t["notes"], m, tau), prof)) for m in t["moments"]]

    # ---- CV-trained methods
    def tokens_for(t, upto, scheme):
        """chord tokens with strong flags for scheme M5/M6/M7/M8, heard before `upto`."""
        hb = half_bars(t["beats"])
        downs = [h for h, s in hb if s]
        if scheme in ("M5", "M7"):
            out = []
            for s, e, tok in t["chords"]:
                if s >= upto:
                    break
                strong = scheme == "M7" and any(abs(s - d) < 0.08 for d in downs)
                out.append((tok, strong))
            return out
        out = []
        for h, strong in hb:
            if h >= upto:
                break
            tok = chord_at(t["chords"], h)
            if tok is not None:
                out.append((tok, strong if scheme == "M8" else False))
        return out

    for fold in progs:
        train = [t for t in takes.values() if t["prog"] != fold]
        test = [(s, t) for s, t in takes.items() if t["prog"] == fold]
        # C4 on solo notes (trained on solo takes), pcp also on comp notes (trained on comp)
        for kind in ("solo", "comp"):
            pcp = Trained((12,))
            itv = Trained((12, 12))
            for t in train:
                if t["kind"] != kind:
                    continue
                tonic, mode = t["key"] % 12, t["key"] // 12
                h = pc_hist(t["notes"], 1e9)
                pcp.c[mode] += np.roll(h, -tonic)
                for a, b, w in transitions(t["notes"]):
                    itv.c[mode, (a - tonic) % 12, (b - tonic) % 12] += w
            lp, li = np.log(pcp.prob()), np.log(itv.prob())
            for stem, t in test:
                if t["kind"] != kind:
                    continue
                e1, e2 = [], []
                for m in t["moments"]:
                    h = pc_hist(t["notes"], m)
                    tr = transitions(t["notes"], m)
                    s1 = np.array([(np.roll(h, -(k % 12)) * lp[k // 12]).sum() for k in range(24)])
                    s2 = np.zeros(24)
                    for k in range(24):
                        tn = k % 12
                        s2[k] = sum(w * li[k // 12, (a - tn) % 12, (b - tn) % 12] for a, b, w in tr)
                    e1.append(best(s1) if h.sum() > 0 else -1)
                    e2.append(best(s2) if tr else -1)
                est[f"C4-pcp ({kind})"][stem] = e1
                if kind == "solo":
                    est["C4-int"][stem] = e2
        # C3 on comp chords
        for scheme in ("M5", "M6", "M7", "M8"):
            prof = Trained((2, 24))  # [mode][strong/weak][token]
            for t in train:
                if t["kind"] != "comp":
                    continue
                mode, c = rel_tokens_train(tokens_for(t, 1e9, scheme), t["key"])
                prof.c[mode] += c
            lp = np.log(prof.prob())
            for stem, t in test:
                if t["kind"] != "comp":
                    continue
                e = []
                for m in t["moments"]:
                    toks = tokens_for(t, m, scheme)
                    if not toks:
                        e.append(-1)
                        continue
                    s = np.zeros(24)
                    for k in range(24):
                        tn, md = k % 12, k // 12
                        s[k] = sum(lp[md, 0 if st else 1, ((r - tn) % 12) + 12 * mi] for (r, mi), st in toks)
                    e.append(best(s))
                est[f"C3-{scheme}"][stem] = e

    # ---- C3-dt: fixed literature root distribution, tonic only; mode from the tonic chords' quality
    ldt = np.log(np.asarray(DT_ROOTS))
    for stem, t in takes.items():
        if t["kind"] != "comp":
            continue
        e = []
        for m in t["moments"]:
            toks = tokens_for(t, m, "M6")
            if not toks:
                e.append(-1)
                continue
            s = np.array([sum(ldt[(r - tn) % 12] for (r, mi), _ in toks) for tn in range(12)])
            tn = int(np.argmax(s))
            on = [mi for (r, mi), _ in toks if r == tn]
            e.append(tn + (12 if on and sum(on) > len(on) / 2 else 0))
        est["C3-dt"][stem] = e

    # ---- C3-M6s RS200 (fixed): chord durations in seconds, profiles from the RS200 rock corpus
    if "--rs200" in sys.argv:
        cnt, used = rs200_root_counts(sys.argv[sys.argv.index("--rs200") + 1])
        lr = np.log((cnt + ALPHA) / (cnt + ALPHA).sum(axis=-1, keepdims=True))
        for stem, t in takes.items():
            if t["kind"] != "comp":
                continue
            e = []
            for m in t["moments"]:
                toks = [((r, mi), min(en, m) - s) for s, en, (r, mi) in t["chords"] if s < m]
                if not toks:
                    e.append(-1)
                    continue
                e.append(best(np.array([sum(w * lr[k // 12, ((r - k % 12) % 12) + 12 * mi] for (r, mi), w in toks) for k in range(24)])))
            est["C3-M6s RS200 (fixed)"][stem] = e

    # ---- C3-app: the app's own ChordKeyEstimator rule (hand weights) on the annotated chords
    for stem, t in takes.items():
        if t["kind"] != "comp":
            continue
        e = []
        for m in t["moments"]:
            segs = [(r, mi, s, min(en, m)) for s, en, (r, mi) in t["chords"] if s < m]
            sc = chord_key_suggest(segs)
            e.append(-1 if sc is None else best(sc))
        est["C3-app rule"][stem] = e

    # ---- score
    res = {}
    rows = defaultdict(list)
    for method, per in est.items():
        for kind in ("comp", "solo"):
            ty = Tally()
            for stem, e in per.items():
                t = takes[stem]
                if t["kind"] != kind:
                    continue
                for x in e:
                    ty.add(x, t["key"], stem)
            if ty.n == 0:
                continue
            res[f"{method}|{kind}"] = dict(exact=ty.exact, rel=ty.rel, mirex=ty.mirex, n=ty.n, clips=len(ty.clips))
            rows[kind].append((method, ty))
    json.dump(dict(scores=res, est={m: v for m, v in est.items()}), open(out_json, "w"))
    for kind in ("comp", "solo"):
        print(f"\n### GuitarSet {kind}: oracle (annotations), live readings\n")
        rr = [[m, f"{ty.exact:.1f}", f"{ty.rel:.1f}", f"{ty.mirex:.1f}", str(len(ty.clips)), str(ty.n)] for m, ty in rows[kind]]
        print(table(["method", "exact %", "relative %", "MIREX %", "clips", "readings"], rr))


if __name__ == "__main__":
    main()
