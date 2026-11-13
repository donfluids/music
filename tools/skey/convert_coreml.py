"""Core ML conversion of S-KEY's ChromaNet at a fixed input length (FP32 and FP16), and of the whole
audio -> probabilities graph (VQT front end included) to see what breaks.

usage: python -I convert_coreml.py --skey-src SRC --out DIR [--seconds 20 30]
Writes DIR/skey_net_<T>_{fp32,fp16}.mlpackage (input: (1, 1, 84, T) dB-scaled VQT, output: 24
probabilities in the model's own order, see skey/key_detection.py key_map) and DIR/convert.json.
"""
import argparse
import copy
import json
import os
import sys
import traceback
import warnings

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
warnings.filterwarnings("ignore")
import numpy as np  # noqa: E402
import torch  # noqa: E402

from skey_common import SKey  # noqa: E402
from skey_export import ExportChromaNet  # noqa: E402


def frames(seconds):
    return 1 + (seconds * 22050) // 512


def mil_ops(mlmodel):
    from collections import Counter
    c = Counter()
    for f in mlmodel._mil_program.functions.values():
        for op in f.operations:
            c[op.op_type] += 1
    return dict(c)


def size_of(path):
    tot = 0
    for root, _, files in os.walk(path):
        for f in files:
            tot += os.path.getsize(os.path.join(root, f))
    return tot


def try_convert(ct, module, x):
    try:
        traced = torch.jit.trace(module, x, check_trace=False)
        ct.convert(traced, inputs=[ct.TensorType(shape=tuple(x.shape))], convert_to="mlprogram",
                   minimum_deployment_target=ct.target.iOS17)
        return dict(ok=True)
    except Exception as e:  # noqa: BLE001
        return dict(ok=False, error=f"{type(e).__name__}: {e}"[:300])


class Whole(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.hcqt, self.net = copy.deepcopy(m.hcqt), ExportChromaNet(copy.deepcopy(m.net))

    def forward(self, x):
        return self.net(self.hcqt(x)[:, :, :84, :])


def main():
    import coremltools as ct
    p = argparse.ArgumentParser()
    p.add_argument("--skey-src", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--seconds", type=int, nargs="*", default=[20, 30])
    a = p.parse_args()
    os.makedirs(a.out, exist_ok=True)
    m = SKey(a.skey_src)
    report = {}
    for sec in a.seconds:
        T = frames(sec)
        x = torch.rand(1, 1, 84, T)
        report[f"official_{T}"] = try_convert(ct, copy.deepcopy(m.net).eval(), x)
        print(f"official module, T={T}:", report[f"official_{T}"], flush=True)
        for prec in ("fp32", "fp16"):
            # a fresh copy for every conversion (see skey_export.py)
            traced = torch.jit.trace(ExportChromaNet(copy.deepcopy(m.net)).eval(), x, check_trace=False)
            name = f"skey_net_{T}_{prec}"
            try:
                ml = ct.convert(traced, inputs=[ct.TensorType(name="vqt_db", shape=(1, 1, 84, T))],
                                outputs=[ct.TensorType(name="probs")], convert_to="mlprogram",
                                minimum_deployment_target=ct.target.iOS17,
                                compute_precision=ct.precision.FLOAT32 if prec == "fp32" else ct.precision.FLOAT16)
                path = os.path.join(a.out, name + ".mlpackage")
                ml.save(path)
                report[name] = dict(ok=True, bytes=size_of(path), ops=mil_ops(ml))
            except Exception as e:  # noqa: BLE001
                report[name] = dict(ok=False, error=f"{type(e).__name__}: {e}"[:500], trace=traceback.format_exc()[-1500:])
            print(name, {k: v for k, v in report[name].items() if k != "ops"}, flush=True)
    # the whole graph, nnAudio VQT included
    sec = a.seconds[0]
    try:
        xa = torch.rand(1, 1, sec * 22050) - 0.5
        traced = torch.jit.trace(Whole(m).eval(), xa, check_trace=False)
        ml = ct.convert(traced, inputs=[ct.TensorType(name="audio", shape=tuple(xa.shape))], convert_to="mlprogram",
                        minimum_deployment_target=ct.target.iOS17, compute_precision=ct.precision.FLOAT32)
        path = os.path.join(a.out, f"skey_whole_{sec}s_fp32.mlpackage")
        ml.save(path)
        report["whole"] = dict(ok=True, bytes=size_of(path), ops=mil_ops(ml))
    except Exception as e:  # noqa: BLE001
        report["whole"] = dict(ok=False, error=f"{type(e).__name__}: {e}"[:800], trace=traceback.format_exc()[-2000:])
    print("whole", {k: v for k, v in report["whole"].items() if k not in ("ops",)}, flush=True)
    json.dump(report, open(os.path.join(a.out, "convert.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
