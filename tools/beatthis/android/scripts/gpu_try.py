"""Tries LiteRT's GPU accelerator (CompiledModel, WebGPU backend) on this machine's software
Vulkan (Mesa lavapipe). A functional check only: does the graph compile for the GPU, fully or
with CPU fallback, and do the logits match? Speeds here mean nothing for a phone GPU.

    python -I gpu_try.py MODEL.tflite IN.f32 OUT.f32          # GPU only, else GPU with CPU fallback
    python -I gpu_try.py many MODEL.tflite SUFFIX IN.f32 ...  # GPU only, writes IN.<SUFFIX>.f32
"""
import sys
import time

import numpy as np
from ai_edge_litert.compiled_model import CompiledModel
from ai_edge_litert.hardware_accelerator import HardwareAccelerator


def main():
    path, inp, outp = sys.argv[1:4]
    x = np.fromfile(inp, dtype="<f4").reshape(1, 412, 128)
    for name, acc in [("GPU only", HardwareAccelerator.GPU), ("GPU|CPU", HardwareAccelerator.GPU | HardwareAccelerator.CPU)]:
        try:
            t0 = time.time()
            m = CompiledModel.from_file(path, hardware_accel=acc)
            print(f"{name}: compiled in {time.time() - t0:.1f} s, fully accelerated: {m.is_fully_accelerated()}", flush=True)
            ins = m.create_input_buffers(0)
            outs = m.create_output_buffers(0)
            ins[0].write(x)
            t0 = time.time()
            m.run_by_index(0, ins, outs)
            print(f"{name}: ran in {time.time() - t0:.1f} s", flush=True)
            y = outs[0].read(2 * 412, np.float32).reshape(2, 412)
            np.concatenate([y[0], y[1]]).astype("<f4").tofile(outp)
            return
        except Exception as e:  # noqa: BLE001
            print(f"{name}: failed: {e!r}", flush=True)




def many(path, suffix, inputs):
    """Compile once on the GPU only, run every input, write <input>.<suffix>.f32."""
    m = CompiledModel.from_file(path, hardware_accel=HardwareAccelerator.GPU)
    print(f"{path}: fully accelerated {m.is_fully_accelerated()}", flush=True)
    ins = m.create_input_buffers(0); outs = m.create_output_buffers(0)
    for p in inputs:
        ins[0].write(np.fromfile(p, dtype="<f4").reshape(1, 412, 128))
        m.run_by_index(0, ins, outs)
        y = outs[0].read(2 * 412, np.float32).reshape(2, 412)
        np.concatenate([y[0], y[1]]).astype("<f4").tofile(p.replace(".in.f32", f".{suffix}.f32"))


if __name__ == "__main__":
    if sys.argv[1] == "many":
        many(sys.argv[2], sys.argv[3], sys.argv[4:])
    else:
        main()
