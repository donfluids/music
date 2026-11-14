"""Offline check of tuning estimators on the notes the app segmented (DetuneTrace "final" lines):
for RS200 sung tunes at a detune, how far each estimate of where the singer sits is from the
detune, using the notes up to 14, 30 and 60 s. "circ" is the plain circular mean of the notes'
distance from the nearest semitone (no shrinkage, no memory); "scaleNN" is one held level fitted
to a seven-note scale with NN cents of scatter. (The "wrong side" column is not reliable; use the
|err| > 11 column.)

usage: python -I dt_estimators.py TRACE_DIR(with <id>.jsonl) rs200_truth.json average,good 0,39,45
"""
import json, math, sys, statistics
import numpy as np
OUT = sys.argv[1]
tr = json.load(open(sys.argv[2]))
MAJ = [0, 2, 4, 5, 7, 9, 11]
def wrap(c): return (c + 50) % 100 - 50
def scalefit(c, w, sigma, oos=0.05, rng=np.arange(-49, 51, 1.0)):
    # constant level: max over 12 tonics and in-semitone offset g of sum w*log(exp(-d^2/2s^2)+oos)
    best, bg = -1e18, 0
    for g in rng:
        for t in range(12):
            rel = (c - g - 100 * t) % 1200
            d = np.min([np.abs(((rel - 100 * k) + 600) % 1200 - 600) for k in MAJ], axis=0)
            s = np.sum(w * np.log(np.exp(-d * d / (2 * sigma * sigma)) + oos))
            if s > best: best, bg = s, g
    return bg
def circ(c, w):
    a = 2 * math.pi * c / 100
    return math.atan2(np.sum(w * np.sin(a)), np.sum(w * np.cos(a))) * 100 / (2 * math.pi)
res = {}
for sg in sys.argv[3].split(","):
    for det in [int(x) for x in sys.argv[4].split(",")]:
        tag = "" if det == 0 else (f"_d{det}" if det > 0 else f"_dm{-det}")
        errs = {}
        for base in tr:
            if not base.endswith(sg): continue
            L = [json.loads(l) for l in open(f"{OUT}/{base}{tag}.jsonl")]
            fin = [r for r in L if "final" in r][0]["final"]
            for T in (14, 30, 60):
                ns = [n for n in fin if n[1] < T]
                if len(ns) < 16: continue
                c = np.array([n[0] * 100 for n in ns]); w = np.array([min(max(n[2], .1), 1.5) for n in ns])
                for name, est in (("circ", circ(c, w)), ("scale15", scalefit(c, w, 15)), ("scale20", scalefit(c, w, 20)), ("scale30", scalefit(c, w, 30))):
                    errs.setdefault((name, T), []).append(wrap(est - det))
        for (name, T), e in sorted(errs.items()):
            e = np.array(e)
            print(f"{sg:7s} {det:+3d} {name:8s} t<{T:2d}s n={len(e):2d} median err {np.median(e):+5.1f}  |err|>11: {100*np.mean(np.abs(e)>11):4.0f}%  wrong side (would name the wrong key): {100*np.mean(np.abs(wrap(e + det)) > 50 - 1e-9) if det else 100*np.mean(np.abs(e)>=50):3.0f}%", flush=True)
