"""CPU time of S-KEY per run: the official per-window run (nnAudio VQT + ChromaNet), the network
alone (what a live app reruns on each reading once VQT frames are kept as audio arrives), and
the nnAudio VQT on 4 s of new audio (a rough upper bound for the incremental front end).

usage: python -I bench_speed.py --skey-src SRC [--threads 1 4] [--seconds 20 30] [--runs 15]
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

from skey_common import SKey  # noqa: E402


def timeit(fn, runs, warm=3):
    for _ in range(warm):
        fn()
    t = []
    for _ in range(runs):
        s = time.perf_counter()
        fn()
        t.append((time.perf_counter() - s) * 1000)
    return statistics.median(t), min(t)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--skey-src", required=True)
    p.add_argument("--threads", type=int, nargs="*", default=[1, 4])
    p.add_argument("--seconds", type=int, nargs="*", default=[20, 30])
    p.add_argument("--runs", type=int, default=15)
    a = p.parse_args()
    m = SKey(a.skey_src)
    import nnAudio.features.vqt as nv
    cpu = platform.processor() or platform.machine()
    try:
        cpu = [l.split(":")[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name")][0]
    except Exception:  # noqa: BLE001
        pass
    print(f"CPU: {cpu}, {os.cpu_count()} logical cores, torch {torch.__version__}, load {os.getloadavg()}")
    rng = np.random.RandomState(0)
    print("| window | threads | official per window, median (min) ms | network only ms | VQT of 4 s ms |")
    print("|---|---|---|---|---|")
    for th in a.threads:
        torch.set_num_threads(th)
        for sec in a.seconds:
            x = (rng.randn(sec * 22050) * 0.1).astype(np.float32)
            T = 1 + (sec * 22050) // 512
            feat = torch.rand(1, 1, 84, T)
            x4 = torch.from_numpy(x[: 4 * 22050])[None, None]

            def full():
                m.probs(x)

            def net():
                with torch.no_grad():
                    m.net(feat)

            def vqt4():
                with torch.no_grad():
                    nv.VQT.forward(m.hcqt, x4, "Magnitude", "librosa")
            f = timeit(full, max(3, a.runs // 3))
            n = timeit(net, a.runs)
            v = timeit(vqt4, a.runs)
            print(f"| {sec} s | {th} | {f[0]:.0f} ({f[1]:.0f}) | {n[0]:.1f} ({n[1]:.1f}) | {v[0]:.0f} ({v[1]:.0f}) |", flush=True)


if __name__ == "__main__":
    main()
