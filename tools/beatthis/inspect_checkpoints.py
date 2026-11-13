"""Prints size, sha256, hyperparameters, training data and parameter counts of Beat This! checkpoints.

    python -I inspect_checkpoints.py --beat-this-src DIR ckpt/final0.ckpt ckpt/small0.ckpt ...
"""
import argparse
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import torch  # noqa: E402

from bt_common import count_params, load_model  # noqa: E402


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--beat-this-src", required=True)
    ap.add_argument("ckpts", nargs="+")
    a = ap.parse_args()
    for path in a.ckpts:
        ck = torch.load(path, map_location="cpu", weights_only=True)
        model = load_model(a.beat_this_src, path)
        n, nb = count_params(model)
        sd = ck["state_dict"]
        stored = sum(v.numel() for v in sd.values())
        dtypes = sorted({str(v.dtype) for v in sd.values()})
        hp = ck.get("hyper_parameters", {})
        dm = ck.get("datamodule_hyper_parameters", {})
        print(json.dumps({
            "file": os.path.basename(path),
            "bytes": os.path.getsize(path),
            "sha256": sha256(path),
            "trainable_parameters": n,
            "buffers": nb,
            "state_dict_elements": stored,
            "state_dict_dtypes": dtypes,
            "keys": sorted(k for k in ck.keys()),
            "hyper_parameters": {k: (v if isinstance(v, (int, float, str, bool, type(None), dict, list)) else str(v)) for k, v in hp.items()},
            "datamodule_hyper_parameters": {k: (v if isinstance(v, (int, float, str, bool, type(None), dict, list)) else str(v)) for k, v in dm.items()},
        }, indent=1, default=str))


if __name__ == "__main__":
    main()
