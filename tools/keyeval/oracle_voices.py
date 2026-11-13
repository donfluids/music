"""Oracle key finding for Voices on the sung sets' own note lists (perfect transcription, no
singer drift), at the live schedule, to separate method error from CREPE/segmentation error.

Note lists: CSD (its note CSVs, aligned with the audio), RS200 sung tunes (the rendered notes),
GTSinger (the metadata's notes, the same pitches as its MusicXML, timed from its phone
durations, segments joined with 0.4 s as in gts_audio.py).

Methods (fixed; the C4 one cross-validated with the folds of c2_resolver.py, by song):
  C1 <profile>      the app's MelodicKeyFinder input (duration + 0.6 on phrase-final notes
                    (gap > 0.4 s) + 0.4 on the longest note) correlated with each profile
  C1 <profile> dur  plain duration-weighted histogram
  C4-int (CV)       learned interval profiles (as oracle_guitarset.py), trained on the other
                    folds of all these sets pooled
usage: python -I oracle_voices.py --items VOICE_ITEMS.json --csd-csv DIR --rs200 RS200.json --rs200-mel rs200_melody_nlt.zip
          --gts-clips gtsinger.json --gts-meta META.json [..] --skey-work SKEY_WORK
"""
import argparse
import csv
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

from c2_resolver import fold_of, group_of  # noqa: E402
from keyeval_common import PROFILES, Tally, best, corr_rank, load_moments, moments_for, table  # noqa: E402

ALPHA = 0.5


def hist(notes, t, app_weights):
    h = np.zeros(12)
    heard = [(s, min(e, t), p) for s, e, p in notes if s < t]
    if not heard:
        return h
    longest = max(range(len(heard)), key=lambda i: heard[i][1] - heard[i][0])
    for i, (s, e, p) in enumerate(heard):
        w = e - s
        if app_weights:
            nxt = heard[i + 1][0] if i + 1 < len(heard) else None
            if nxt is None or nxt - e > 0.4:
                w += 0.6
            if i == longest:
                w += 0.4
        h[p % 12] += w
    return h


def trans(notes, t=None):
    out = []
    prev = None
    for s, e, p in notes:
        if t is not None and s >= t:
            break
        if prev is not None:
            out.append((prev % 12, p % 12, (min(e, t) if t is not None else e) - s))
        prev = p
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--items", required=True)
    ap.add_argument("--csd-csv", required=True)
    ap.add_argument("--rs200", required=True)
    ap.add_argument("--rs200-mel", required=True)
    ap.add_argument("--gts-clips", required=True)
    ap.add_argument("--gts-meta", nargs="+", required=True)
    ap.add_argument("--skey-work", required=True)
    a = ap.parse_args()
    import gts_select
    import rs200_synth
    items = [i for i in json.load(open(a.items)) if i["dataset"] in ("csd", "rs200", "gtsinger")]
    rs = {c["name"]: c for c in json.load(open(a.rs200))["clips"]}
    rs_dir = os.path.dirname(a.rs200)
    gclips = gts_select.clips_from(a.gts_meta)
    notes_by = {}
    mels = rs200_synth.read_zip(a.rs200_mel, ".nlt")
    for it in items:
        if it["dataset"] == "csd":
            f = os.path.join(a.csd_csv, it["id"][4:] + ".csv")
            notes_by[it["id"]] = [(float(r["start"]), float(r["end"]), int(r["pitch"])) for r in csv.DictReader(open(f))]
        elif it["dataset"] == "rs200":
            c = rs[it["id"][len("rs200_"):]]
            mel = [r for r in mels[c["song"]] if len(r) >= 4]
            notes_by[it["id"]] = [(s, s + d, p) for s, d, p in rs200_synth.notes_of(mel)]
    # GTSinger: metadata notes, timed
    gj = json.load(open(a.gts_clips))
    for m in gj:
        c = gclips[m["source"]]
        t0 = 0.0
        notes = []
        for sg in c["segs"]:
            t = t0
            prev = None
            for p, nd, ph_d, w in zip(sg["ep_pitches"], sg["ep_notedurs"], sg["ph_durs"], sg["ph2words"]):
                k = (w, p, nd)
                if p > 0 and k != prev:
                    notes.append((t, t + nd, p))
                prev = k if p > 0 else None
                t += ph_d
            t0 += sum(sg["word_durs"]) + 0.4
        notes_by[m["name"]] = notes
    est = defaultdict(dict)
    keys = {}
    for it in items:
        if it["id"] not in notes_by:
            continue
        notes = sorted(notes_by[it["id"]])
        notes_by[it["id"]] = notes
        mom = load_moments(a.skey_work, it["id"]) or moments_for(notes[-1][1] + 0.5)
        it["moments"] = mom
        keys[it["id"]] = it["key"]
        for name, prof in PROFILES.items():
            est[f"C1 {name}"][it["id"]] = [best(corr_rank(hist(notes, m, True), prof)) for m in mom]
            est[f"C1 {name} dur"][it["id"]] = [best(corr_rank(hist(notes, m, False), prof)) for m in mom]
    its = [it for it in items if it["id"] in keys]
    for k in range(5):
        train = [it for it in its if fold_of(group_of(it)) != k]
        test = [it for it in its if fold_of(group_of(it)) == k]
        c = np.zeros((2, 12, 12))
        for it in train:
            tn, md = it["key"] % 12, it["key"] // 12
            for x, y, w in trans(notes_by[it["id"]]):
                c[md, (x - tn) % 12, (y - tn) % 12] += w
        li = np.log((c + ALPHA) / (c + ALPHA).sum(axis=-1, keepdims=True))
        for it in test:
            e = []
            for m in it["moments"]:
                tr = trans(notes_by[it["id"]], m)
                s = np.array([sum(w * li[kk // 12, (x - kk % 12) % 12, (y - kk % 12) % 12] for x, y, w in tr) for kk in range(24)])
                e.append(best(s) if tr else -1)
            est["C4-int (CV)"][it["id"]] = e
    sets = []
    for d in ("csd", "rs200", "gtsinger"):
        for sub in ("major", "minor"):
            sets.append((d, sub))
    rows = []
    for name, per in est.items():
        row = [name]
        for d, sub in sets:
            ty = Tally()
            for it in its:
                if it["dataset"] == d and (it["key"] >= 12) == (sub == "minor") and it["id"] in per:
                    for x in per[it["id"]]:
                        ty.add(x, it["key"], it["id"])
            row.append(f"{ty.exact:.1f} / {ty.rel:.1f}" if ty.n else "-")
        rows.append(row)
    print(table(["oracle notes: exact % / relative %"] + [f"{d} {s}" for d, s in sets], rows))
    cnt = []
    for d, sub in sets:
        cl = [it for it in its if it["dataset"] == d and (it["key"] >= 12) == (sub == "minor")]
        cnt.append(f"{len(cl)} clips / {sum(len(it['moments']) for it in cl)} readings")
    print("\n" + table(["set"] + [f"{d} {s}" for d, s in sets], [["counts"] + cnt]))


if __name__ == "__main__":
    main()
