"""Stores the biases of an FP16-weight .tflite as float16 too, each behind its own DEQUANTIZE.

    python -I fp16_biases.py IN.tflite OUT.tflite

ai-edge-quantizer's float casting (convert_tflite.py) leaves the biases of FULLY_CONNECTED,
CONV_2D and DEPTHWISE_CONV_2D in float32. LiteRT 1.4's XNNPACK delegate then declines every such
op whose weights are float16 but whose bias is float32: in the GPU-friendly small0 graph that is
38 of 86 FULLY_CONNECTED and all 4 convolutions, which run outside XNNPACK in 42 pieces and make
a window 2.5x slower than the FP32 file (LiteRT 2.3 takes them as they are). With the biases in
float16 as well, LiteRT 1.4 delegates the whole graph. The biases are a few kilobytes, so the
rounding changes the logits by about as much as the float16 weights already do.
"""
import sys

import numpy as np
from ai_edge_litert import schema_py_generated as S

OPS = {S.BuiltinOperator.FULLY_CONNECTED, S.BuiltinOperator.CONV_2D, S.BuiltinOperator.DEPTHWISE_CONV_2D}


def main(src, dst):
    raw = open(src, "rb").read()
    m = S.ModelT.InitFromPackedBuf(raw, 0)
    # litert-torch stores the constants after the flatbuffer (offset and size): bring them in.
    for buf in m.buffers:
        if getattr(buf, "offset", 0) and buf.size:
            buf.data = np.frombuffer(raw[buf.offset:buf.offset + buf.size], dtype=np.uint8)
            buf.offset = 0
            buf.size = 0
    codes = [max(c.builtinCode, c.deprecatedBuiltinCode) for c in m.operatorCodes]
    dq = codes.index(S.BuiltinOperator.DEQUANTIZE)
    changed = 0
    for g in m.subgraphs:
        producer = {t: op for op in g.operators for t in op.outputs}
        new_ops = []
        for op in g.operators:
            if codes[op.opcodeIndex] not in OPS or len(op.inputs) < 3 or op.inputs[2] < 0:
                continue
            w = producer.get(op.inputs[1])
            if w is None or codes[w.opcodeIndex] != S.BuiltinOperator.DEQUANTIZE:
                continue  # weights not float16
            b = g.tensors[op.inputs[2]]
            data = m.buffers[b.buffer].data
            if b.type != S.TensorType.FLOAT32 or data is None or len(data) == 0:
                continue  # not a constant float32 bias
            values = np.frombuffer(bytes(bytearray(data)), dtype="<f4")
            buf = S.BufferT()
            buf.data = np.frombuffer(values.astype("<f2").tobytes(), dtype=np.uint8)
            m.buffers.append(buf)
            half = S.TensorT()
            half.shape = list(b.shape)
            half.type = S.TensorType.FLOAT16
            half.buffer = len(m.buffers) - 1
            half.name = (b.name or b"bias") + b"_fp16"
            g.tensors.append(half)
            full = S.TensorT()
            full.shape = list(b.shape)
            full.type = S.TensorType.FLOAT32
            full.buffer = 0
            full.name = (b.name or b"bias") + b"_dequantized"
            g.tensors.append(full)
            d = S.OperatorT()
            d.opcodeIndex = dq
            d.inputs = [len(g.tensors) - 2]
            d.outputs = [len(g.tensors) - 1]
            new_ops.append(d)
            op.inputs = list(op.inputs)
            op.inputs[2] = len(g.tensors) - 1
            changed += 1
        # The new DEQUANTIZE ops read constants only, so they can all run first.
        g.operators = new_ops + list(g.operators)
    b = __import__("flatbuffers").Builder(0)
    b.Finish(m.Pack(b), file_identifier=b"TFL3")
    open(dst, "wb").write(b.Output())
    print(f"{changed} biases stored as float16")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
