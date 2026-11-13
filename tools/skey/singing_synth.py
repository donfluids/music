"""The sung test tunes of KeyCatch's own unit tests, rendered to audio for the S-KEY evaluation.

Ports (same formulas, same java.util.Random seeds) of the synthesisers in
  analysis/src/test/kotlin/com/donfluids/music/dsp/SingerRobustnessTest.kt  (C major hymn, 8 singer types x 5 seeds)
  analysis/src/test/kotlin/com/donfluids/music/dsp/WanderingSingersTest.kt  (the steady case: 3 verses, 10 cents scatter)
  analysis/src/test/kotlin/com/donfluids/music/dsp/MelodyKeyTest.kt         (E major sharp/flat, C major vs A minor tunes)
The tests use no recorded voices: these synthetic voices (3-4 harmonics, vibrato, slides) are the
"singing cases". Expected keys are the ones the tests assert. Kotlin Float accumulation is
reproduced with float32 adds; small rounding differences from the JVM do not matter here.

usage: python -I singing_synth.py OUTDIR   -> OUTDIR/<name>.f32 (44.1 kHz float32 mono) + OUTDIR/singing.json
"""
import json
import math
import os
import sys

import numpy as np

RATE = 44100


class JavaRandom:
    """java.util.Random: 48-bit LCG, nextGaussian by the polar method with the cached second value."""

    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)
        self.have = False
        self.next_g = 0.0

    def _next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        r = self.seed >> (48 - bits)
        if r & (1 << (bits - 1)):
            r -= 1 << bits  # signed int
        return r

    def next_double(self):
        a = self._next(26) & ((1 << 26) - 1)
        b = self._next(27) & ((1 << 27) - 1)
        return ((a << 27) + b) * (1.0 / (1 << 53))

    def next_gaussian(self):
        if self.have:
            self.have = False
            return self.next_g
        while True:
            v1 = 2 * self.next_double() - 1
            v2 = 2 * self.next_double() - 1
            s = v1 * v1 + v2 * v2
            if 0 < s < 1:
                break
        m = math.sqrt(-2 * math.log(s) / s)
        self.next_g = v2 * m
        self.have = True
        return v1 * m


HYMN = [(55, 1), (60, 2), (64, 1), (60, 1), (64, 2), (62, 1), (60, 2), (57, 1), (55, 3),
        (55, 1), (60, 2), (64, 1), (60, 1), (64, 2), (62, 1), (67, 3), (64, 1), (67, 2), (64, 1), (60, 1),
        (64, 2), (62, 1), (60, 2), (57, 1), (55, 2), (55, 1), (60, 2), (64, 1), (60, 1), (64, 2), (62, 1), (60, 4)]
BEAT = 0.45


def sing_robust(err, glide_ms, drift, vibrato=25.0, scoop=0.0, seed=1):
    """SingerRobustnessTest.sing"""
    rnd = JavaRandom(seed)
    total = sum(b for _, b in HYMN) * BEAT + 1.0
    out = np.zeros(int(total * RATE), dtype=np.float32)
    t0 = 0.3
    phase = 0.0
    prev = float("nan")
    for midi, beats in HYMN:
        ln = beats * BEAT
        e = rnd.next_gaussian() * err
        n = int(ln * RATE)
        t = np.arange(n) / RATE
        at = t0 + t
        target = midi * 100.0 + e + drift * (at / total)
        g = glide_ms / 1000.0
        if not math.isnan(prev):
            m = t < g
            target = np.where(m, prev + (target - prev) * (t / g), target)
        if scoop > 0:
            target = np.where(t < 0.15, target - scoop * (1 - t / 0.15), target)
        vib = np.where(t > 0.25, vibrato * np.sin(2 * np.pi * 5.3 * t), 0.0)
        inc = 2 * np.pi * 440.0 * np.power(2.0, ((target + vib) / 100.0 - 69) / 12.0) / RATE
        ph = phase + np.cumsum(inc)
        phase = ph[-1]
        env = np.minimum(1.0, t / 0.03) * (np.minimum(1.0, (n - np.arange(n)) / RATE / 0.15) if beats >= 3 else 1.0)
        idx = (at * RATE).astype(int)
        ok = idx < len(out)
        v = (0.18 * env * (np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.3 * np.sin(3 * ph) + 0.15 * np.sin(4 * ph))).astype(np.float32)
        np.add.at(out, idx[ok], v[ok])
        prev = midi * 100.0 + e + drift * ((t0 + ln) / total)
        t0 += ln
    return out


