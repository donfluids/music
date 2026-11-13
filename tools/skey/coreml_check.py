"""Checks the converted Core ML programs against PyTorch on real windows, without a Mac.

Core ML cannot run on Linux, so the converted MIL program itself (the ops and constants that go
into the .mlpackage) is run by a small NumPy/torch interpreter below. FP32 programs are run in
float32. FP16 programs are run with every op's result rounded to float16 (the program's own
types) while each op computes in float32 inside: fp16 storage with fp32 accumulation, which is
what the GPU and Neural Engine are documented to do for most ops; an ANE that accumulated
reductions in fp16 would be worse than this stand-in. Final check belongs on a device.

usage: python -I coreml_check.py --skey-src SRC --work WORK [--n 120]
"""
import argparse
import os
import random
import sys
import warnings

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
warnings.filterwarnings("ignore")
import numpy as np  # noqa: E402
import torch  # noqa: E402
import torch.nn.functional as F  # noqa: E402

import skey_live_eval as ev  # noqa: E402
from skey_export import ExportChromaNet  # noqa: E402


def run_mil(prog, feed):
    from coremltools.converters.mil.mil import types
    fn = prog.functions["main"]
    env = dict(feed)

    def val(v):
        if v.val is not None and v.name not in env:
            return np.asarray(v.val)
        return env[v.name]

    for op in fn.operations:
        t = op.op_type
        ins = {k: v for k, v in op.inputs.items()}
        g = lambda k: val(ins[k])  # noqa: E731
        f32 = lambda a: np.asarray(a, dtype=np.float32)  # noqa: E731
        if t == "const":
            r = np.asarray(op.outputs[0].val)
        elif t in ("add", "sub", "mul", "real_div", "maximum", "pow"):
            x, y = f32(g("x")), f32(g("y"))
            r = {"add": np.add, "sub": np.subtract, "mul": np.multiply, "real_div": np.divide,
                 "maximum": np.maximum, "pow": np.power}[t](x, y)
        elif t == "sqrt":
            r = np.sqrt(f32(g("x")))
        elif t == "reduce_mean":
            r = f32(g("x")).mean(axis=tuple(int(a) for a in g("axes")), keepdims=bool(g("keep_dims")))
        elif t == "conv":
            x = torch.from_numpy(f32(g("x")))
            w = torch.from_numpy(f32(g("weight")))
            b = torch.from_numpy(f32(g("bias"))) if "bias" in ins else None
            pad = [int(p) for p in g("pad")] if "pad" in ins else [0, 0, 0, 0]
            assert str(g("pad_type")) in ("custom", "valid"), g("pad_type")
            x = F.pad(x, (pad[2], pad[3], pad[0], pad[1]))
            r = F.conv2d(x, w, b, stride=[int(s) for s in g("strides")], dilation=[int(d) for d in g("dilations")],
                         groups=int(g("groups"))).numpy()
        elif t == "gelu":
            x = torch.from_numpy(f32(g("x")))
            mode = str(g("mode")) if "mode" in ins else "EXACT"
            r = F.gelu(x, approximate="tanh" if mode == "TANH_APPROXIMATION" else "none").numpy()
        elif t == "pad":
            x = torch.from_numpy(f32(g("x")))
            p = [int(v) for v in g("pad")]
            mode = str(g("mode"))
            # MIL pads are (before, after) per axis from the first padded axis; torch wants last axis first
            pairs = [(p[i], p[i + 1]) for i in range(0, len(p), 2)]
            while len(pairs) > 2 and pairs[0] == (0, 0):  # torch pads only the last axes
                pairs = pairs[1:]
            tp = [v for pr in reversed(pairs) for v in pr]
            r = F.pad(x, tp, mode=mode).numpy()
        elif t == "transpose":
            r = np.transpose(g("x"), [int(v) for v in g("perm")])
        elif t == "linear":
            x, w = f32(g("x")), f32(g("weight"))
            r = x @ w.T + (f32(g("bias")) if "bias" in ins else 0)
        elif t == "reshape":
            r = np.reshape(g("x"), [int(v) for v in g("shape")])
        elif t == "softmax":
            x = f32(g("x"))
            ax = int(g("axis"))
            e = np.exp(x - x.max(axis=ax, keepdims=True))
            r = e / e.sum(axis=ax, keepdims=True)
        elif t == "cast":
            r = g("x").astype({"fp16": np.float16, "fp32": np.float32}[str(g("dtype"))])
        else:
            raise NotImplementedError(t)
        out = op.outputs[0]
        dt = np.float16 if out.dtype == types.fp16 else (np.float32 if out.dtype == types.fp32 else None)
        env[out.name] = r.astype(dt) if dt is not None and t != "const" else r
    return {v.name: env[v.name] for v in fn.outputs}


