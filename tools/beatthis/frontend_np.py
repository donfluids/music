"""NumPy reimplementation of the Beat This! front end (resampling aside) and its check.

Front end (beat_this/preprocessing.py LogMelSpect, torchaudio MelSpectrogram underneath):
  22050 Hz mono -> centred STFT (n_fft 1024, hop 441, periodic Hann 1024, reflect padding 512)
  -> |X| / sqrt(1024) -> 128 triangular mel filters (Slaney mel scale, 30..11000 Hz, no area
  normalisation) -> log1p(1000 * mel). Output (frames, 128), 50 frames per second.

    python -I frontend_np.py --beat-this-src DIR audio1.wav [audio2.wav ...]

prints the largest absolute and relative difference against the official LogMelSpect.
"""
import argparse
import os
import sys

import numpy as np

SR = 22050
N_FFT = 1024
HOP = 441
N_MELS = 128
F_MIN = 30.0
F_MAX = 11000.0
LOG_MULT = 1000.0


def hz_to_mel_slaney(f):
    f = np.asarray(f, dtype=np.float64)
    mel = 3.0 * f / 200.0
    logstep = np.log(6.4) / 27.0
    return np.where(f >= 1000.0, 15.0 + np.log(np.maximum(f, 1e-10) / 1000.0) / logstep, mel)


def mel_to_hz_slaney(m):
    m = np.asarray(m, dtype=np.float64)
    logstep = np.log(6.4) / 27.0
    return np.where(m >= 15.0, 1000.0 * np.exp(logstep * (m - 15.0)), 200.0 * m / 3.0)


def mel_filterbank(sr=SR, n_fft=N_FFT, n_mels=N_MELS, f_min=F_MIN, f_max=F_MAX):
    """(n_fft//2+1, n_mels) matrix, as torchaudio.functional.melscale_fbanks(norm=None, mel_scale='slaney')."""
    n_freqs = n_fft // 2 + 1
    # torchaudio uses linspace(0, sr // 2, n_freqs): integer Nyquist (11025 for 22050).
    all_freqs = np.linspace(0, sr // 2, n_freqs)
    m_pts = np.linspace(hz_to_mel_slaney(f_min), hz_to_mel_slaney(f_max), n_mels + 2)
    f_pts = mel_to_hz_slaney(m_pts)
    f_diff = f_pts[1:] - f_pts[:-1]
    slopes = f_pts[None, :] - all_freqs[:, None]
    down = -slopes[:, :-2] / f_diff[:-1]
    up = slopes[:, 2:] / f_diff[1:]
    return np.maximum(0.0, np.minimum(down, up))


def periodic_hann(n=N_FFT):
    return 0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(n) / n)


_FB = None


def frames_magnitude(x, center=True):
    """|STFT| / sqrt(n_fft), shape (frames, 513). Frame t is centred on sample t*441."""
    x = np.asarray(x, dtype=np.float64)
    if center:
        x = np.pad(x, (N_FFT // 2, N_FFT // 2), mode="reflect")
    n = 1 + (len(x) - N_FFT) // HOP
    idx = np.arange(N_FFT)[None, :] + HOP * np.arange(n)[:, None]
    spec = np.fft.rfft(x[idx] * periodic_hann()[None, :], axis=1)
    return np.abs(spec) / np.sqrt(N_FFT)


def logmel(x, center=True):
    """Log-mel spectrogram, (frames, 128) float32, of a 22050 Hz mono signal."""
    global _FB
    if _FB is None:
        _FB = mel_filterbank()
    return np.log1p(LOG_MULT * (frames_magnitude(x, center) @ _FB)).astype(np.float32)


def main():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import soundfile as sf
    import soxr
    import torch

    from bt_common import import_beat_this

    ap = argparse.ArgumentParser()
    ap.add_argument("--beat-this-src", required=True)
    ap.add_argument("audio", nargs="+")
    a = ap.parse_args()
    _, _, pre = import_beat_this(a.beat_this_src)
    official = pre.LogMelSpect()
    # compare the filterbank itself
    fb_t = official.spect_class.mel_scale.fb.numpy()
    print("filterbank max abs diff vs torchaudio: %.3g" % np.abs(fb_t - mel_filterbank()).max())
    worst = 0.0
    worst_rel = 0.0
    for path in a.audio:
        x, sr = sf.read(path, dtype="float64", always_2d=True)
        x = x.mean(1)
        if sr != SR:
            x = soxr.resample(x, sr, SR)
        ref = official(torch.tensor(x, dtype=torch.float32)).numpy()
        mine = logmel(x)
        d = np.abs(ref - mine)
        rel = d.max() / np.abs(ref).max()
        worst = max(worst, d.max())
        worst_rel = max(worst_rel, rel)
        print("%s frames=%d max_abs=%.3g (ref range 0..%.2f) mean_abs=%.3g" % (os.path.basename(path), len(ref), d.max(), ref.max(), d.mean()))
    print("largest abs diff over all files: %.3g, relative to max value: %.3g" % (worst, worst_rel))


if __name__ == "__main__":
    main()
