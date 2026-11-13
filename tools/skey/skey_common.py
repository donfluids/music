"""Shared helpers: import the official S-KEY code from a checkout, load the model, key indices.

Key index convention used everywhere in these scripts (same as KeyCatch's Key(tonic, mode)):
  k = tonic for major (C=0 .. B=11), k = 12 + tonic for minor.
"""
import os
import sys

import numpy as np

PITCH = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]


def import_skey(src):
    src = os.path.abspath(src)
    if src not in sys.path:
        sys.path.insert(0, src)
    import skey.key_detection as kd  # noqa: E402
    return kd


def key_name(k):
    if k is None or k < 0:
        return "-"
    return PITCH[k % 12] + ("m" if k >= 12 else "")


def parse_name(s):
    """'C# Major', 'Bb minor', 'A:minor', 'Eb major', 'F#m' -> index."""
    s = s.strip().replace(":", " ")
    names = {"C": 0, "C#": 1, "DB": 1, "D": 2, "D#": 3, "EB": 3, "E": 4, "FB": 4, "E#": 5, "F": 5, "F#": 6, "GB": 6,
             "G": 7, "G#": 8, "AB": 8, "A": 9, "A#": 10, "BB": 10, "B": 11, "CB": 11, "B#": 0}
    parts = s.split()
    if len(parts) == 1:
        p = parts[0]
        minor = p.endswith("m") and not p.endswith("bm") or p.endswith("min")
        t = p.rstrip("min") if p.endswith("min") else (p[:-1] if p.endswith("m") else p)
        return names[t.upper()] + (12 if minor else 0)
    t, m = parts[0], parts[1].lower()
    return names[t.upper()] + (12 if m.startswith("min") else 0)


def skey_index_to_key(kd):
    """Map the model's 24 output indices to our key index, via its own key_map strings."""
    return np.array([parse_name(kd.key_map[i]) for i in range(24)])


def relative(k):
    return (k + 9) % 12 + 12 if k < 12 else (k - 12 + 3) % 12


def parallel(k):
    return k + 12 if k < 12 else k - 12


def mirex(est, ref):
    """MIREX weighted key score: 1 same, 0.5 fifth (same mode), 0.3 relative, 0.2 parallel, 0 else."""
    if est is None or est < 0:
        return 0.0
    if est == ref:
        return 1.0
    if (est >= 12) == (ref >= 12) and (est - ref) % 12 in (5, 7):
        return 0.5
    if est == relative(ref):
        return 0.3
    if est == parallel(ref):
        return 0.2
    return 0.0


def category(est, ref):
    if est is None or est < 0:
        return "none"
    if est == ref:
        return "exact"
    if (est >= 12) == (ref >= 12) and (est - ref) % 12 in (5, 7):
        return "fifth"
    if est == relative(ref):
        return "relative"
    if est == parallel(ref):
        return "parallel"
    return "other"


class SKey:
    """The official VQT + ChromaNet with the published checkpoint. probs(audio) -> 24 probabilities in our key order."""

    def __init__(self, src, ckpt=None, threads=None):
        import torch
        if threads:
            torch.set_num_threads(threads)
        self.torch = torch
        self.kd = import_skey(src)
        ck = self.kd.load_checkpoint(ckpt)
        self.sr = ck["audio"]["sr"]
        self.hcqt, self.net, self.crop = self.kd.load_model_components(ck, torch.device("cpu"))
        self.order = skey_index_to_key(self.kd)  # model index -> our index

    def probs(self, audio, normalize=True):
        """audio: 1-D float array at self.sr. Peak-normalised like the official load_audio."""
        torch = self.torch
        x = np.asarray(audio, dtype=np.float32)
        if normalize:
            m = np.abs(x).max()
            if m > 0:
                x = x / m
        with torch.no_grad():
            t = torch.from_numpy(x)[None, None, :]  # (batch 1, channel 1, samples) like infer_key's batch.unsqueeze(0)
            p = self.net(self.crop(self.hcqt(t), torch.zeros(1))).mean(0).numpy()
        out = np.zeros(24)
        out[self.order] = p
        return out
