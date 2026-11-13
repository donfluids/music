"""CREPE-tiny from KeyCatch's CRPE weights file, in NumPy, checked against the reference activations."""
import struct, json, sys
import numpy as np

def read_weights(path):
    b = open(path, 'rb').read(); pos = 0
    def u16():
        nonlocal pos; v = struct.unpack_from('<H', b, pos)[0]; pos += 2; return v
    def halves(n):
        nonlocal pos; a = np.frombuffer(b, dtype='<f2', count=n, offset=pos).astype(np.float32); pos += 2 * n; return a
    assert b[:4] == b'CRPE'; pos = 4
    assert u16() == 1
    count = u16(); layers = []
    for _ in range(count):
        width, cin, cout, stride = u16(), u16(), u16(), u16()
        k = halves(cout * cin * width).reshape(cout, cin, width)
        layers.append(dict(width=width, cin=cin, cout=cout, stride=stride, kernel=k, bias=halves(cout), scale=halves(cout), shift=halves(cout)))
    inputs, outputs = u16(), u16()
    W = halves(outputs * inputs).reshape(outputs, inputs); bias = halves(outputs)
    return layers, W, bias

def conv_same(x, k, stride, before, after):
    # x: (cin, L); k: (cout, cin, w); correlation with zero padding.
    xp = np.pad(x, ((0, 0), (before, after)))
    cout, cin, w = k.shape
    n = (xp.shape[1] - w) // stride + 1
    cols = np.stack([xp[:, i * stride: i * stride + w] for i in range(n)], axis=0)  # (n, cin, w)
    return np.einsum('ncw,dcw->dn', cols, k)

def forward(frame, layers, W, bias):
    x = frame[None, :].astype(np.float64)
    for li, l in enumerate(layers):
        if li == 0:
            L = len(frame) // l['stride']
            before = ((L - 1) * l['stride'] + l['width'] - len(frame)) // 2
            after = (L - 1) * l['stride'] + l['width'] - len(frame) - before
        else:
            before = (l['width'] - 1) // 2; after = l['width'] - 1 - before
        y = conv_same(x, l['kernel'].astype(np.float64), l['stride'], before, after) + l['bias'][:, None]
        y = np.maximum(y, 0) * l['scale'][:, None] + l['shift'][:, None]
        y = np.maximum(y[:, 0::2][:, : y.shape[1] // 2], y[:, 1::2][:, : y.shape[1] // 2])
        x = y
    flat = x.T.reshape(-1)  # time-major: position * channels + channel
    z = W.astype(np.float64) @ flat + bias
    return 1 / (1 + np.exp(-z)), [ (l['width'], l['stride']) for l in layers ]

if __name__ == '__main__':
    layers, W, bias = read_weights(sys.argv[1])
    ref = json.load(open(sys.argv[2]))
    for f, a in zip(ref['frames'], ref['activations']):
        f = np.array(f, dtype=np.float64); f = (f - f.mean()) / max(f.std(), 1e-8)
        got, shapes = forward(f, layers, W, bias)
        print('max diff', float(np.abs(got - np.array(a)).max()), 'peak', int(np.argmax(got)), int(np.argmax(a)))
    print([ (l['cin'], l['cout'], l['width'], l['stride']) for l in layers ], W.shape)
