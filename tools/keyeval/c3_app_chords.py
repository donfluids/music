"""C3 (root-distribution key finding, Temperley & de Clercq 2013 models 6 and 8) on the app's
own ChordTracker output for GuitarSet comp, at the live moments.

The scratch app wrote, every second, the chord segments it had heard so far (variant 0, the
current app: work/<kv>/chords/<id>.jsonl). Tokens: chord root relative to the tonic x
major/minor family, as in oracle_guitarset.py. Methods (fixed before scoring):
  rule          the app's ChordKeyEstimator, ported (keyeval_common.chord_key_suggest), on these segments
  M6s           each segment weighted by its duration in seconds (model 6 without a metre)
  M8b           half-bar tokens (the app's chord sounding at each half bar), strong = odd-numbered
                downbeats, the half bars taken from GuitarSet's annotated beats (a semi-oracle
                metre: the app's own bar positions were not dumped)
Profiles are trained with leave-one-progression-out CV (15 folds), either on the annotated
chords ("ann-trained") or on the app's own chords of the training takes ("app-trained", the
whole take); only held-out numbers. Add-0.5 smoothing.

Also "M6s (RS200-trained, fixed)": the same duration model with profiles counted from the RS200
rock corpus harmonic analyses (keyeval_common.rs200_root_counts), so no GuitarSet data in training.

usage: python -I c3_app_chords.py ANN_DIR KV_DIR SKEY_WORK [--rs200 rs200_harmony_clt.zip] [--datasets guitarset_comp guitarset_comp_d39]
"""
import glob
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

from keyeval_common import Tally, best, chord_key_suggest, rs200_root_counts, table  # noqa: E402
from oracle_guitarset import Trained, chord_at, half_bars, load_take  # noqa: E402


def app_segments(path):
    out = {}
    for line in open(path):
        d = json.loads(line)
        out[d["moment"]] = [(int(r), int(mi), float(s), float(e)) for r, mi, s, e in d["seg"]]
    return out


def app_chord_at(segs, h):
    for r, mi, s, e in segs:
        if s - 1e-6 <= h < e:
            return (r, mi)
    return None


