"""Exports a Beat This! checkpoint to the flat "BTHS" v1 weights file read by BeatThis.kt, and
writes fixed test inputs with the official PyTorch FP32 logits for them.

    python -I export_bin.py --beat-this-src DIR --tools DIR --ckpt small0.ckpt --out OUTDIR \
        --audio a.wav b.wav

File layout (all little-endian): "BTHS", int32 version 1, int32 transformer dim, int32 count,
then `count` arrays of float32, each preceded by its int32 length. Linear weights are stored
transposed (input-major, [in][out]) so the Kotlin inner loop runs along contiguous outputs;
BatchNorms are folded into scale/shift; RMSNorm gammas include the sqrt(size) factor.

Inputs: for each audio file, 8 s of log-mel (400 frames, from second 5) with 6 zero frames on
each side = 412 frames x 128, float32, time-major ("<name>.in.f32"). Reference: official eager
model, FP32, "<name>.<ckpt>.ref.f32" = beat logits (412) then downbeat logits (412).
"""
import argparse
import struct
import sys
from pathlib import Path

import numpy as np
import torch

FRAMES = 412
BORDER = 6


def bn_fold(bn):
    scale = bn.weight.detach() / torch.sqrt(bn.running_var + bn.eps)
    shift = bn.bias.detach() - bn.running_mean * scale
    return scale.numpy(), shift.numpy()


def lin_t(lin):
    return lin.weight.detach().numpy().T.copy()  # [in][out]


def rms_gamma(norm):
    return (norm.gamma.detach() * norm.scale).numpy().reshape(-1)


def attn_arrays(att):
    out = [rms_gamma(att.norm), lin_t(att.to_qkv), lin_t(att.to_gates), att.to_gates.bias.detach().numpy(),
           lin_t(att.to_out[0])]
    return out


def ff_arrays(ff):
    net = ff.net
    return [rms_gamma(net[0]), lin_t(net[1]), net[1].bias.detach().numpy(), lin_t(net[4]), net[4].bias.detach().numpy()]


def conv_t(conv):
    w = conv.weight.detach().numpy()  # (out, in, kf, kt)
    return w.transpose(2, 3, 1, 0).copy()  # [kf][kt][in][out]


def export(model, path):
    arrs = []
    st = model.frontend.stem
    arrs += list(bn_fold(st.bn1d))
    arrs.append(conv_t(st.conv2d))
    arrs += list(bn_fold(st.bn2d))
    for blk in model.frontend.blocks:
        p = blk.partial
        arrs += attn_arrays(p.attnF) + ff_arrays(p.ffF) + attn_arrays(p.attnT) + ff_arrays(p.ffT)
        arrs.append(conv_t(blk.conv2d))
        arrs += list(bn_fold(blk.norm))
    lin = model.frontend.linear
    d = lin.out_features
    w = lin.weight.detach().numpy().reshape(d, 256, 4)  # (out, c, f): input index c*4+f
    arrs.append(w.transpose(2, 1, 0).copy())  # [f][c][out]
    arrs.append(lin.bias.detach().numpy())
    for att, ff in model.transformer_blocks.layers:
        arrs += attn_arrays(att) + ff_arrays(ff)
    arrs.append(rms_gamma(model.transformer_blocks.norm))
    head = model.task_heads.beat_downbeat_lin
    arrs.append(lin_t(head))
    arrs.append(head.bias.detach().numpy())
    with open(path, "wb") as f:
        f.write(b"BTHS")
        f.write(struct.pack("<iii", 1, d, len(arrs)))
        for a in arrs:
            a = np.ascontiguousarray(a, dtype="<f4").reshape(-1)
            f.write(struct.pack("<i", a.size))
            f.write(a.tobytes())
    return len(arrs), sum(np.asarray(a).size for a in arrs)


def make_input(wav, frontend_np, start_s=5.0):
    import soundfile as sf
    import soxr
    x, sr = sf.read(str(wav), dtype="float32", always_2d=True)
    x = x.mean(axis=1)
    x = soxr.resample(x, sr, 22050).astype(np.float32)
    mel = frontend_np.logmel(x.astype(np.float64)).astype(np.float32)
    s = int(start_s * 50)
    w = mel[s:s + FRAMES - 2 * BORDER]
    assert w.shape == (FRAMES - 2 * BORDER, 128), w.shape
    return np.concatenate([np.zeros((BORDER, 128), np.float32), w, np.zeros((BORDER, 128), np.float32)])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--beat-this-src", required=True)
    ap.add_argument("--tools", required=True, help="the repo's tools/beatthis (read only)")
    ap.add_argument("--ckpt", nargs="+", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--audio", nargs="*", default=[])
    a = ap.parse_args()
    sys.path.insert(0, str(Path(a.tools).resolve()))
    import bt_common
    import frontend_np
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    inputs = {}
    for wav in a.audio:
        name = Path(wav).stem.replace(".", "_")
        spec = make_input(wav, frontend_np)
        spec.astype("<f4").tofile(out / f"{name}.in.f32")
        inputs[name] = spec
    torch.set_num_threads(4)
    for ck in a.ckpt:
        tag = Path(ck).stem
        model = bt_common.load_model(a.beat_this_src, ck).eval()
        n, total = export(model, out / f"beatthis-{tag}.bin")
        print(f"{tag}: {n} arrays, {total} floats -> {(out / f'beatthis-{tag}.bin').stat().st_size} bytes")
        for name, spec in inputs.items():
            with torch.no_grad():
                o = model(torch.from_numpy(spec)[None])
            ref = np.concatenate([o["beat"][0].numpy(), o["downbeat"][0].numpy()]).astype("<f4")
            ref.tofile(out / f"{name}.{tag}.ref.f32")
            print(f"  {name}: beat logits {ref[:FRAMES].min():.2f}..{ref[:FRAMES].max():.2f}")


if __name__ == "__main__":
    main()
