"""Runs .tflite models with the LiteRT Python interpreter (XNNPACK, the default CPU delegate).

    python -I run_tflite.py logits MODEL.tflite IN.f32 [IN2.f32 ...] --suffix S   # writes IN.<S>.f32
    python -I run_tflite.py bench MODEL.tflite IN.f32 --threads 1 4 --runs 21 --warmup 5 [--no-xnnpack]
"""
import argparse
import time
from pathlib import Path

import numpy as np

FRAMES = 412


def interp(path, threads, xnnpack=True):
    from ai_edge_litert.interpreter import Interpreter, OpResolverType
    kw = {} if xnnpack else dict(experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES)
    it = Interpreter(model_path=str(path), num_threads=threads, **kw)
    it.allocate_tensors()
    return it


def run(it, x):
    inp = it.get_input_details()[0]
    out = it.get_output_details()[0]
    it.set_tensor(inp["index"], x.reshape(inp["shape"]).astype(np.float32))
    it.invoke()
    y = it.get_tensor(out["index"]).reshape(2, FRAMES)
    return np.concatenate([y[0], y[1]]).astype("<f4")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["logits", "bench"])
    ap.add_argument("model")
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("--suffix", default="tflite")
    ap.add_argument("--threads", type=int, nargs="+", default=[1, 4])
    ap.add_argument("--runs", type=int, default=21)
    ap.add_argument("--warmup", type=int, default=5)
    ap.add_argument("--no-xnnpack", action="store_true")
    a = ap.parse_args()
    if a.mode == "logits":
        it = interp(a.model, 4)
        for p in a.inputs:
            y = run(it, np.fromfile(p, dtype="<f4"))
            y.tofile(str(p).replace(".in.f32", f".{a.suffix}.f32"))
        return
    x = np.fromfile(a.inputs[0], dtype="<f4")
    for th in a.threads:
        t0 = time.perf_counter()
        it = interp(a.model, th, not a.no_xnnpack)
        run(it, x)
        first = (time.perf_counter() - t0) * 1e3
        for _ in range(a.warmup):
            run(it, x)
        ms = []
        for _ in range(a.runs):
            t = time.perf_counter(); run(it, x); ms.append((time.perf_counter() - t) * 1e3)
        ms.sort()
        print(f"{Path(a.model).name} threads={th} xnnpack={not a.no_xnnpack}: median {ms[len(ms) // 2]:.1f} ms, "
              f"min {ms[0]:.1f}, max {ms[-1]:.1f}, n={len(ms)}; load+first run {first:.0f} ms", flush=True)


if __name__ == "__main__":
    main()
