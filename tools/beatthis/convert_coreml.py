"""Traces a Beat This! checkpoint at a fixed input length and converts it to Core ML.

    python -I convert_coreml.py --beat-this-src DIR --ckpt small0.ckpt --frames 412 --out DIR [--audio a.wav ...]

Writes <name>_<frames>_fp32.mlpackage and <name>_<frames>_fp16.mlpackage (mlprogram, iOS 17).
Input  "spect": (1, frames, 128) float32 log-mel frames (see README).
Outputs "beat", "downbeat": (1, frames) float32 logits.

Core ML models cannot be run on Linux, so the numbers printed for FP16 come from running the
same PyTorch model in float16 on the CPU (a stand-in for Core ML's float16 compute), and the
traced model is checked against the eager model in float32.
"""
import argparse
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
import torch  # noqa: E402

from bt_common import load_model  # noqa: E402
from bt_export import ExportBeatThis  # noqa: E402


class Wrapper(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, spect):
        out = self.model(spect)
        return out["beat"], out["downbeat"]


def dir_size(path):
    return sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(path) for f in fs)


def real_inputs(paths, frames):
    import soundfile as sf
    import soxr

    from frontend_np import logmel
    xs = []
    for p in paths:
        x, sr = sf.read(p, dtype="float64", always_2d=True)
        x = x.mean(1)
        if sr != 22050:
            x = soxr.resample(x, sr, 22050)
        s = logmel(x)
        # take a few windows from each file
        for start in range(0, max(1, len(s) - frames), max(1, (len(s) - frames) // 3 or 1))[:4]:
            w = s[start:start + frames]
            if len(w) == frames:
                xs.append(w)
    return torch.tensor(np.stack(xs))


def main():
    import coremltools as ct

    ap = argparse.ArgumentParser()
    ap.add_argument("--beat-this-src", required=True)
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--frames", type=int, default=412)
    ap.add_argument("--out", required=True)
    ap.add_argument("--audio", nargs="*", default=[])
    a = ap.parse_args()
    name = os.path.splitext(os.path.basename(a.ckpt))[0]
    model = load_model(a.beat_this_src, a.ckpt).eval()
    wrapped = Wrapper(model).eval()  # the official forward
    export = ExportBeatThis(model, a.frames).eval()  # same weights, convertible ops
    example = torch.zeros(1, a.frames, 128)
    with torch.no_grad():
        traced = torch.jit.trace(export, example)

    if a.audio:
        xs = real_inputs(a.audio, a.frames)
        with torch.no_grad():
            d_trace = 0.0
            d_half = 0.0
            ref_max = 0.0
            half = Wrapper(load_model(a.beat_this_src, a.ckpt)).eval().half()
            flips = 0
            total = 0
            for x in xs:
                x = x[None]
                rb, rd = wrapped(x)
                tb, td = traced(x)
                hb, hd = half(x.half())
                d_trace = max(d_trace, (rb - tb).abs().max().item(), (rd - td).abs().max().item())
                d_half = max(d_half, (rb - hb.float()).abs().max().item(), (rd - hd.float()).abs().max().item())
                ref_max = max(ref_max, rb.abs().max().item())
                flips += int(((rb > 0) != (hb.float() > 0)).sum() + ((rd > 0) != (hd.float() > 0)).sum())
                total += 2 * rb.numel()
        print(f"{name}: {len(xs)} real windows of {a.frames} frames; max |logit| {ref_max:.2f}")
        print(f"{name}: traced export vs official eager fp32 max diff {d_trace:.3g}")
        print(f"{name}: torch float16 (proxy for Core ML FP16) vs fp32 max diff {d_half:.3g}; frames whose logit sign flips: {flips}/{total}")

    os.makedirs(a.out, exist_ok=True)
    for prec, cp in (("fp32", ct.precision.FLOAT32), ("fp16", ct.precision.FLOAT16)):
        path = os.path.join(a.out, f"{name}_{a.frames}_{prec}.mlpackage")
        if os.path.exists(path):
            shutil.rmtree(path)
        try:
            ml = ct.convert(
                traced,
                inputs=[ct.TensorType(name="spect", shape=(1, a.frames, 128), dtype=np.float32)],
                outputs=[ct.TensorType(name="beat"), ct.TensorType(name="downbeat")],
                convert_to="mlprogram",
                minimum_deployment_target=ct.target.iOS17,
                compute_precision=cp,
            )
            ml.save(path)
            spec = ml.get_spec()
            ops = {}
            for f in spec.mlProgram.functions.values():
                for blk in f.block_specializations.values():
                    for op in blk.operations:
                        ops[op.type] = ops.get(op.type, 0) + 1
            print(f"{name} {prec}: converted OK, {dir_size(path) / 1e6:.2f} MB, {sum(ops.values())} ops, types: {dict(sorted(ops.items(), key=lambda kv: -kv[1]))}")
        except Exception as e:  # report and go on
            print(f"{name} {prec}: conversion FAILED: {type(e).__name__}: {e}")


if __name__ == "__main__":
    main()
