"""Times one Beat This! forward pass per checkpoint and window length on this CPU.

    python -I bench_speed.py --beat-this-src DIR --ckpt small0.ckpt final0.ckpt --windows 6 8 12

The input is W*50 frames plus the 6+6 zero frames of border padding the official inference
adds. Reports the median of --reps runs after --warmup runs, for 1 thread and the default
thread count, plus the log-mel front end in NumPy for the same audio length.
"""
import argparse
import os
import platform
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
import torch  # noqa: E402

from bt_common import load_model  # noqa: E402
from frontend_np import logmel  # noqa: E402


def timeit(fn, warmup, reps):
    for _ in range(warmup):
        fn()
    ts = []
    for _ in range(reps):
        t0 = time.perf_counter()
        fn()
        ts.append(time.perf_counter() - t0)
    return statistics.median(ts) * 1e3, min(ts) * 1e3


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--beat-this-src", required=True)
    ap.add_argument("--ckpt", nargs="+", required=True)
    ap.add_argument("--windows", nargs="+", type=float, default=[6, 8, 12])
    ap.add_argument("--warmup", type=int, default=3)
    ap.add_argument("--reps", type=int, default=15)
    a = ap.parse_args()
    default_threads = torch.get_num_threads()
    cpu = "?"
    try:
        for line in open("/proc/cpuinfo"):
            if line.startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    print(f"CPU: {cpu}; os.cpu_count()={os.cpu_count()}; torch {torch.__version__}; default threads {default_threads}; {platform.python_version()}")
    print("| checkpoint | W (s) | input frames | 1 thread median (min) ms | %d threads median (min) ms | NumPy log-mel of W s, ms |" % default_threads)
    print("|---|---|---|---|---|---|")
    rng = np.random.default_rng(0)
    for ck in a.ckpt:
        model = load_model(a.beat_this_src, ck).eval()
        name = os.path.splitext(os.path.basename(ck))[0]
        for w in a.windows:
            frames = int(round(w * 50)) + 12
            x = torch.tensor(rng.standard_normal((1, frames, 128)).astype(np.float32).clip(0) * 3)
            res = []
            for th in (1, default_threads):
                torch.set_num_threads(th)
                with torch.inference_mode():
                    res.append(timeit(lambda: model(x), a.warmup, a.reps))
            torch.set_num_threads(default_threads)
            audio = rng.standard_normal(int(w * 22050)) * 0.1
            fe = timeit(lambda: logmel(audio), 2, 7)
            print(f"| {name} | {w:g} | {frames} | {res[0][0]:.1f} ({res[0][1]:.1f}) | {res[1][0]:.1f} ({res[1][1]:.1f}) | {fe[0]:.1f} |")


if __name__ == "__main__":
    main()
