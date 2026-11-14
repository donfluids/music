"""Stage-by-stage view of DetuneTrace output, per data set (and detune) and variant:
  exact / relative %  the shown key at the live moments (10, 14, 18, ... s), as score_variants.py
  grid c              the singer model's grid offset (median over moments, and at 10 s / 30 s)
  lean c              the shown tuning (singer.currentCents or the key level's)
  level %             share of moments where the key-level tracker's notes were used
  notes right / +1 / -1  (RS200 only, from the rendered note list) share of the key finder's
                      note time whose pitch class is the sung one, one above, one below
  pair +1 %           readings whose key pair is a semitone above the true pair
  CREPE bias c        (RS200) median of concert pitch - (rendered pitch + detune) over final notes

usage: python -I dt_analyse.py --items ITEMS.json [..] --out OUTDIR [--variants base ..] [--truth rs200_truth.json]
         [--skey-work SKEY_WORK] [--by-singer]
"""
import argparse
import bisect
import json
import os
import statistics
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.abspath(os.path.join(HERE, "..")))
from keyeval_common import load_moments, moments_for, table  # noqa: E402


def pair_major(k):
    return k % 12 if k < 12 else (k % 12 + 3) % 12


def wrap(c):
    return (c + 50.0) % 100.0 - 50.0


def read(path):
    rows, final, done = {}, None, None
    for line in open(path):
        r = json.loads(line)
        if "moment" in r:
            rows[r["moment"]] = r
        elif "final" in r:
            final = r["final"]
        elif "done" in r:
            done = r
    return rows, final, done


def note_labels(notes, truth, detune):
    """Duration shares of the key finder's notes on the sung pitch class, one above, one below."""
    r = 2 ** (detune / 1200.0)
    starts = [n[0] for n in truth]
    acc = defaultdict(float)
    for p, s, d in notes:
        mid = (s + d / 2) * r  # back to the rendered clip's time
        i = bisect.bisect_right(starts, mid) - 1
        if i < 0 or mid > truth[i][0] + truth[i][1] + 0.05:
            acc["unmatched"] += d
            continue
        diff = (round(p) - truth[i][2]) % 12
        acc["right" if diff == 0 else "+1" if diff == 1 else "-1" if diff == 11 else "other"] += d
    return acc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--items", nargs="+", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--variants", nargs="*", default=["base"])
    ap.add_argument("--truth")
    ap.add_argument("--skey-work")
    ap.add_argument("--by-singer", action="store_true")
    ap.add_argument("--datasets", nargs="*")
    a = ap.parse_args()
    items = []
    for f in a.items:
        items += json.load(open(f))
    if a.datasets:
        items = [i for i in items if i["dataset"] in a.datasets]
    truth = json.load(open(a.truth)) if a.truth else {}
    for v in a.variants:
        agg = defaultdict(lambda: defaultdict(list))
        for it in items:
            f = os.path.join(a.out, v, it["id"] + ".jsonl")
            if not os.path.exists(f):
                continue
            rows, final, done = read(f)
            ds = it["dataset"]
            if a.by_singer and ds.startswith("rs200"):
                ds += ":" + it.get("base_id", it["id"]).rsplit("_", 1)[1]
            g = agg[ds]
            moments = (load_moments(a.skey_work, it["id"]) if a.skey_work else None) or moments_for(done["seconds"], it.get("final", False))
            det = it.get("detune", 0)
            tr = truth.get(it.get("base_id", it["id"]))
            for m in moments:
                r = rows.get(m)
                e = -1 if r is None else r["display"]
                g["exact"].append(e == it["key"])
                g["rel"].append(e >= 0 and e != it["key"] and pair_major(e) == pair_major(it["key"]))
                g["pair+1"].append(e >= 0 and (pair_major(e) - pair_major(it["key"])) % 12 == 1)
                g["pair-1"].append(e >= 0 and (pair_major(e) - pair_major(it["key"])) % 12 == 11)
                if r is None:
                    continue
                g["grid"].append(r["off"])
                g["lean"].append(r["tuning"])
                g["level"].append(r["lvl"] is not None)
                if r["lvl"] is not None:
                    g["lvlc"].append(wrap(r["lvl"][0]))
                g["sigma"].append(r["sigma"])
                if tr and "notes" in r:
                    lab = note_labels(r["notes"], tr["notes"], det)
                    tot = sum(lab.values()) or 1.0
                    for kk in ("right", "+1", "-1"):
                        g["n" + kk].append(lab[kk] / tot)
            for m, name in ((10.0, "grid10"), (30.0, "grid30")):
                if m in rows:
                    g[name].append(rows[m]["off"])
            g["clips"].append(1)
            if tr and final:
                r = 2 ** (det / 1200.0)
                starts = [n[0] for n in tr["notes"]]
                for p, s, d in final:
                    mid = (s + d / 2) * r
                    i = bisect.bisect_right(starts, mid) - 1
                    if i >= 0 and mid <= tr["notes"][i][0] + tr["notes"][i][1]:
                        t = tr["notes"][i]
                        dev = (p - t[2]) * 100 - t[3] - det
                        if abs(dev) < 50:
                            g["bias"].append(dev)

        def pct(x):
            return f"{100.0 * sum(x) / len(x):.1f}" if x else ""

        def med(x):
            return f"{statistics.median(x):.0f}" if x else ""

        rows = []
        for ds in sorted(agg, key=lambda d: (d.split("_d")[0], d)):
            g = agg[ds]
            rows.append([ds, str(len(g["clips"])), str(len(g["exact"])), f"{pct(g['exact'])} / {pct(g['rel'])}", pct(g["pair+1"]), pct(g["pair-1"]),
                         f"{med(g['grid10'])} / {med(g['grid30'])} / {med(g['grid'])}", med(g["lean"]), pct(g["level"]), med(g["lvlc"]),
                         f"{statistics.mean(g['sigma']):.2f}" if g["sigma"] else "",
                         " / ".join(f"{100 * statistics.mean(g['n' + k]):.1f}" for k in ("right", "+1", "-1")) if g["nright"] else "",
                         f"{statistics.median(g['bias']):+.1f}" if g["bias"] else ""])
        print(f"variant {v}")
        print(table(["set", "clips", "readings", "exact / rel %", "pair +1 %", "pair -1 %", "grid c (10 s / 30 s / all)", "lean c",
                     "level used %", "level centre c", "sigma", "notes right / +1 / -1 %", "CREPE bias c"], rows))
        print()


if __name__ == "__main__":
    main()
