"""Compares logits (beat 412 then downbeat 412, float32 LE) against the PyTorch reference.

    python -I compare.py --beat-this-src DIR REF.f32 TEST.f32 [TEST2.f32 ...]

Prints the largest absolute logit difference, how many logits change sign, and whether the
official minimal postprocessing (on the 400 frames without the 6 border frames each side, as
the app uses them) picks the same beats and downbeats.
"""
import argparse
import sys
from pathlib import Path

import numpy as np

B = 6


def picks(pp, logits):
    import torch
    n = logits.size // 2
    beat = torch.from_numpy(logits[:n][B:n - B].copy())
    down = torch.from_numpy(logits[n:][B:n - B].copy())
    b, d = pp(beat, down)
    return np.asarray(b), np.asarray(d)


def compare(pp, ref, test):
    diff = np.abs(ref - test)
    sign = int(np.sum(np.sign(ref) != np.sign(test)))
    rb, rd = picks(pp, ref)
    tb, td = picks(pp, test)
    same_b = rb.shape == tb.shape and np.allclose(rb, tb)
    same_d = rd.shape == td.shape and np.allclose(rd, td)
    return dict(max=float(diff.max()), mean=float(diff.mean()), sign=sign, n=ref.size,
                beats=(len(rb), len(tb)), downs=(len(rd), len(td)), same_beats=same_b, same_downs=same_d)


def fmt(r):
    return (f"max |diff| {r['max']:.2e} (mean {r['mean']:.1e}), sign changes {r['sign']}/{r['n']}, "
            f"beats {r['beats'][0]}->{r['beats'][1]} {'same' if r['same_beats'] else 'DIFFERENT'}, "
            f"downbeats {r['downs'][0]}->{r['downs'][1]} {'same' if r['same_downs'] else 'DIFFERENT'}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--beat-this-src", required=True)
    ap.add_argument("ref")
    ap.add_argument("tests", nargs="+")
    a = ap.parse_args()
    sys.path.insert(0, str(Path(a.beat_this_src).resolve()))
    from beat_this.model.postprocessor import Postprocessor
    pp = Postprocessor("minimal", 50)
    ref = np.fromfile(a.ref, dtype="<f4")
    for t in a.tests:
        print(f"{Path(t).name}: {fmt(compare(pp, ref, np.fromfile(t, dtype='<f4')))}")


if __name__ == "__main__":
    main()
