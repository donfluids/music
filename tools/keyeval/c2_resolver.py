"""C2: RelativeKeyResolver's hand-set evidence weights refitted by logistic regression,
cross-validated; only held-out numbers are reported.

The app (scratch copy) wrote, at every key update of variant 0, the pair the finder chose and
the resolver's evidence terms (endings, leading tone, approaches, chords, profile; prior 0.25)
plus the threshold scale and, for Guitar, whether the chord estimate was on screen and its key
(work/<kv>/evidence/<id>.csv). Here the app's RelativeModeTracker (threshold 1.5 x scale,
hysteresis at a third, lean dead band 0.5) and, for Guitar, the chord override are replayed on
those terms with new weights; "app" (all weights 1, bias 0.25) must reproduce the app's own
readings, and the agreement is printed.

Fit: one sample per reading (the evidence at that moment) whose true key is in the chosen pair;
y = major; L2-regularised logistic regression (lambda 0.01 on the weights, not the bias), IRLS.
Two variants, fixed before scoring: "C2" (the training data's own major/minor mix decides the
bias) and "C2-bal" (classes weighted equally). Folds: 5, by song (Voices: CSD song, the test
tune, RS200 song, GTSinger song; the +39 c copy goes with its original) or by GuitarSet
progression; the fold of a group is fixed by an md5 of its name.

usage: python -I c2_resolver.py --mode voices|guitar --items ITEMS.json [..] --kv KV_DIR
          --datasets D [D ..] [--skey-work SKEY_WORK] [--json OUT.json]
"""
import argparse
import hashlib
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

from keyeval_common import Tally, load_moments, moments_for, read_jsonl, table  # noqa: E402

FEATS = ["endings", "leadingTone", "approaches", "chords", "profile"]
THRESHOLD = 1.5
LEAN_BAND = 0.5


def pair(k):
    major = k if k < 12 else (k % 12 + 3) % 12
    return major, (major + 9) % 12 + 12


class Tracker:
    """RelativeModeTracker (RelativeKeyResolver.kt)."""

    def __init__(self):
        self.decided = None
        self.pair_major = None
        self.lean = None

    def update(self, key, score, th):
        M, m = pair(key)
        if M != self.pair_major:
            self.pair_major, self.decided, self.lean = M, None, None
        if score > th:
            d = M
        elif score < -th:
            d = m
        elif self.decided == M and score > th / 3:
            d = M
        elif self.decided == m and score < -th / 3:
            d = m
        else:
            d = None
        self.decided = d
        if d is not None:
            self.lean = d
        elif self.lean is None:
            self.lean = M if score >= 0 else m
        elif self.lean == M and score < -LEAN_BAND:
            self.lean = m
        elif self.lean == m and score > LEAN_BAND:
            self.lean = M
        return self.lean


def group_of(it):
    d = it["dataset"]
    if d.startswith("guitarset"):
        return it["id"].split("_")[2].split("-")[0]
    if d == "csd":
        return it["id"][:-1]
    if d == "singing":
        return "singing:" + it["group"]
    return d.split("_")[0] + ":" + it.get("song", it["group"])


def fold_of(group, k=5):
    return int(hashlib.md5(group.encode()).hexdigest(), 16) % k


def load_clip(kv, it, skey_work):
    ev = os.path.join(kv, "evidence", it["id"] + ".csv")
    js = os.path.join(kv, "app_app", it["id"] + ".jsonl")
    if not (os.path.exists(ev) and os.path.exists(js)):
        return None
    a = np.loadtxt(ev, delimiter=",", ndmin=2)
    rows = read_jsonl(js)
    done = [json.loads(line) for line in open(js) if '"done"' in line][0]
    moments = (load_moments(skey_work, it["id"]) if skey_work else None) or moments_for(done["seconds"], it.get("final", False))
    reads = []
    for m in moments:
        r = rows.get(m)
        if r is None:
            reads.append((m, None, -1))
            continue
        idx = int(np.searchsorted(a[:, 0], r["t"] + 1e-6)) - 1  # last update at or before the readout
        reads.append((m, idx, r["display"]))
    return dict(t=a[:, 0], found=a[:, 1].astype(int), X=a[:, 2:7], prior=a[:, 7], scale=a[:, 8],
                fromChords=a[:, 9].astype(int), songKey=a[:, 10].astype(int), reads=reads, key=it["key"])


def replay(c, w, b, mode):
    """Shown key at each reading with weights w (5) and bias b."""
    tr = Tracker()
    score = c["X"] @ w + b
    shown = np.empty(len(score), dtype=int)
    for i in range(len(score)):
        p = tr.update(int(c["found"][i]), float(score[i]), THRESHOLD * float(c["scale"][i]))
        if mode == "guitar" and c["fromChords"][i] and c["songKey"][i] >= 0 and pair(int(c["songKey"][i])) != pair(p):
            p = int(c["songKey"][i])
        shown[i] = p
    return [(-1 if idx is None or idx < 0 else int(shown[idx])) for _, idx, _ in c["reads"]]


