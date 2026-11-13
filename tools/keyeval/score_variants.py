"""Scores KeyVariantsDump output: the shown key (displayKey) at the live moments (10, 14, 18, ... s;
the moments tools/skey used where it has them), per data set and variant.

usage: python -I score_variants.py --items ITEMS.json [..] --kv KV_DIR --skey-work SKEY_WORK
         [--datasets D ..] [--variants V ..] [--json OUT.json]
"""
import argparse
import glob
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from keyeval_common import Tally, load_moments, moments_for, read_jsonl, table  # noqa: E402


def clip_moments(it, rows_done, skey_work):
    m = load_moments(skey_work, it["id"]) if skey_work else None
    if m is None:
        m = moments_for(rows_done["seconds"], it.get("final", False))
    return m


def load(kv, variant, it):
    f = os.path.join(kv, variant, it["id"] + ".jsonl")
    if not os.path.exists(f):
        return None, None
    done = None
    for line in open(f):
        if '"done"' in line:
            done = json.loads(line)
    return read_jsonl(f), done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--items", nargs="+", required=True)
    ap.add_argument("--kv", required=True)
    ap.add_argument("--skey-work", default=None)
    ap.add_argument("--datasets", nargs="*")
    ap.add_argument("--variants", nargs="*")
    ap.add_argument("--json")
    a = ap.parse_args()
    items = []
    for f in a.items:
        items += json.load(open(f))
    if a.datasets:
        items = [i for i in items if i["dataset"] in a.datasets]
    variants = a.variants or sorted(os.path.basename(d) for d in glob.glob(os.path.join(a.kv, "*"))
                                    if os.path.isdir(d) and not os.path.basename(d).startswith(("_", "evidence", "chords")))
    tallies = defaultdict(Tally)
    for it in items:
        for v in variants:
            rows, done = load(a.kv, v, it)
            if rows is None:
                continue
            for m in clip_moments(it, done, a.skey_work):
                r = rows.get(m)
                tallies[(it["dataset"], v)].add(-1 if r is None else r["display"], it["key"], it["id"])
                if it["key"] >= 12 or it["key"] < 12:
                    tallies[(it["dataset"] + (":minor" if it["key"] >= 12 else ":major"), v)].add(
                        -1 if r is None else r["display"], it["key"], it["id"])
    out = {f"{d}|{v}": dict(exact=t.exact, rel=t.rel, mirex=t.mirex, n=t.n, clips=len(t.clips)) for (d, v), t in tallies.items()}
    if a.json:
        json.dump(out, open(a.json, "w"), indent=1)
    sets = sorted({d for d, _ in tallies})
    rows = []
    for v in variants:
        rows.append([v] + [f"{tallies[(d, v)].exact:.1f} / {tallies[(d, v)].rel:.1f}" if tallies[(d, v)].n else "" for d in sets])
    print(table(["variant (exact % / relative %)"] + sets, rows))
    print()
    print(table(["set", "clips", "readings"], [[d, str(len(tallies[(d, variants[0])].clips)), str(tallies[(d, variants[0])].n)] for d in sets]))


if __name__ == "__main__":
    main()
