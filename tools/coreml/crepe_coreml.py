"""Builds CREPE-tiny as a Core ML program from KeyCatch's CRPE weights file.

Input "frame": 1024 samples at 16 kHz, normalised to zero mean and unit variance (the app does
that). Output "activations": the 360 pitch-bin sigmoids. Same layers as the Kotlin port:
"same" convolutions (512 taps stride 4, then 64 taps), ReLU, folded batch norm, max-pool by two,
time-major flattening and a dense layer.
"""
import sys
sys.path.insert(0, sys.argv[3])
import numpy as np
import coremltools as ct
from coremltools.converters.mil import Builder as mb
from crepe_np import read_weights

layers, W, bias = read_weights(sys.argv[1])

@mb.program(input_specs=[mb.TensorSpec(shape=(1, 1024))], opset_version=ct.target.iOS17)
def crepe(frame):
    x = mb.reshape(x=frame, shape=[1, 1, 1024])
    for li, l in enumerate(layers):
        if li == 0:
            L = 1024 // l['stride']
            total = (L - 1) * l['stride'] + l['width'] - 1024
        else:
            total = l['width'] - 1
        before = total // 2 if li == 0 else (l['width'] - 1) // 2
        after = total - before
        x = mb.conv(x=x, weight=l['kernel'], bias=l['bias'], strides=[l['stride']], pad_type="custom", pad=[before, after])
        x = mb.relu(x=x)
        x = mb.mul(x=x, y=l['scale'].reshape(1, -1, 1))
        x = mb.add(x=x, y=l['shift'].reshape(1, -1, 1))
        x = mb.max_pool(x=x, kernel_sizes=[2], strides=[2], pad_type="valid")
    x = mb.transpose(x=x, perm=[0, 2, 1])      # (1, positions, channels): time-major
    x = mb.reshape(x=x, shape=[1, -1])
    x = mb.linear(x=x, weight=W, bias=bias)
    return mb.sigmoid(x=x, name="activations")

model = ct.convert(crepe, convert_to="mlprogram", minimum_deployment_target=ct.target.iOS17,
                   compute_precision=ct.precision.FLOAT16)
model.short_description = "CREPE-tiny pitch tracker (Kim, Salamon, Li, Bello 2018; MIT licence). Input: 1024 normalised samples at 16 kHz."
model.input_description["frame"] = "1024 samples at 16 kHz, zero mean, unit variance"
model.output_description["activations"] = "360 pitch-bin probabilities, 20 cents apart from about 32.7 Hz"
model.save(sys.argv[2])
print("saved", sys.argv[2])