def fit(X, y, sw, lam=0.01, iters=50):
    Xb = np.hstack([X, np.ones((len(X), 1))])
    beta = np.zeros(Xb.shape[1])
    reg = np.full(Xb.shape[1], lam)
    reg[-1] = 0.0
    for _ in range(iters):
        z = np.clip(Xb @ beta, -30, 30)
        p = 1 / (1 + np.exp(-z))
        g = Xb.T @ (sw * (p - y)) + reg * beta
        H = (Xb * (sw * p * (1 - p))[:, None]).T @ Xb + np.diag(reg + 1e-9)
        step = np.linalg.solve(H, g)
        beta -= step
        if np.abs(step).max() < 1e-8:
            break
    return beta[:-1], beta[-1]


def samples(clips):
    X, y = [], []
    for c in clips:
        M, m = None, None
        for _, idx, _ in c["reads"]:
            if idx is None or idx < 0:
                continue
            M, m = pair(int(c["found"][idx]))
            if c["key"] not in (M, m):
                continue
            X.append(c["X"][idx])
            y.append(1.0 if c["key"] == M else 0.0)
    return np.array(X), np.array(y)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", required=True, choices=["voices", "guitar"])
    ap.add_argument("--items", nargs="+", required=True)
    ap.add_argument("--kv", required=True)
    ap.add_argument("--datasets", nargs="+", required=True)
    ap.add_argument("--skey-work")
    ap.add_argument("--json")
    a = ap.parse_args()
    items = []
    for f in a.items:
        items += [i for i in json.load(open(f)) if i["dataset"] in a.datasets]
    clips = {}
    for it in items:
        c = load_clip(a.kv, it, a.skey_work)
        if c is not None:
            c["item"] = it
            clips[it["id"]] = c
    print(len(clips), "clips with evidence", file=sys.stderr)
    w0, b0 = np.ones(5), 0.25
    agree = n = 0
    for c in clips.values():
        for (m, idx, disp), est in zip(c["reads"], replay(c, w0, b0, a.mode)):
            n += 1
            agree += int(disp == est)
    print(f"replay with the app's weights reproduces the app's reading in {agree}/{n} readings ({100 * agree / max(n, 1):.1f} %)")

    est = defaultdict(dict)
    coefs = defaultdict(list)
    for c in clips.values():
        est["app (replayed)"][c["item"]["id"]] = replay(c, w0, b0, a.mode)
    for k in range(5):
        train = [c for c in clips.values() if fold_of(group_of(c["item"])) != k]
        test = [c for c in clips.values() if fold_of(group_of(c["item"])) == k]
        X, y = samples(train)
        if a.mode == "guitar":  # no sung notes: endings and approaches are always 0
            X = X.copy()
            X[:, [0, 2]] = 0.0
        for name, bal in (("C2", False), ("C2-bal", True)):
            sw = np.ones(len(y))
            if bal:
                sw = np.where(y > 0.5, 0.5 / max(y.mean(), 1e-9), 0.5 / max(1 - y.mean(), 1e-9))
            w, b = fit(X, y, sw)
            coefs[name].append([round(float(x), 3) for x in w] + [round(float(b), 3)])
            for c in test:
                est[name][c["item"]["id"]] = replay(c, w, b, a.mode)
    sets = sorted({c["item"]["dataset"] for c in clips.values()})
    res = {}
    rows = []
    for name, per in est.items():
        row = [name]
        for d in sets:
            for sub in ("", ":major", ":minor"):
                ty = Tally()
                for iid, e in per.items():
                    it = clips[iid]["item"]
                    if it["dataset"] != d or (sub == ":major" and it["key"] >= 12) or (sub == ":minor" and it["key"] < 12):
                        continue
                    for x in e:
                        ty.add(x, it["key"], iid)
                res[f"{d}{sub}|{name}"] = dict(exact=ty.exact, rel=ty.rel, mirex=ty.mirex, n=ty.n, clips=len(ty.clips))
                if sub == "":
                    row.append(f"{ty.exact:.1f} / {ty.rel:.1f}")
        rows.append(row)
    print(table(["method (exact % / relative %)"] + sets, rows))
    for name, cs in coefs.items():
        print(f"{name} weights per fold ({', '.join(FEATS)}, bias):")
        for cc in cs:
            print("   ", cc)
    if a.json:
        json.dump(dict(scores=res, coefs=coefs, agreement=agree / max(n, 1)), open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