def main():
    ann, kv, skey_work = sys.argv[1], sys.argv[2], sys.argv[3]
    datasets = ["guitarset_comp", "guitarset_comp_d39"]
    if "--datasets" in sys.argv:
        datasets = [x for x in sys.argv[sys.argv.index("--datasets") + 1:] if not x.startswith("--")]
        datasets = datasets[: next((i for i, x in enumerate(datasets) if x.endswith(".zip")), len(datasets))]
    takes = {}
    for f in sorted(glob.glob(os.path.join(ann, "*_comp.jams"))):
        stem = os.path.basename(f)[:-5]
        t = load_take(f)
        t["prog"] = stem.split("_")[1].split("-")[0]
        t["hb"] = half_bars(t["beats"])
        takes[stem] = t
    clips = []
    for ds in datasets:
        suf = "_d39" if ds.endswith("_d39") else ""
        for stem, t in takes.items():
            iid = f"guitarset_{stem}{suf}"
            cf = os.path.join(kv, "chords", iid + ".jsonl")
            rf = os.path.join(skey_work, "res", iid + ".json")
            if not os.path.exists(cf):
                continue
            moments = [float(m) for m in json.load(open(rf))["moments"]]
            segs = app_segments(cf)
            last = segs[max(segs)]
            clips.append(dict(id=iid, ds=ds, stem=stem, take=t, moments=moments, segs=segs, final=last))
    print(len(clips), "clips", file=sys.stderr)
    progs = sorted({t["prog"] for t in takes.values()})

    def tokens(segs, hb, upto, scheme):
        if scheme == "M6s":
            return [((r, mi), False, e - s) for r, mi, s, e in segs if s < upto]
        out = []
        for h, strong in hb:
            if h >= upto:
                break
            tok = app_chord_at(segs, h)
            if tok is not None:
                out.append((tok, strong, 1.0))
        return out

    def ann_tokens(t, scheme):
        if scheme == "M6s":
            return [(tok, False, e - s) for s, e, tok in t["chords"]]
        return [(chord_at(t["chords"], h), st, 1.0) for h, st in t["hb"] if chord_at(t["chords"], h) is not None]

    est = defaultdict(dict)
    for c in clips:
        e = []
        for m in c["moments"]:
            sc = chord_key_suggest(c["segs"].get(m, []))
            e.append(-1 if sc is None else best(sc))
        est["rule (ChordKeyEstimator port)"][c["id"]] = e
    if "--rs200" in sys.argv:
        # profiles from an independent corpus (no GuitarSet in training): RS200 roots, duration-weighted
        cnt, used = rs200_root_counts(sys.argv[sys.argv.index("--rs200") + 1])
        print("RS200 songs used (major, minor):", used, file=sys.stderr)
        lp = np.log((cnt + 0.5) / (cnt + 0.5).sum(axis=-1, keepdims=True))
        for c in clips:
            e = []
            for m in c["moments"]:
                toks = tokens(c["segs"].get(m, []), c["take"]["hb"], m, "M6s")
                if not toks:
                    e.append(-1)
                    continue
                s = np.array([sum(w * lp[k // 12, ((r - k % 12) % 12) + 12 * mi] for (r, mi), st, w in toks) for k in range(24)])
                e.append(best(s))
            est["M6s (RS200-trained, fixed)"][c["id"]] = e
    for fold in progs:
        for scheme in ("M6s", "M8b"):
            for src in ("ann", "app"):
                prof = Trained((2, 24))
                if src == "ann":
                    for stem, t in takes.items():
                        if t["prog"] == fold:
                            continue
                        mode = t["key"] // 12
                        cnt = np.zeros((2, 24))  # weighted by duration for M6s
                        for (r, mi), st, w in ann_tokens(t, scheme):
                            cnt[0 if st else 1, ((r - t["key"] % 12) % 12) + 12 * mi] += w
                        prof.c[mode] += cnt
                else:
                    for c in clips:
                        t = c["take"]
                        if t["prog"] == fold:
                            continue
                        mode = t["key"] // 12
                        for (r, mi), st, w in tokens(c["final"], t["hb"], 1e9, scheme):
                            prof.c[mode, 0 if st else 1, ((r - t["key"] % 12) % 12) + 12 * mi] += w
                lp = np.log(prof.prob())
                for c in clips:
                    if c["take"]["prog"] != fold:
                        continue
                    e = []
                    for m in c["moments"]:
                        toks = tokens(c["segs"].get(m, []), c["take"]["hb"], m, scheme)
                        if not toks:
                            e.append(-1)
                            continue
                        s = np.zeros(24)
                        for k in range(24):
                            tn, md = k % 12, k // 12
                            s[k] = sum(w * lp[md, 0 if st else 1, ((r - tn) % 12) + 12 * mi] for (r, mi), st, w in toks)
                        e.append(best(s))
                    est[f"{scheme} ({src}-trained)"][c["id"]] = e
    rows = []
    res = {}
    for name, per in est.items():
        row = [name]
        for ds in datasets:
            ty = Tally()
            for c in clips:
                if c["ds"] == ds and c["id"] in per:
                    for x in per[c["id"]]:
                        ty.add(x, c["take"]["key"], c["id"])
            row.append(f"{ty.exact:.1f} / {ty.rel:.1f} ({len(ty.clips)}, {ty.n})")
            res[f"{ds}|{name}"] = dict(exact=ty.exact, rel=ty.rel, n=ty.n, clips=len(ty.clips))
        rows.append(row)
    print(table(["C3 on app chords: exact % / relative % (clips, readings)"] + datasets, rows))
    json.dump(res, open(os.path.join(os.path.dirname(kv.rstrip("/")), "c3_app.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
