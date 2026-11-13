"""Sung test tunes from the RS200 rock corpus melodies (de Clercq & Temperley, CC BY 4.0,
rockcorpus.midside.com: melodic_transcriptions/rs200_melody_nlt.zip and
harmonic_analyses/rs200_harmony_clt.zip), rendered with the same kind of synthetic voice as
tools/skey/singing_synth.py (KeyCatch's own test singers: 4 harmonics, vibrato, glides, pitch
scatter).

Labels (fixed before any scoring; derived, not annotated):
  * one key for the whole song: every chord in the harmonic analysis by the melody's own
    transcriber has the same key tonic, and every melody note's pitch minus its scale degree
    gives that tonic;
  * minor if at least 2/3 of the tonic-chord time is a minor tonic chord (Roman numeral 'i...')
    and the melody's b3 time is at least twice its natural-3 time; major if both hold the other
    way round (I..., 3 over b3); otherwise left out.
Excerpt: the first 60 s of melody (from its first note); notes last min(0.92 x inter-onset
interval, one bar); tunes with under 25 s of melody are left out. Two singers per tune:
"good" (15 cents scatter, 80 ms glides) and "average" (30 cents, 120 ms); seeds fixed per tune.

usage: python -I rs200_synth.py MELODY_NLT.zip HARMONY_CLT.zip OUTDIR [--max-major N]
       -> OUTDIR/<name>.flac (44.1 kHz, 24-bit mono) + OUTDIR/rs200.json (also the left-out songs and why)
"""
import io
import json
import math
import os
import sys
import zipfile
from collections import defaultdict

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "skey"))  # singing_synth.py from tools/skey
from singing_synth import JavaRandom  # noqa: E402

RATE = 44100
EXCERPT = 60.0


def read_zip(path, ext):
    z = zipfile.ZipFile(path)
    out = {}
    for n in z.namelist():
        if n.startswith("__") or not n.endswith(ext):
            continue
        rows = []
        for line in z.read(n).decode("utf-8", "replace").splitlines():
            r = line.split()
            try:  # numeric rows only ("End" markers and the odd error message are skipped)
                float(r[0])
                if ext == ".nlt":
                    r = [r[0], r[1], str(int(r[2])), str(int(r[3]))]
                elif len(r) < 7:
                    continue
            except (ValueError, IndexError):
                continue
            rows.append(r)
        out[os.path.basename(n)[: -len(ext)]] = rows
    return out


def label(mel, harm):
    harm = [r for r in harm if len(r) >= 7]
    tonics = {int(r[5]) for r in harm if len(r) >= 7}
    if len(tonics) != 1:
        return None, f"{len(tonics)} keys in the harmony"
    tonic = tonics.pop()
    mt = {(int(r[2]) - int(r[3])) % 12 for r in mel}
    if mt != {tonic}:
        return None, "melody tonic differs or modulates"
    t_harm = [float(r[0]) for r in harm]
    tmaj = tmin = 0.0
    for i, r in enumerate(harm):
        d = (t_harm[i + 1] - t_harm[i]) if i + 1 < len(harm) else 2.0
        if int(r[3]) == 0:  # chromatic root relative to the key = tonic
            if r[2].startswith("i") or r[2].startswith("bi"):
                tmin += d
            elif r[2].startswith("I"):
                tmaj += d
    on = [float(r[0]) for r in mel]
    d3 = d4 = 0.0
    for i, r in enumerate(mel):
        ioi = (on[i + 1] - on[i]) if i + 1 < len(mel) else 0.5
        deg = int(r[3]) % 12
        if deg == 3:
            d3 += min(ioi, 2.0)
        elif deg == 4:
            d4 += min(ioi, 2.0)
    tot = tmaj + tmin
    if tot <= 0:
        return None, "no tonic chord"
    if tmin >= 2 / 3 * tot and d3 >= 2 * d4 and d3 > 0:
        return tonic + 12, None
    if tmaj >= 2 / 3 * tot and d4 >= 2 * d3 and d4 > 0:
        return tonic, None
    return None, f"mode unclear: minor tonic share {tmin / tot:.2f}, b3 {d3:.1f}s vs 3 {d4:.1f}s"


