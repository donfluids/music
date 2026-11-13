"""Prototype: keep KeyCatch's simple/compound decision when the beats come from Beat This!.

Beat This! gives beats and downbeats but never says whether a beat divides in two or three.
KeyCatch's MeterDetector.isCompound answers that from the onset signal: energy a third and
two thirds of the way between beats against energy halfway. This script ports that check
(same smoothing, detrending and windows as TempoTracker/MeterDetector in KeyCatchCore) and
runs it with the *Beat This!* beat period and newest beat at every live moment, on the onset
signal of KeyCatchCore's own OnsetDetector (dumped by the `kconsets` tool, Band preset).

Ground truth: GTZAN-Rhythm v2 (Marchand & Peeters 2015) marks 31 GTZAN tracks as ternary
(27 in 12/8, 4 in 9/8). Reported: share of live moments called compound, on ternary clips
(should be high) and on the other clips of the random sample (should be low), for the current
Swift tracker and for the Beat This!-driven check.

    python -I compound_proto.py --work WORK --ckpts small0 final0 --window 8
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

from live_eval import bt_readouts, load_items, read_swift  # noqa: E402

RATE = 44100
HOP = 512
FPS = RATE / HOP
CAPACITY = int(np.floor(8.0 * FPS + 0.5))  # TempoTracker history, 8 s
TREND = max(1, int(np.floor(0.75 * FPS + 0.5)))


def kround(x):
    return int(np.floor(x + 0.5))


def detrend(x):
    """TempoTracker.estimate(): 1-2-1 smoothing, then minus a +-0.75 s moving average."""
    n = len(x)
    lin = 0.25 * x[np.maximum(0, np.arange(n) - 1)] + 0.5 * x + 0.25 * x[np.minimum(n - 1, np.arange(n) + 1)]
    c = np.concatenate([[0.0], np.cumsum(lin, dtype=np.float64)])
    lo = np.maximum(0, np.arange(n) - TREND)
    hi = np.minimum(n - 1, np.arange(n) + TREND)
    return lin - (c[hi + 1] - c[lo]) / (hi - lo + 1)


def window_max(x, center, half):
    lo = max(0, kround(center - half))
    hi = min(len(x) - 1, kround(center + half))
    if lo > hi:
        return 0.0
    return max(0.0, float(x[lo: hi + 1].max()))


def is_compound(onset, newest, period):
    """MeterDetector.isCompound, line for line."""
    sub = period / 20.0
    half = first = second = beat = 0.0
    t = newest
    while t - period >= 0:
        start = t - period
        beat += window_max(onset, start, period / 8.0)
        half += window_max(onset, start + period / 2.0, sub)
        first += window_max(onset, start + period / 3.0, sub)
        second += window_max(onset, start + 2.0 * period / 3.0, sub)
        t -= period
    third = 0.5 * (first + second)
    both = min(first, second) > 0.35 * max(first, second)
    return both and third > 1.3 * half and third > 0.25 * beat


def bt_compound(onsets, readouts):
    out = []
    for m, _now, bpm, _bpb, last, period, _ in readouts:
        if bpm is None or last is None or period is None:
            out.append(False)
            continue
        h_end = int(np.floor(m * RATE / HOP)) - 1  # newest complete hop
        h0 = max(0, h_end - CAPACITY + 1)
        hist = detrend(onsets[h0: h_end + 1].astype(np.float64))
        newest = last * RATE / HOP - 1 - h0
        pf = period * RATE / HOP
        if pf < 4.0 or len(hist) < 3 * FPS or newest < 0:
            out.append(False)
            continue
        out.append(bool(is_compound(hist, newest, pf)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--ckpts", nargs="+", default=["small0", "final0"])
    ap.add_argument("--window", type=float, default=8)
    a = ap.parse_args()
    items = [i for i in load_items(a.work) if i["dataset"] == "gtzan"]
    tern = [i for i in items if i.get("ternary")]
    other = [i for i in items if i.get("random_sample") and not i.get("ternary")]
    print(f"GTZAN: {len(tern)} ternary clips (12/8, 9/8), {len(other)} non-ternary clips of the random sample\n")
    print("| compound decision from | share of moments called compound: ternary clips | non-ternary clips | clips called compound by majority of moments: ternary | non-ternary |")
    print("|---|---|---|---|---|")
    systems = [("KeyCatch Swift (band), MeterDetector as shipped", None)] + [
        (f"isCompound on Beat This {c} beats, W={a.window:g} s", c) for c in a.ckpts]
    for label, ck in systems:
        res = {}
        for group, its in (("t", tern), ("o", other)):
            mom = []
            clip = []
            for it in its:
                if ck is None:
                    ms, _, _ = read_swift(os.path.join(a.work, "swift", "band", it["id"] + ".jsonl"))
                    flags = [bool(d["compound"]) for d in ms]
                else:
                    p = os.path.join(a.work, "bt", ck, it["id"] + ".json")
                    if not os.path.exists(p):
                        continue
                    live = json.load(open(p))[f"live_{a.window:g}"]
                    onsets = np.fromfile(os.path.join(a.work, "onsets", it["id"] + ".f32"), dtype="<f4")
                    flags = bt_compound(onsets, bt_readouts(live))
                if flags:
                    mom.extend(flags)
                    clip.append(np.mean(flags) > 0.5)
            res[group] = (100 * np.mean(mom) if mom else float("nan"), sum(clip), len(clip))
        t, o = res["t"], res["o"]
        print(f"| {label} | {t[0]:.1f}% | {o[0]:.1f}% | {t[1]}/{t[2]} | {o[1]}/{o[2]} |")


if __name__ == "__main__":
    main()
