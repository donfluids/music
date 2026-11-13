"""Small modules through litert-torch and LiteRT's GPU accelerator on lavapipe, against PyTorch:
does the GPU path give right numbers at all, and which piece of the Beat This! graph goes wrong.

    python -I gpu_toys.py OUTDIR
"""
import sys
from pathlib import Path

import numpy as np
import torch
import litert_torch
from ai_edge_litert.compiled_model import CompiledModel
from ai_edge_litert.hardware_accelerator import HardwareAccelerator


class Lin(torch.nn.Module):
    def __init__(self):
        super().__init__(); self.l = torch.nn.Linear(128, 64)

    def forward(self, x):  # (1, 412, 128)
        return torch.nn.functional.gelu(self.l(x))


class BatchAttn(torch.nn.Module):
    """412 sequences of 32 tokens, 1 head of 32: the frequency attention's shape."""

    def forward(self, x):  # (412, 1, 32, 32)
        s = torch.matmul(x, x.transpose(-1, -2)) * 0.17
        return torch.matmul(torch.softmax(s, dim=-1), x)


class TimeAttn(torch.nn.Module):
    """32 sequences of 412 frames, 1 head: the time attention's shape."""

    def forward(self, x):  # (32, 1, 412, 32)
        s = torch.matmul(x, x.transpose(-1, -2)) * 0.17
        return torch.matmul(torch.softmax(s, dim=-1), x)


class Permute(torch.nn.Module):
    def forward(self, x):  # (1, 32, 32, 412) b c f t -> (b t) f c and back as in the front end
        y = x.permute(0, 3, 2, 1).reshape(412, 32, 32) * 2.0
        y = y.reshape(412, 32, 32).permute(1, 0, 2) + 1.0
        return y.permute(2, 0, 1).unsqueeze(0)


class RotLinear(torch.nn.Module):
    def __init__(self):
        super().__init__()
        r = torch.zeros(32, 32)
        for i in range(16):
            r[2 * i + 1, 2 * i] = -1.0; r[2 * i, 2 * i + 1] = 1.0
        self.register_buffer("rt", r.t().contiguous())

    def forward(self, x):  # (412, 1, 32, 32)
        return torch.nn.functional.linear(x, self.rt)


def check(name, mod, x, out):
    ref = mod(x).detach().numpy()
    p = out / f"toy_{name}.tflite"
    litert_torch.convert(mod.eval(), (x,)).export(str(p))
    m = CompiledModel.from_file(str(p), hardware_accel=HardwareAccelerator.GPU | HardwareAccelerator.CPU)
    ins = m.create_input_buffers(0); outs = m.create_output_buffers(0)
    ins[0].write(x.numpy())
    m.run_by_index(0, ins, outs)
    y = outs[0].read(ref.size, np.float32).reshape(ref.shape)
    print(f"TOY {name}: fully on GPU {m.is_fully_accelerated()}, max |gpu - torch| {np.abs(y - ref).max():.3g} "
          f"(|ref| max {np.abs(ref).max():.3g})", flush=True)


def main():
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(0)
    check("linear_gelu", Lin(), torch.randn(1, 412, 128), out)
    check("permute", Permute(), torch.randn(1, 32, 32, 412), out)
    check("rot_linear", RotLinear(), torch.randn(412, 1, 32, 32), out)
    check("freq_attention", BatchAttn(), torch.randn(412, 1, 32, 32), out)
    check("time_attention", TimeAttn(), torch.randn(32, 1, 412, 32), out)


if __name__ == "__main__":
    main()
