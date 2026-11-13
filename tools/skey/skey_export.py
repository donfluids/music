"""A conversion-friendly forward pass of S-KEY's ChromaNet: the same weights and arithmetic as the
official module, with static shapes. The official one does not convert: layer_norm's
normalized_shape is taken from x.shape at run time and einops' rearrange in OctavePool become
shape arithmetic that coremltools' `int` cast cannot handle ("only 0-dimensional arrays can be
converted to Python scalars").

Input (1, 1, 84, T): the VQT in dB scaled as the official VQT.forward does ((dB / 80) + 1,
clipped 80 dB under the window's peak), bins 0..83 (A0 upwards). Output: 24 probabilities in
the model's own order (key_map in skey/key_detection.py: 0..11 A..G# major, 12..23 B..A# minor).
"""
import torch
from torch import nn
import torch.nn.functional as F


def layer_norm_chw(x, eps=1e-5):
    """F.layer_norm(x, x.shape[1:]) without affine: over channels, bins and frames together."""
    mean = x.mean(dim=(1, 2, 3), keepdim=True)
    d = x - mean
    var = (d * d).mean(dim=(1, 2, 3), keepdim=True)
    return d / torch.sqrt(var + eps)


class ExportChromaNet(nn.Module):
    """fold_bn: fold the final BatchNorm (eval, no affine) into the 1x1 classifier here, as
    weight / sigma and (bias - mean) / sigma; coremltools does the same fold itself when it is
    left as (x - mean) / sigma, so both give the same program.

    Convert each precision from a fresh copy of the model (copy.deepcopy) or a fresh process:
    measured with coremltools 9.0 / torch 2.7.1, a second ct.convert of the same module in one
    process produced a program whose folded classifier bias was (b + mean) / sigma, i.e. wrong
    output, while the first conversion was right."""

    def __init__(self, net, fold_bn=False):
        super().__init__()
        self.net = net
        self.fold_bn = fold_bn
        bn = net.batch_norm
        inv = 1.0 / torch.sqrt(bn.running_var + bn.eps)
        self.register_buffer("cls_w", (net.classifier.weight * inv.view(-1, 1, 1, 1)).detach().clone())
        self.register_buffer("cls_b", ((net.classifier.bias - bn.running_mean) * inv).detach().clone())

    def forward(self, x):
        n = self.net
        for cb, tb in zip(n.convnext_blocks, n.time_downsampling_blocks):
            x = F.gelu(tb.conv(layer_norm_chw(x)))
            inp = x
            k = cb.dwconv.kernel_size[0] // 2
            y = F.pad(x, (k, k, k, k), mode="replicate")
            y = F.conv2d(y, cb.dwconv.weight, cb.dwconv.bias, groups=cb.dwconv.groups)
            y = layer_norm_chw(y)
            y = y.permute(0, 2, 3, 1)
            y = cb.pwconv2(F.gelu(cb.pwconv1(y)))
            y = cb.gamma * y
            x = inp + y.permute(0, 3, 1, 2)
        # Shapes as constants (batch 1, 84 bins = 7 octaves of 12): a shape read from x would trace
        # to the int ops coremltools cannot convert.
        x = x.reshape(1, n.out_channels[-1], 7, 12, -1).mean(dim=2)  # OctavePool: average the 7 octaves
        x = x.mean(dim=3, keepdim=True)                          # AdaptiveAvgPool2d((12, 1))
        if self.fold_bn:
            x = F.conv2d(x, self.cls_w, self.cls_b)              # classifier + BatchNorm, (B, 2, 12, 1)
        else:
            x = n.classifier(x)
            bn = n.batch_norm
            x = (x - bn.running_mean.view(1, -1, 1, 1)) / torch.sqrt(bn.running_var.view(1, -1, 1, 1) + bn.eps)
        x = x.reshape(1, 24)
        return torch.softmax(x / n.temperature, dim=-1)
