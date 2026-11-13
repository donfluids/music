"""Export-friendly forward pass for the official BeatThis model, for a fixed input length.

The official model uses einops layers and a caching rotary embedding, which coremltools cannot
convert from a trace. This module walks the same submodules and weights with plain
permute/reshape and precomputed rotary cos/sin tables. It is checked against the official
eager model (max difference printed by convert_coreml.py).
"""
import torch
import torch.nn.functional as F
from torch import nn


def rope_tables(n, dim=32, theta=10000.0):
    """cos, sin of shape (n, dim) for interleaved rotary embedding (rotary_embedding_torch, freqs_for='lang')."""
    freqs = 1.0 / (theta ** (torch.arange(0, dim, 2)[: dim // 2].float() / dim))
    ang = torch.arange(n, dtype=torch.float32)[:, None] * freqs[None, :]  # (n, dim/2)
    ang = ang.repeat_interleave(2, dim=-1)  # (n, dim): f0 f0 f1 f1 ...
    return ang.cos(), ang.sin()


def rotate_half(x, shp):
    # interleaved pairs (x0, x1) -> (-x1, x0); shp is x's shape as Python ints
    x = x.reshape(*shp[:-1], shp[-1] // 2, 2)
    x1 = x[..., 0]
    x2 = x[..., 1]
    return torch.stack((-x2, x1), dim=-1).reshape(shp)


def rms_norm(norm, x):
    # F.normalize(x, dim=-1) * sqrt(size) * gamma, F.normalize eps = 1e-12
    n = torch.sqrt((x * x).sum(dim=-1, keepdim=True)).clamp(min=1e-12)
    return x / n * norm.scale * norm.gamma


def attention(att, x, cos, sin, b, n):
    """roformer.Attention forward; x (b, n, dim). Shapes are passed as Python ints so the
    trace has no shape arithmetic (coremltools cannot convert it)."""
    h = att.heads
    d = att.to_qkv.out_features // (3 * h)
    x = rms_norm(att.norm, x)
    qkv = att.to_qkv(x)
    qkv = qkv.reshape(b, n, 3, h, d).permute(2, 0, 3, 1, 4)  # qkv b h n d
    q, k, v = qkv[0], qkv[1], qkv[2]
    shp = (b, h, n, d)
    q = q * cos + rotate_half(q, shp) * sin
    k = k * cos + rotate_half(k, shp) * sin
    scores = torch.matmul(q, k.transpose(-1, -2)) * (d ** -0.5)
    out = torch.matmul(torch.softmax(scores, dim=-1), v)  # b h n d
    gates = att.to_gates(x)  # b n h
    out = out * torch.sigmoid(gates.permute(0, 2, 1).unsqueeze(-1))
    out = out.permute(0, 2, 1, 3).reshape(b, n, h * d)
    return att.to_out(out)


def feed_forward(ff, x):
    net = ff.net
    x = rms_norm(net[0], x)
    x = net[1](x)
    x = F.gelu(x)
    return net[4](x)


class ExportBeatThis(nn.Module):
    def __init__(self, model, frames):
        super().__init__()
        self.m = model
        self.frames = frames
        # frequency lengths seen by the three partial transformers: 32, 16, 8
        tabs = {}
        for n in (frames, 32, 16, 8):
            c, s = rope_tables(n)
            tabs[n] = (c, s)
        for n, (c, s) in tabs.items():
            self.register_buffer(f"cos{n}", c, persistent=False)
            self.register_buffer(f"sin{n}", s, persistent=False)

    def tab(self, n):
        return getattr(self, f"cos{n}"), getattr(self, f"sin{n}")

    def forward(self, spect):
        m = self.m
        stem = m.frontend.stem
        x = spect.transpose(1, 2)  # b f t
        x = stem.bn1d(x)
        x = x.unsqueeze(1)  # b 1 f t
        x = stem.conv2d(x)
        x = stem.bn2d(x)
        x = F.gelu(x)
        b, t = 1, self.frames
        c, f = m.frontend.stem.conv2d.out_channels, m.frontend.stem.bn1d.num_features // 4
        for blk in m.frontend.blocks:
            p = blk.partial
            y = x.permute(0, 3, 2, 1).reshape(b * t, f, c)
            cf, sf = self.tab(f)
            y = y + attention(p.attnF, y, cf, sf, b * t, f)
            y = y + feed_forward(p.ffF, y)
            y = y.reshape(b, t, f, c).permute(0, 2, 1, 3).reshape(b * f, t, c)
            ct, st = self.tab(t)
            y = y + attention(p.attnT, y, ct, st, b * f, t)
            y = y + feed_forward(p.ffT, y)
            x = y.reshape(b, f, t, c).permute(0, 3, 1, 2)
            x = blk.conv2d(x)
            x = blk.norm(x)
            x = F.gelu(x)
            c, f = blk.conv2d.out_channels, f // 2
        x = x.permute(0, 3, 1, 2).reshape(b, t, c * f)
        x = m.frontend.linear(x)
        ct, st = self.tab(t)
        for att, ff in m.transformer_blocks.layers:
            x = attention(att, x, ct, st, b, t) + x
            x = feed_forward(ff, x) + x
        x = rms_norm(m.transformer_blocks.norm, x)
        bd = m.task_heads.beat_downbeat_lin(x)
        downbeat = bd[..., 1]
        beat = bd[..., 0] + downbeat
        return beat, downbeat
