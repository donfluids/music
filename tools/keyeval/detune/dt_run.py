"""Runs DetuneTrace (the app's Voices path, CREPE, scratch fixes by flag) over clips at several
detunes, in batches: each batch's audio is written as float32 at 44.1 kHz, moved up by the detune
in cents by resampling (as tools/skey and tools/keyeval do), the scratch repo's gradle test is
run, and the audio is deleted. CREPE's answers are cached per clip and detune, so later runs
with other variants cost only the key logic.

usage: python -I dt_run.py --items ITEMS.json --datasets D [D ...] --detunes 0 39 -39 ...
         --variants base,flagA,flagA+flagB --repo SCRATCH_REPO --out OUTDIR [--jobs 4] [--batch 24]
Items are tools/skey / tools/keyeval items (id, dataset, audio, key); only detune-0 items are
read, and each is run at every detune as <id>_d<cents> (d-39 written dm39), dataset <ds>_d<cents>.
"""
import argparse
import json
import os
import subprocess
import sys
import time

import numpy as np

RATE = 44100


def tag(d):
    return "" if d == 0 else (f"_d{d}" if d > 0 else f"_dm{-d}")


def expand(items, datasets, detunes):
    out = []
    for it in items:
        if it["dataset"] not in datasets or it.get("detune", 0) != 0:
            continue
        for d in detunes:
            j = dict(it)
            j["id"] = it["id"] + tag(d)
            j["dataset"] = it["dataset"] + tag(d)
            j["base_id"] = it["id"]
            j["detune"] = d
            out.append(j)
    return out


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
    ap.add_argument("--detunes", nargs="+", type=int, default=[0])
    ap.add_argument("--variants", default="base")
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--batch", type=int, default=24)
    ap.add_argument("--write-items")
    ap.add_argument("--prop", action="append", default=[], help="extra -P gradle property KEY=VALUE for the test JVM (scratch builds that read one)")
    a = ap.parse_args()
    items = []
    for f in a.items:
        items += json.load(open(f))
    items = expand(items, a.datasets, a.detunes)
    if a.write_items:
        json.dump(items, open(a.write_items, "w"), indent=0)
    variants = [v.strip() for v in a.variants.split(",") if v.strip()]
    out = os.path.abspath(a.out)
    cache = os.path.join(out, "_crepe")
    todo = [i for i in items if not all(os.path.exists(os.path.join(out, v, i["id"] + ".jsonl")) for v in variants)]
    print(len(items), "items,", len(todo), "to run,", len(variants), "variants", flush=True)
    tmp = os.path.join(out, "_tmp")
    os.makedirs(tmp, exist_ok=True)
    os.makedirs(cache, exist_ok=True)
    for b in range(0, len(todo), a.batch):
        batch = todo[b:b + a.batch]
        lines = []
        for it in batch:
            raw = os.path.join(tmp, it["id"] + ".f32")
            if not os.path.exists(raw):
                x, sr = load_audio(it["audio"])
                to_app_rate(x, sr, it["detune"]).astype("<f4").tofile(raw)
            lines.append(f"{raw} {it['id']} {out} {RATE} voices {os.path.join(cache, it['id'] + '.bin')}")
        lst = os.path.join(tmp, "list.txt")
        open(lst, "w").write("\n".join(lines) + "\n")
        t0 = time.time()
        cmd = ["./gradlew", "--offline", "-q", ":app:testDebugUnitTest", "--tests", "*DetuneTrace*", "--rerun",
               f"-Pdt.list={lst}", f"-Pdt.threads={a.jobs}", f"-Pdt.variants={','.join(variants)}"] + [f"-P{p}" for p in a.prop]
        r = subprocess.run(cmd, cwd=a.repo, check=False, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if r.returncode != 0:
            print(r.stdout[-3000:], file=sys.stderr)
        # The driver reports clips it could not run on the test's stderr, which -q hides: read the report.
        rep = os.path.join(a.repo, "app/build/test-results/testDebugUnitTest/TEST-com.donfluids.music.dsp.DetuneTrace.xml")
        if os.path.exists(rep):
            bad = [ln for ln in open(rep, errors="replace") if ln.startswith("failed ") or "[CDATA[failed " in ln]
            if bad:
                print(f"{len(bad)} clips failed, e.g. {bad[0].strip()[:300]}", file=sys.stderr)
        for it in batch:
            raw = os.path.join(tmp, it["id"] + ".f32")
            if os.path.exists(raw):
                os.remove(raw)
        print(f"batch {b // a.batch + 1}/{(len(todo) + a.batch - 1) // a.batch}: {len(batch)} clips in {time.time() - t0:.0f} s", flush=True)


if __name__ == "__main__":
    main()
