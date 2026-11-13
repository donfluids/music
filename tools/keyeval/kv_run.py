"""Runs KeyVariantsDump (Kotlin engine, the app's default models, several key-finder variants per
clip in lockstep) over a list of clips, in batches: each batch's audio is written as float32 at
44.1 kHz (moved up by the item's `detune` cents, as tools/skey does), the scratch repo's gradle
test is run, and the audio is deleted.

usage: python -I kv_run.py --items ITEMS.json [ITEMS2.json ...] --datasets D [D ...] --preset guitar|voices
         --variants "app:app,20:kk,..." --repo SCRATCH_REPO --out OUTDIR [--evidence] [--jobs 3] [--batch 24]
Items are tools/skey's items.json entries (id, dataset, audio, key, detune).
"""
import argparse
import json
import os
import subprocess
import sys
import time

import numpy as np

RATE = 44100


def load_audio(path):
    if path.endswith(".f32"):
        return np.fromfile(path, dtype="<f4").astype(np.float64), 44100
    import soundfile as sf
    x, r = sf.read(path, dtype="float64", always_2d=True)
    return x.mean(1), r


def to_app_rate(x, sr, detune):
    import soxr
    src = sr * 2 ** (detune / 1200.0)
    if abs(src - RATE) < 1e-9:
        return x
    return soxr.resample(x, src, RATE)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--items", nargs="+", required=True)
    ap.add_argument("--datasets", nargs="+", required=True)
    ap.add_argument("--preset", required=True)
    ap.add_argument("--variants", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--evidence", action="store_true")
    ap.add_argument("--jobs", type=int, default=3)
    ap.add_argument("--batch", type=int, default=24)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    items = []
    for f in a.items:
        items += [i for i in json.load(open(f)) if i["dataset"] in a.datasets]
    if a.limit:
        items = items[: a.limit]
    variants = [v.strip() for v in a.variants.split(",") if v.strip()]
    out = os.path.abspath(a.out)
    todo = [i for i in items if not all(os.path.exists(os.path.join(out, v.replace(":", "_"), i["id"] + ".jsonl")) for v in variants)
            or (a.evidence and not os.path.exists(os.path.join(out, "evidence", i["id"] + ".csv")))]
    print(len(items), "items,", len(todo), "to run,", len(variants), "variants", flush=True)
    tmp = os.path.join(out, "_tmp")
    os.makedirs(tmp, exist_ok=True)
    for b in range(0, len(todo), a.batch):
        batch = todo[b:b + a.batch]
        lines = []
        for it in batch:
            raw = os.path.join(tmp, it["id"] + ".f32")
            if not os.path.exists(raw):
                x, sr = load_audio(it["audio"])
                to_app_rate(x, sr, it.get("detune", 0)).astype("<f4").tofile(raw)
            lines.append(f"{raw} {it['id']} {out} {RATE} {a.preset}")
        lst = os.path.join(tmp, "list.txt")
        open(lst, "w").write("\n".join(lines) + "\n")
        t0 = time.time()
        cmd = ["./gradlew", "--offline", "-q", ":analysis:testDebugUnitTest", "--tests", "*KeyVariantsDump*", "--rerun",
               f"-Pkc.list={lst}", f"-Pkc.threads={a.jobs}", f"-Pkc.variants={','.join(variants)}"]
        if a.evidence:
            cmd.append("-Pkc.evidence=1")
        r = subprocess.run(cmd, cwd=a.repo, check=False, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if r.returncode != 0:
            print(r.stdout[-3000:], file=sys.stderr)
        for it in batch:
            raw = os.path.join(tmp, it["id"] + ".f32")
            if os.path.exists(raw):
                os.remove(raw)
        print(f"batch {b // a.batch + 1}/{(len(todo) + a.batch - 1) // a.batch}: {len(batch)} clips in {time.time() - t0:.0f} s", flush=True)


if __name__ == "__main__":
    main()