def convert(net, T, prec, fold_bn=False):
    import coremltools as ct
    import copy
    # A fresh copy each time: coremltools' passes can change constants in place, and constants
    # share memory with the torch weights, so a second conversion in the same process would
    # otherwise start from altered weights.
    traced = torch.jit.trace(ExportChromaNet(copy.deepcopy(net), fold_bn=fold_bn).eval(), torch.rand(1, 1, 84, T), check_trace=False)
    ml = ct.convert(traced, inputs=[ct.TensorType(name="vqt_db", shape=(1, 1, 84, T))], outputs=[ct.TensorType(name="probs")],
                    convert_to="mlprogram", minimum_deployment_target=ct.target.iOS17,
                    compute_precision=ct.precision.FLOAT32 if prec == "fp32" else ct.precision.FLOAT16)
    return ml._mil_program


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--skey-src", required=True)
    p.add_argument("--work", required=True)
    p.add_argument("--n", type=int, default=120)
    a = p.parse_args()
    live = ev.LiveSKey(a.skey_src)
    items = [i for i in ev.load_items(a.work) if i["dataset"] in ("gtzan", "guitarset_comp", "guitarset_solo")]
    rng = random.Random(7)
    for W in ev.WINDOWS:
        T = 1 + (W * ev.SK_RATE) // ev.HOP
        progs = {prec: convert(live.m.net, T, prec) for prec in ("fp32", "fp16")}
        stats = {prec: [] for prec in progs}
        n = 0
        for it in rng.sample(items, a.n):
            x, sr = ev.load_audio(it["audio"])
            x22 = ev.resample(x, sr, ev.SK_RATE)
            if len(x22) < W * ev.SK_RATE + ev.HOP:
                continue
            mag = live.magnitude(x22)
            s1 = len(x22) - ev.HOP
            s0 = ((s1 - W * ev.SK_RATE) // ev.HOP + 1) * ev.HOP
            s1 = s0 + (T - 1) * ev.HOP
            seg = mag[:, s0 // ev.HOP: s0 // ev.HOP + T]
            peak = np.abs(x22[s0:s1]).max()
            db = 20 * np.log10(np.maximum(seg / peak, 1e-10))
            db = np.maximum(db, db.max() - 80)
            feat = (db / 80 + 1)[:84].astype(np.float32)[None, None]
            with torch.no_grad():
                ref = live.m.net(torch.from_numpy(feat)).numpy()[0]
            n += 1
            for prec, prog in progs.items():
                out = list(run_mil(prog, {"vqt_db": feat.astype(np.float32)}).values())[0].astype(np.float32)[0]
                stats[prec].append((float(np.abs(out - ref).max()), int(out.argmax()) == int(ref.argmax()),
                                    float(np.abs(np.log(np.maximum(out, 1e-6)) - np.log(ref)).max())))
        for prec, s in stats.items():
            d = np.array([v[0] for v in s])
            print(f"W={W}s T={T} {prec}: {n} windows, max |p - p_torch| median {np.median(d):.2e} max {d.max():.2e}, "
                  f"argmax same {sum(v[1] for v in s)}/{n}, max |log p diff| {max(v[2] for v in s):.3f}", flush=True)


if __name__ == "__main__":
    main()