def sing_wandering(level, err, seed=1, verses=3):
    """WanderingSingersTest.sing"""
    rnd = JavaRandom(seed)
    song = HYMN * verses
    total = verses * sum(b for _, b in HYMN) * BEAT + 1.0
    out = np.zeros(int(total * RATE), dtype=np.float32)
    t0 = 0.3
    phase = 0.0
    for midi, beats in song:
        ln = beats * BEAT
        e = rnd.next_gaussian() * err
        n = int(ln * RATE)
        t = np.arange(n) / RATE
        at = t0 + t
        vib = np.where(t > 0.25, 25.0 * np.sin(2 * np.pi * 5.3 * t), 0.0)
        inc = 2 * np.pi * 440.0 * np.power(2.0, ((midi * 100.0 + e + level(at) + vib) / 100.0 - 69) / 12.0) / RATE
        ph = phase + np.cumsum(inc)
        phase = ph[-1]
        env = np.minimum(1.0, t / 0.03) * (np.minimum(1.0, (n - np.arange(n)) / RATE / 0.15) if beats >= 3 else 1.0)
        idx = (at * RATE).astype(int)
        ok = idx < len(out)
        v = (0.18 * env * (np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.3 * np.sin(3 * ph))).astype(np.float32)
        np.add.at(out, idx[ok], v[ok])
        t0 += ln
    return out


def sing_melody(notes, offset, gap_after=()):
    """MelodyKeyTest.sing: each note slides in from a semitone below over 60 ms, vibrato, offset cents."""
    total = sum(d for _, d in notes) + len(gap_after) * 0.5 + 0.5
    out = np.zeros(int(total * RATE), dtype=np.float32)
    t0 = 0.0
    for k, (midi, ln) in enumerate(notes):
        f = 440.0 * 2 ** ((midi - 69 + offset / 100.0) / 12.0)
        n = int(ln * RATE)
        t = np.arange(n) / RATE
        slide = np.where(t < 0.06, np.power(2.0, -(1.0 - t / 0.06) / 12.0), 1.0)
        vib = 1.0 + 0.004 * np.sin(2 * np.pi * 5.5 * t)
        ph = np.cumsum(2 * np.pi * f * slide * vib / RATE)
        env = np.minimum(1.0, t / 0.02) * np.minimum(1.0, (n - np.arange(n)) / RATE / 0.04)
        start = int(t0 * RATE)
        v = (0.2 * env * (np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.25 * np.sin(3 * ph))).astype(np.float32)
        end = min(len(out), start + n)
        out[start:end] += v[: end - start]
        t0 += ln + (0.5 if k in gap_after else 0.0)
    return out


E_TUNE = [(63, 0.3), (64, 0.5), (68, 0.4), (66, 0.3), (64, 0.8), (69, 0.3), (68, 0.3), (66, 0.3), (71, 0.4), (68, 0.3),
          (66, 0.3), (64, 0.3), (63, 0.3), (64, 0.9)]
IN_C = [(60, .4), (62, .4), (64, .4), (65, .4), (67, .8), (69, .4), (67, .4), (65, .4), (64, .4), (62, .4), (60, 1.2)]
IN_A = [(69, .4), (71, .4), (72, .4), (74, .4), (76, .8), (74, .4), (72, .4), (71, .4), (68, .4), (71, .4), (69, 1.2)]

C_MAJOR, E_MAJOR, A_MINOR = 0, 4, 12 + 9


def cases():
    """(name, group, key, audio) in the tests' own terms."""
    singers = [
        ("good-clean", dict(err=0.0, glide_ms=40.0, drift=0.0)),
        ("good-slides", dict(err=15.0, glide_ms=80.0, drift=0.0)),
        ("good-long-slides", dict(err=10.0, glide_ms=250.0, drift=0.0)),
        ("good-scoops", dict(err=15.0, glide_ms=80.0, drift=0.0, scoop=150.0)),
        ("average-30c", dict(err=30.0, glide_ms=120.0, drift=0.0)),
        ("drift-80c-flat", dict(err=15.0, glide_ms=80.0, drift=-80.0)),
        ("poor-50c", dict(err=50.0, glide_ms=150.0, drift=0.0)),
        ("poor-drift-slides", dict(err=45.0, glide_ms=200.0, drift=-70.0, vibrato=40.0, scoop=100.0)),
    ]
    for name, kw in singers:
        for seed in (1, 2, 3, 4, 5):
            yield f"hymn_{name}_s{seed}", "hymn-" + name, C_MAJOR, sing_robust(seed=seed, **kw)
    yield "wander_steady", "wander-steady", C_MAJOR, sing_wandering(lambda t: 0.0, 10.0)
    yield "melody_E_sharp40", "melody-E", E_MAJOR, sing_melody(E_TUNE, 40.0, {4, 13})
    yield "melody_E_flat35", "melody-E", E_MAJOR, sing_melody(E_TUNE, -35.0, {4, 13})
    yield "melody_C_rest", "melody-relative", C_MAJOR, sing_melody(IN_C + IN_C, 0.0, {4, 10, 15})
    yield "melody_A_rest", "melody-relative", A_MINOR, sing_melody(IN_A + IN_A, 0.0, {4, 10, 15})


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    meta = []
    for name, group, key, audio in cases():
        path = os.path.join(out, name + ".f32")
        audio.astype("<f4").tofile(path)
        meta.append(dict(name=name, group=group, key=key, path=path, seconds=len(audio) / RATE))
    json.dump(meta, open(os.path.join(out, "singing.json"), "w"), indent=1)
    print(len(meta), "cases")


if __name__ == "__main__":
    main()
