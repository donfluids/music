"""How much does the 44.1/48 kHz -> 22.05 kHz resampler matter?

Compares the official soxr (HQ) resampling with scipy's polyphase resample_poly (Kaiser
window, what a phone would do with a fixed FIR) on the log-mel input and on the model's
logits and picked beats.

    python -I check_resampling.py --beat-this-src DIR --ckpt small0.ckpt a.wav b.wav ...
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
import soundfile as sf  # noqa: E402
import soxr  # noqa: E402
import torch  # noqa: E402
from scipy.signal import resample_poly  # noqa: E402

from bt_common import import_beat_this, load_model  # noqa: E402
from frontend_np import logmel  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--beat-this-src", required=True)
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("audio", nargs="+")
    a = ap.parse_args()
    inf, pp, _ = import_beat_this(a.beat_this_src)
    post = pp.Postprocessor("minimal")
    model = load_model(a.beat_this_src, a.ckpt)

    def run(x):
        s = torch.tensor(logmel(x))
        with torch.inference_mode():
            p = inf.split_predict_aggregate(s, 1500, 6, "keep_first", model)
        b, _ = post(p["beat"], p["downbeat"])
        return s.numpy(), p["beat"].numpy(), p["downbeat"].numpy(), b

    for rate in (44100, 48000):
        dm = dl = 0.0
        same = tot = 0
        for path in a.audio:
            x, sr = sf.read(path, dtype="float64", always_2d=True)
            x = x.mean(1)
            if sr != rate:
                x = soxr.resample(x, sr, rate, quality="VHQ")
            ref = run(soxr.resample(x, rate, 22050))
            up, down = (1, 2) if rate == 44100 else (147, 320)
            alt = run(resample_poly(x, up, down))
            n = min(len(ref[0]), len(alt[0]))
            dm = max(dm, np.abs(ref[0][:n] - alt[0][:n]).max())
            dl = max(dl, np.abs(ref[1][:n] - alt[1][:n]).max(), np.abs(ref[2][:n] - alt[2][:n]).max())
            rb = set(np.round(ref[3] * 50).astype(int))
            ab = set(np.round(alt[3] * 50).astype(int))
            same += len(rb & ab)
            tot += max(len(rb), len(ab))
        print(f"{rate} Hz -> 22050 Hz, soxr HQ vs resample_poly({up},{down}): max log-mel diff {dm:.3g}, "
              f"max logit diff {dl:.3g}, beats on the same frame {same}/{tot} ({os.path.basename(a.ckpt)}, {len(a.audio)} files)")


if __name__ == "__main__":
    main()
