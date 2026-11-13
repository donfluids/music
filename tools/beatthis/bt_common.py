"""Shared helpers for the Beat This! evaluation scripts.

The official beat_this package is imported from a checkout given on the command line
(--beat-this-src), so these scripts can run with `python -I` and never pick up code from
the current directory.
"""
import sys
from pathlib import Path


def import_beat_this(src):
    """Puts the official beat_this checkout on sys.path and returns its modules."""
    src = str(Path(src).resolve())
    if src not in sys.path:
        sys.path.insert(0, src)
    import beat_this.inference as inference  # noqa: E402
    import beat_this.model.postprocessor as postprocessor  # noqa: E402
    import beat_this.preprocessing as preprocessing  # noqa: E402
    return inference, postprocessor, preprocessing


def load_model(src, ckpt_path, device="cpu"):
    inference, _, _ = import_beat_this(src)
    return inference.load_model(str(ckpt_path), device)


def count_params(model):
    total = sum(p.numel() for p in model.parameters())
    buffers = sum(b.numel() for b in model.buffers())
    return total, buffers
