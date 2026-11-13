"""Converts Beat This! checkpoints to LiteRT (.tflite) with litert-torch, FP32 / FP16 weights /
dynamic-range INT8, and writes each model's logits on the fixed test inputs.

    python -I convert_tflite.py --beat-this-src DIR --tools DIR --ckpt small0.ckpt final0.ckpt \
        --data DATA --out OUT

The traced module is the repo's tools/beatthis/bt_export.ExportBeatThis (plain permute/reshape,
fixed 412 frames, precomputed rotary tables, FP16-safe RMSNorm) on the official weights.
FP16 and INT8 are made from the FP32 .tflite with ai-edge-quantizer: FP16 = weight-only float
casting (weights stored as float16, dequantized to float32 at load or by the delegate); INT8 =
dynamic-range (int8 per-channel weights, activations quantized on the fly for the int8 matmuls).
"""
import argparse
import inspect
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch

FRAMES = 412


def load_model(src, ckpt):
    sys.path.insert(0, str(Path(src).resolve()))
    from beat_this.model.beat_tracker import BeatThis
    from beat_this.utils import replace_state_dict_key
    ck = torch.load(ckpt, map_location="cpu", weights_only=False)
    hp = {k: v for k, v in ck["hyper_parameters"].items() if k in set(inspect.signature(BeatThis).parameters)}
    m = BeatThis(**hp)
    m.load_state_dict(replace_state_dict_key(ck["state_dict"], "model.", ""))
    return m.eval()


class Wrap(torch.nn.Module):
    """One output (1, 2, T): beat logits then downbeat logits."""

    def __init__(self, exp):
        super().__init__()
        self.e = exp

    def forward(self, spect):
        b, d = self.e(spect)
        return torch.stack([b, d], dim=1)


def run_tflite(path, x, threads=1):
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=str(path), num_threads=threads)
    it.allocate_tensors()
    inp = it.get_input_details()[0]
    out = it.get_output_details()[0]
    it.set_tensor(inp["index"], x.reshape(inp["shape"]).astype(np.float32))
    it.invoke()
    y = it.get_tensor(out["index"]).reshape(2, FRAMES)
    return np.concatenate([y[0], y[1]]).astype("<f4")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--beat-this-src", required=True)
    ap.add_argument("--tools", required=True)
    ap.add_argument("--ckpt", nargs="+", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--inputs", nargs="+", default=["rock_00000", "00_BN1-129-Eb_solo_mic"])
    ap.add_argument("--gpu-friendly", action="store_true",
                    help="trace bt_export_gpu.ExportBeatThisGpu (rank <= 4, no L2_NORMALIZATION); files get '-gpu'")
    a = ap.parse_args()
    sys.path.insert(0, str(Path(a.tools).resolve()))
    from bt_export import ExportBeatThis
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from bt_export_gpu import ExportBeatThisGpu
    module = ExportBeatThisGpu if a.gpu_friendly else ExportBeatThis
    sfx = "-gpu" if a.gpu_friendly else ""
    import litert_torch
    from ai_edge_quantizer import quantizer, recipe

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    data = Path(a.data)
    log = {}
    for ck in a.ckpt:
        tag = Path(ck).stem + sfx
        model = load_model(a.beat_this_src, ck)
        wrap = Wrap(module(model, FRAMES)).eval()
        x0 = torch.from_numpy(np.fromfile(data / f"{a.inputs[0]}.in.f32", dtype="<f4").reshape(1, FRAMES, 128))
        t0 = time.time()
        edge = litert_torch.convert(wrap, (x0,))
        p32 = out / f"beatthis-{tag}-fp32.tflite"
        edge.export(str(p32))
        log[f"{tag}-fp32"] = dict(seconds=round(time.time() - t0, 1), bytes=p32.stat().st_size)
        print(f"{tag}: converted in {time.time() - t0:.0f} s, {p32.stat().st_size} bytes", flush=True)

        # FP16 weights (float casting), INT8 dynamic range
        variants = {"fp32": p32}
        try:
            qt = quantizer.Quantizer(str(p32))
            qt.add_weight_only_config(regex=".*", operation_name="*", num_bits=16, algorithm_key="float_casting")
            p16 = out / f"beatthis-{tag}-fp16.tflite"
            qt.quantize().export_model(str(p16), overwrite=True)
            variants["fp16"] = p16
            log[f"{tag}-fp16"] = dict(bytes=p16.stat().st_size)
        except Exception as e:  # noqa: BLE001
            print(f"{tag}: fp16 failed: {e!r}", flush=True)
            log[f"{tag}-fp16"] = dict(error=repr(e))
        try:
            qt = quantizer.Quantizer(str(p32))
            qt.load_quantization_recipe(recipe.dynamic_wi8_afp32())
            p8 = out / f"beatthis-{tag}-int8dyn.tflite"
            qt.quantize().export_model(str(p8), overwrite=True)
            variants["int8dyn"] = p8
            log[f"{tag}-int8dyn"] = dict(bytes=p8.stat().st_size)
        except Exception as e:  # noqa: BLE001
            print(f"{tag}: int8 failed: {e!r}", flush=True)
            log[f"{tag}-int8dyn"] = dict(error=repr(e))

        for name in a.inputs:
            x = np.fromfile(data / f"{name}.in.f32", dtype="<f4")
            for v, p in variants.items():
                y = run_tflite(p, x, threads=4)
                y.tofile(out / f"{name}.{tag}.tflite-{v}.f32")
        for v, p in variants.items():
            print(f"{tag} {v}: {p.stat().st_size} bytes", flush=True)
    (out / "convert_log.json").write_text(json.dumps(log, indent=1))


if __name__ == "__main__":
    main()
