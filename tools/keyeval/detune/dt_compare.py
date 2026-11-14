"""Exact / relative % of the shown key at the live moments, per data set, for several DetuneTrace
runs side by side (each NAME=DIR, DIR holding <id>.jsonl; a run missing a clip leaves its cell
empty). RS200 rows are also split by singer when --by-singer is given.

usage: python -I dt_compare.py --items ITEMS.json [..] --run base=OUT/base --run fix=OUT2/base ...
         [--skey-work SKEY_WORK] [--by-singer]
"""
import argparse
import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.abspath(os.path.join(HERE, "..")))
from keyeval_common import Tally, load_moments, moments_for, table  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--items", nargs="+", required=True)
    ap.add_argument("--run", action="append", required=True)
    ap.add_argument("--skey-work")
    ap.add_argument("--by-singer", action="store_true")
    a = ap.parse_args()
    items = []
    for f in a.items:
        items += json.load(open(f))
    runs = [r.split("=", 1) for r in a.run]
    tallies = defaultdict(Tally)
    for it in items:
        sets = [it["dataset"]]
        if a.by_singer and it["dataset"].startswith("rs200"):
            sets.append(it["dataset"] + ":" + it.get("base_id", it["id"]).rsplit("_", 1)[1])
        for name, d in runs:
            f = os.path.join(d, it["id"] + ".jsonl")
            if not os.path.exists(f):
                continue
            rows, done = {}, None
            for line in open(f):
                r = json.loads(line)
                if "moment" in r:
                    rows[r["moment"]] = r
                elif "done" in r:
                    done = r
            ms = (load_moments(a.skey_work, it["id"]) if a.skey_work else None) or moments_for(done["seconds"], it.get("final", False))
            for m in ms:
                r = rows.get(m)
                for s in sets:
                    tallies[(s, name)].add(-1 if r is None else r["display"], it["key"], it["id"])
    sets = sorted({s for s, _ in tallies}, key=lambda s: (s.split("_d")[0].split(":")[0], s))
    out = []
    for s in sets:
        n = next((tallies[(s, nm)] for nm, _ in runs if tallies[(s, nm)].n), None)
        out.append([s, str(len(n.clips)) if n else "", str(n.n) if n else ""] +
                   [f"{tallies[(s, nm)].exact:.1f} / {tallies[(s, nm)].rel:.1f}" if tallies[(s, nm)].n else "" for nm, _ in runs])
    print(table(["set", "clips", "readings"] + [nm + " (exact / rel %)" for nm, _ in runs], out))


if __name__ == "__main__":
    main()
