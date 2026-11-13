"""Writes the reference data for KeyCatchCore's Beat This! tests (BeatThisTests.swift,
BeatThisCoreMLTests.swift) into ios/KeyCatchCore/Tests/KeyCatchCoreTests/Resources/.

    python -I make_fixtures.py --beat-this-src DIR --ckpt small0=small0.ckpt final0=final0.ckpt \
        --work WORK --guitarset-clip ID --waltz-clip ID --feel-clip ID --out DIR

WORK is live_eval.py's work folder (items.json, onsets/, bt/). Written:

  beat-this-frontend.json   log-mel of a synthetic 1 s signal (synth_signal), live convention
                            (silence before the start), frontend_np.py in float64
  beat-this-readings.json   two clips' live windows (logits stored as float16 in -logits.bin),
                            with what bt_live.Reader reads from each; Reader is checked here
                            against live_eval.bt_readouts first
  beat-this-feel.json       one GTZAN 12/8 song's onset signal and readings, with the per-beat
                            evidence and the decision of bt_live.Feel at each reading
  beat-this-coreml.json     reference logits of the official PyTorch models (FP32) for two
                            network inputs (float16 in -coreml-input.bin): a GuitarSet take
                            and the synthetic band (synth_band), and the live readings of the
                            whole Python pipeline on the synthetic band (soxr, float64 log-mel,
                            PyTorch, Reader) for the end-to-end check

synth_signal and synth_band have twins in the Swift tests (BeatThisFixtures.swift) that must
give the same samples. GuitarSet: Xi, Bittner, Pauwels, Ye and Bello, ISMIR 2018, CC BY 4.0.
"""
import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

from bt_live import BORDER, FPS, FRAMES, Feel, Reader, downbeat_at_beats, feel_energies, postprocess  # noqa: E402
from frontend_np import logmel  # noqa: E402

SR = 22050


class Lcg:
    """Numerical Recipes' 32-bit LCG, so Swift and Python draw the same noise."""

    def __init__(self, seed):
        self.s = seed

    def next(self):
        self.s = (1664525 * self.s + 1013904223) % 4294967296
        return self.s / 4294967296.0 * 2.0 - 1.0


