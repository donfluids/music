"""A GPU-delegate-friendly variant of tools/beatthis/bt_export.ExportBeatThis (same weights, same
maths): every tensor has rank <= 4, and nothing becomes L2_NORMALIZATION.

Changes against bt_export.py (found by running LiteRT's GPU accelerator, see gpu_try.py):
* q, k, v are sliced from the (b, n, 3c) projection and reshaped to (b, n, h, d) -> (b, h, n, d)
  instead of one (b, n, 3, h, d) reshape (rank 5).
* the interleaved rotary pair swap (x0, x1) -> (-x1, x0) is a constant 32 x 32 matrix product
  (F.linear; torch.matmul against a 2-D constant became BROADCAST_TO + BATCH_MATMUL, and the
  GPU delegate has no BROADCAST_TO) instead of a reshape to (..., 16, 2) and a stack (rank 5).
* RMSNorm keeps the FP16-safe division by the largest magnitude, then multiplies by
  rsqrt(mean(y^2)) instead of dividing by sqrt(sum(y^2)) (the converter turned that into
  L2_NORMALIZATION, which the GPU delegate does not run). F.normalize(x) * sqrt(size) equals
  x / rms(x), so this is the same function; a 1e-7 floor under the mean (>= 1/size here) only
  guards an all-zero vector.
"""
import torch
import torch.nn.functional as F
from torch import nn

from bt_export import rope_tables  # noqa: F401  (same tables)


def rms_norm(norm, x):
    m = x.abs().amax(dim=-1, keepdim=True).clamp(min=1e-12)
    y = x / m
    r = torch.rsqrt((y * y).mean(dim=-1, keepdim=True) + 1e-7)
    return y * r * norm.gamma  # norm.scale * F.normalize == 1 / rms


def swap_matrix(d=32):
    """R with (x @ R) = rotate_half(x) for interleaved pairs: out[2i] = -x[2i+1], out[2i+1] = x[2i]."""
    r = torch.zeros(d, d)
    for i in range(d // 2):
        r[2 * i + 1, 2 * i] = -1.0
        r[2 * i, 2 * i + 1] = 1.0
    return r


def attention(att, x, cos, sin, rot, b, n):
    h = att.heads
    d = att.to_qkv.out_features // (3 * h)
    c = h * d
    x = rms_norm(att.norm, x)
    qkv = att.to_qkv(x)  # b n 3c
    q = qkv[:, :, :c].reshape(b, n, h, d).permute(0, 2, 1, 3)
    k = qkv[:, :, c:2 * c].reshape(b, n, h, d).permute(0, 2, 1, 3)
    v = qkv[:, :, 2 * c:].reshape(b, n, h, d).permute(0, 2, 1, 3)
    q = q * cos + F.linear(q, rot) * sin   # rot holds R^T: F.linear(q, R^T) = q @ R
    k = k * cos + F.linear(k, rot) * sin
    scores = torch.matmul(q * (d ** -0.5), k.transpose(-1, -2))
    out = torch.matmul(torch.softmax(scores, dim=-1), v)  # b h n d
    gates = att.to_gates(x)  # b n h
    out = out * torch.sigmoid(gates.permute(0, 2, 1).unsqueeze(-1))
    out = out.permute(0, 2, 1, 3).reshape(b, n, c)
    return att.to_out(out)


def feed_forward(ff, x):
    net = ff.net
    x = rms_norm(net[0], x)
    x = net[1](x)
    x = F.gelu(x)
    return net[4](x)


class ExportBeatThisGpu(nn.Module):
    def __init__(self, model, frames):
        super().__init__()
        self.m = model
        self.frames = frames
        for n in (frames, 32, 16, 8):
            c, s = rope_tables(n)
            self.register_buffer(f"cos{n}", c, persistent=False)
            self.register_buffer(f"sin{n}", s, persistent=False)
        self.register_buffer("rot", swap_matrix().t().contiguous(), persistent=False)

    def tab(self, n):
        return getattr(self, f"cos{n}"), getattr(self, f"sin{n}")

    def forward(self, spect):
        m = self.m
        stem = m.frontend.stem
        x = spect.transpose(1, 2)
        x = stem.bn1d(x)
        x = x.unsqueeze(1)
        x = stem.conv2d(x)
        x = stem.bn2d(x)
        x = F.gelu(x)
        b, t = 1, self.frames
        c, f = m.frontend.stem.conv2d.out_channels, m.frontend.stem.bn1d.num_features // 4
        for blk in m.frontend.blocks:
            p = blk.partial
            y = x.permute(0, 3, 2, 1).reshape(b * t, f, c)
            cf, sf = self.tab(f)
            y = y + attention(p.attnF, y, cf, sf, self.rot, b * t, f)
            y = y + feed_forward(p.ffF, y)
            y = y.reshape(t, f, c).permute(1, 0, 2)  # (b f) t c with b = 1
            ct, st = self.tab(t)
            y = y + attention(p.attnT, y, ct, st, self.rot, b * f, t)
            y = y + feed_forward(p.ffT, y)
            x = y.permute(2, 0, 1).unsqueeze(0)  # b c f t
            x = blk.conv2d(x)
            x = blk.norm(x)
            x = F.gelu(x)
            c, f = blk.conv2d.out_channels, f // 2
        x = x.permute(0, 3, 1, 2).reshape(b, t, c * f)
        x = m.frontend.linear(x)
        ct, st = self.tab(t)
        for att, ff in m.transformer_blocks.layers:
            x = attention(att, x, ct, st, self.rot, b, t) + x
            x = feed_forward(ff, x) + x
        x = rms_norm(m.transformer_blocks.norm, x)
        bd = m.task_heads.beat_downbeat_lin(x)
        downbeat = bd[..., 1]
        beat = bd[..., 0] + downbeat
        return beat, downbeat