def notes_of(mel):
    on = [float(r[0]) for r in mel]
    bars = [float(r[1]) for r in mel]
    start = on[0]
    out = []
    for i, r in enumerate(mel):
        if on[i] - start > EXCERPT:
            break
        if i + 1 < len(mel):
            ioi = on[i + 1] - on[i]
            db = bars[i + 1] - bars[i]
            bar_sec = ioi / db if db > 1e-6 else 2.0
            d = min(0.92 * ioi, bar_sec)
        else:
            d = 0.8
        out.append((on[i] - start + 0.3, max(0.06, d), int(r[2])))
    return out


def sing(notes, err, glide_ms, seed, vibrato=25.0):
    """The SingerRobustnessTest voice (singing_synth.sing_robust) on arbitrary timed notes."""
    rnd = JavaRandom(seed)
    total = notes[-1][0] + notes[-1][1] + 1.0
    out = np.zeros(int(total * RATE), dtype=np.float32)
    phase = 0.0
    prev = float("nan")
    prev_end = -1.0
    for t0, ln, midi in notes:
        e = rnd.next_gaussian() * err
        n = int(ln * RATE)
        t = np.arange(n) / RATE
        target = np.full(n, midi * 100.0 + e)
        g = glide_ms / 1000.0
        if not math.isnan(prev) and t0 - prev_end < 0.05:  # glide only within a legato line
            m = t < g
            target = np.where(m, prev + (target - prev) * (t / g), target)
        vib = np.where(t > 0.25, vibrato * np.sin(2 * np.pi * 5.3 * t), 0.0)
        inc = 2 * np.pi * 440.0 * np.power(2.0, ((target + vib) / 100.0 - 69) / 12.0) / RATE
        ph = phase + np.cumsum(inc)
        phase = ph[-1]
        env = np.minimum(1.0, t / 0.03) * np.minimum(1.0, (n - np.arange(n)) / RATE / 0.05)
        idx = ((t0 + t) * RATE).astype(int)
        ok = idx < len(out)
        v = (0.18 * env * (np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.3 * np.sin(3 * ph) + 0.15 * np.sin(4 * ph))).astype(np.float32)
        np.add.at(out, idx[ok], v[ok])
        prev = midi * 100.0 + e
        prev_end = t0 + ln
    return out


def main():
    mel_zip, harm_zip, out = sys.argv[1], sys.argv[2], sys.argv[3]
    max_major = int(sys.argv[sys.argv.index("--max-major") + 1]) if "--max-major" in sys.argv else 10 ** 9
    mels = read_zip(mel_zip, ".nlt")
    harms = read_zip(harm_zip, ".clt")
    os.makedirs(out, exist_ok=True)
    meta, left = [], {}
    labelled = []
    for name in sorted(mels):
        mel = [r for r in mels[name] if len(r) >= 4]
        harm = harms.get(name)
        if not mel or harm is None:
            left[name] = "no harmony by the same author"
            continue
        key, why = label(mel, harm)
        if key is None:
            left[name] = why
            continue
        notes = notes_of(mel)
        if notes[-1][0] + notes[-1][1] < 25.0:
            left[name] = "under 25 s of melody"
            continue
        labelled.append((name, key, notes))
    majors = [x for x in labelled if x[1] < 12][:max_major]
    keep = [x for x in labelled if x[1] >= 12] + majors
    for si, (name, key, notes) in enumerate(sorted(keep)):
        for singer, err, glide in (("good", 15.0, 80.0), ("average", 30.0, 120.0)):
            audio = sing(notes, err, glide, seed=1000 + si)
            clip = f"{name}_{singer}"
            path = os.path.join(out, clip + ".flac")
            import soundfile as sf
            sf.write(path, audio, RATE, format="FLAC", subtype="PCM_24")
            meta.append(dict(name=clip, song=name, singer=singer, key=key, path=path, seconds=len(audio) / RATE))
    json.dump(dict(clips=meta, left_out=left, labelled_songs={n: k for n, k, _ in labelled}), open(os.path.join(out, "rs200.json"), "w"), indent=1)
    nmin = sum(1 for _, k, _ in labelled if k >= 12)
    print(len(mels), "melodies;", len(labelled), "labelled:", nmin, "minor,", len(labelled) - nmin, "major;",
          len(keep), "rendered x 2 singers =", len(meta), "clips")


if __name__ == "__main__":
    main()