def synth_signal(n):
    """1 s test signal at 22,050 Hz for the front end: two tones, a chirp, noise bursts."""
    rng = Lcg(1)
    x = np.zeros(n)
    for i in range(n):
        t = i / SR
        v = 0.3 * math.sin(2 * math.pi * 440.0 * t) + 0.1 * math.sin(2 * math.pi * 6000.0 * t)
        v += 0.2 * math.sin(2 * math.pi * (100.0 * t + 1500.0 * t * t))
        noise = rng.next()
        if (i // 2205) % 3 == 1:
            v += 0.25 * noise
        x[i] = v
    return x.astype(np.float32)


def midi_hz(m):
    return 440.0 * 2.0 ** ((m - 69) / 12.0)


def synth_band(rate=44100, seconds=12.0, bpm=100.0):
    """A loud 4/4 groove: kick on 1 and 3, snare on 2 and 4, eighth hi-hats, a bass note on
    every beat and a chord on every downbeat, the root changing each bar (C, A minor, F, G)."""
    rng = Lcg(7)
    n = int(seconds * rate)
    beat = 60.0 / bpm
    roots = [36, 33, 29, 31]
    x = np.zeros(n)
    prev = 0.0
    for i in range(n):
        t = i / rate
        b = int(math.floor(t / beat))
        tb = t - b * beat
        pos = b % 4
        bar = b // 4
        half = beat / 2.0
        te = t - math.floor(t / half) * half
        noise = rng.next()
        v = 0.0
        if pos == 0 or pos == 2:
            v += 0.8 * math.sin(2 * math.pi * (45.0 * tb + 120.0 * 0.03 * (1.0 - math.exp(-tb / 0.03)))) * math.exp(-tb / 0.2)
        else:
            v += (0.5 * noise + 0.3 * math.sin(2 * math.pi * 190.0 * tb)) * math.exp(-tb / 0.08)
        v += 0.15 * (noise - prev) * math.exp(-te / 0.015)
        prev = noise
        root = roots[bar % 4]
        f = midi_hz(root)
        v += 0.35 * (math.sin(2 * math.pi * f * tb) + 0.4 * math.sin(4 * math.pi * f * tb)) * math.exp(-tb / 0.5)
        if pos == 0:
            third = 3 if root == 33 else 4
            for m in (root + 24, root + 24 + third, root + 31):
                v += 0.08 * (math.sin(2 * math.pi * midi_hz(m) * tb) + 0.3 * math.sin(4 * math.pi * midi_hz(m) * tb)) * math.exp(-tb / 1.0)
        x[i] = 0.6 * v
    return x.astype(np.float32)


def live_logmel(x22):
    """Log-mel frames with silence before the start, as the app makes them: frame t centred on
    sample 441 t, only frames whose 1024 samples are all in."""
    padded = np.concatenate([np.zeros(512), np.asarray(x22, np.float64)])
    return logmel(padded, center=False)


def window(spect, last):
    win = np.zeros((FRAMES + 2 * BORDER, 128), np.float32)
    s0 = last - FRAMES + 1
    lo = max(0, s0)
    win[BORDER + (lo - s0): BORDER + FRAMES] = spect[lo: last + 1]
    return win, s0


def moments(n22, first=4):
    out = []
    m = first
    while m * SR <= n22:
        out.append(m)
        m += 1
    return out


def last_frame(m):
    return int(math.floor((m * SR - 512) / 441))


def to_f16(a):
    return np.asarray(a, np.float32).astype("<f2")


def readings_for(model, spect, ms, f16=True):
    """Runs the windows through the official model; returns the stored logits and the readings."""
    import torch
    reader = Reader()
    rows = []
    logits = []
    live = []
    for m in ms:
        win, s0 = window(spect, last_frame(m))
        with torch.inference_mode():
            o = model(torch.tensor(win[None]))
        bl = o["beat"][0, BORDER: BORDER + FRAMES].numpy()
        dl = o["downbeat"][0, BORDER: BORDER + FRAMES].numpy()
        if f16:
            bl = to_f16(bl).astype(np.float32)
            dl = to_f16(dl).astype(np.float32)
        logits.append(np.stack([bl, dl]))
        r = reader.read(float(m), s0, bl, dl)
        b_local, d_local = postprocess(bl, dl)
        live.append(dict(t=float(m), b=[float(v) + s0 / FPS for v in b_local], d=[float(v) + s0 / FPS for v in d_local],
                         dp=downbeat_at_beats(b_local, dl)))
        r["s0"] = s0
        rows.append(r)
    return rows, np.stack(logits), live


def check_against_live_eval(rows, live):
    from live_eval import bt_readouts
    ref = bt_readouts(live)
    for r, (_, _, bpm, bpb, last, period, _) in zip(rows, ref):
        for a, b in ((r["bpm"], bpm), (r["last"], last), (r["period"], period)):
            assert (a is None and b is None) or abs(a - b) < 1e-12, (r["t"], a, b)
        assert r["bpb"] == bpb, (r["t"], r["bpb"], bpb)


def clean(r):
    keep = ("t", "s0", "beats", "downbeats", "bpm", "period", "last", "bpb", "index", "locked", "strength")
    out = {k: r[k] for k in keep}
    out["scores"] = {str(k): v for k, v in r["scores"].items()}
    return out


def main():
    import torch
    from bt_common import load_model
    from live_eval import load_audio, load_items, bt_readouts
    ap = argparse.ArgumentParser()
    ap.add_argument("--beat-this-src", required=True)
    ap.add_argument("--ckpt", nargs="+", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--guitarset-clip", required=True)
    ap.add_argument("--waltz-clip", required=True)
    ap.add_argument("--feel-clip", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    torch.set_num_threads(4)
    models = {k: load_model(a.beat_this_src, v).eval() for k, v in (c.split("=", 1) for c in a.ckpt)}
    items = {i["id"]: i for i in load_items(a.work)}
    os.makedirs(a.out, exist_ok=True)

    # Front end.
    sig = synth_signal(22050)
    mel = live_logmel(sig.astype(np.float64))
    json.dump(dict(samples=len(sig), frames=mel.astype(float).round(6).tolist()), open(os.path.join(a.out, "beat-this-frontend.json"), "w"))
    print("front end:", mel.shape)

    # Readings on two clips' logits.
    clips = []
    blobs = []
    for cid, ck in ((a.guitarset_clip, "final0"), (a.waltz_clip, "small0")):
        x = load_audio(items[cid]["audio"], SR)[: 20 * SR]
        spect = live_logmel(x)
        ms = moments(len(x))[:16]
        rows, logits, live = readings_for(models[ck], spect, ms)
        check_against_live_eval(rows, live)
        blobs.append(to_f16(logits))
        clips.append(dict(id=cid, model=ck, readings=[clean(r) for r in rows]))
        print(cid, ck, [(r["t"], None if r["bpm"] is None else round(r["bpm"], 1), r["bpb"], r["index"]) for r in rows])
    np.concatenate([b.reshape(-1) for b in blobs]).tofile(os.path.join(a.out, "beat-this-readings-logits.bin"))
    json.dump(dict(frames=FRAMES, clips=clips), open(os.path.join(a.out, "beat-this-readings.json"), "w"))

    # Simple or compound on one 12/8 song: stored readings of live_eval (small0, 8 s).
    it = items[a.feel_clip]
    live = json.load(open(os.path.join(a.work, "bt", "small0", it["id"] + ".json")))["live_8"]
    # Rounded to 4 decimals (the file stays small); the reference is computed from these values.
    onsets = np.round(np.fromfile(os.path.join(a.work, "onsets", it["id"] + ".f32"), dtype="<f4").astype(float), 4).astype(np.float32)
    feel = Feel()
    rows = []
    for m, _, bpm, _, last, period, _ in bt_readouts(live):
        row = dict(t=m, bpm=bpm, last=last, period=period, energies=None)
        if bpm is not None:
            e, pf = feel_energies(onsets, 44100, 512, m, last, period)
            if e is not None:
                feel.update(e, pf)
                row["energies"] = [float(v) for v in e]
                row["periodFrames"] = pf
        row["compound"] = feel.compound
        rows.append(row)
    print("feel:", a.feel_clip, sum(r["compound"] for r in rows), "of", len(rows))
    json.dump(dict(id=a.feel_clip, rate=44100, hop=512, onsets=[round(float(v), 4) for v in onsets], readings=rows),
              open(os.path.join(a.out, "beat-this-feel.json"), "w"))

    # Core ML: two network inputs and the official FP32 logits for them; the end-to-end readings.
    import soxr
    gx = load_audio(items[a.guitarset_clip]["audio"], SR)
    g_in, _ = window(live_logmel(gx), last_frame(12))
    band = synth_band()
    bx = soxr.resample(band.astype(np.float64), 44100, SR)
    bspect = live_logmel(bx)
    b_in, _ = window(bspect, last_frame(10))
    inputs = [to_f16(g_in), to_f16(b_in)]
    np.concatenate([v.reshape(-1) for v in inputs]).tofile(os.path.join(a.out, "beat-this-coreml-input.bin"))
    ref = {}
    for ck, model in models.items():
        outs = []
        for v in inputs:
            with torch.inference_mode():
                o = model(torch.tensor(v.astype(np.float32)[None]))
            outs.append(dict(beat=o["beat"][0].numpy().astype(float).round(5).tolist(),
                             downbeat=o["downbeat"][0].numpy().astype(float).round(5).tolist()))
        ref[ck] = outs
    e2e = {}
    for ck, model in models.items():
        rows, _, live = readings_for(model, bspect, moments(len(bx)), f16=False)
        check_against_live_eval(rows, live)
        e2e[ck] = [clean(r) for r in rows]
        print("e2e", ck, [(r["t"], None if r["bpm"] is None else round(r["bpm"], 2), r["bpb"], r["index"], None if r["last"] is None else round(r["last"], 3)) for r in rows])
    json.dump(dict(inputs=["guitarset " + a.guitarset_clip + " at 12 s", "synthetic band at 10 s"], logits=ref, e2e=e2e,
                   bandPeak=float(np.abs(band).max()), bandSumSquares=float(np.sum(band.astype(np.float64) ** 2))),
              open(os.path.join(a.out, "beat-this-coreml.json"), "w"))


if __name__ == "__main__":
    main()
