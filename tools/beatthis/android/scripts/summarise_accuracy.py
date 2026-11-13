"""Sums up compare.py over every window: worst logit difference, sign changes, windows whose
picked beats / downbeats differ from the PyTorch FP32 reference.

    python -I summarise_accuracy.py --beat-this-src DIR data data2
"""
import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--beat-this-src", required=True)
    ap.add_argument("dirs", nargs="+")
    a = ap.parse_args()
    sys.path.insert(0, str(Path(a.beat_this_src).resolve()))
    from beat_this.model.postprocessor import Postprocessor
    pp = Postprocessor("minimal", 50)
    names = [p.name[:-len(".in.f32")] for d in a.dirs for p in sorted(Path(d).glob("*.in.f32"))]
    dirs = {p.name[:-len(".in.f32")]: Path(d) for d in a.dirs for p in Path(d).glob("*.in.f32")}
    print(f"{len(names)} windows of 412 frames (824 logits each)\n")
    print("| model | variant | max logit diff | mean | sign changes | windows with other beats | other downbeats | beats ref -> variant |")
    print("|---|---|---|---|---|---|---|---|")
    for m in ["small0", "final0"]:
        for v in ["kotlin", "tflite-fp32", "tflite-fp16", "tflite-int8dyn", "tflite-gpu-fp32", "tflite-gpu-fp16", "gpudelegate-fp32", "gpudelegate-fp16"]:
            worst = 0.0; mean = []; sign = 0; nb = 0; nd = 0; br = 0; bt = 0; n = 0
            for name in names:
                d = dirs[name]
                ref = np.fromfile(d / f"{name}.{m}.ref.f32", dtype="<f4")
                tp = d / f"{name}.{m}.{v}.f32"
                if not tp.exists():
                    continue
                r = compare.compare(pp, ref, np.fromfile(tp, dtype="<f4"))
                n += 1
                worst = max(worst, r["max"]); mean.append(r["mean"]); sign += r["sign"]
                nb += not r["same_beats"]; nd += not r["same_downs"]
                br += r["beats"][0]; bt += r["beats"][1]
            if n:
                print(f"| {m} | {v} | {worst:.2g} | {np.mean(mean):.1g} | {sign} / {n * 824} | {nb} / {n} | {nd} / {n} | {br} -> {bt} |")


if __name__ == "__main__":
    main()
