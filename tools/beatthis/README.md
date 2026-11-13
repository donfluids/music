# Beat This! for KeyCatch: spec, conversion and live evaluation

Groundwork for replacing or backing up KeyCatch's hand-written beat chain
(OnsetDetector -> TempoTracker -> MeterDetector) with the Beat This! beat/downbeat model
(Foscarin, Schlüter, Widmer, ISMIR 2024, github.com/CPJKU/beat_this, MIT). Nothing here is
used by the app yet. All numbers below were measured with the scripts in this folder unless
marked "read" (taken from upstream docs or code).

Scripts (all run with `python -I`; the official code is put on `sys.path` from a checkout passed
as `--beat-this-src`, downloaded data is only read):

| file | does |
|---|---|
| `bt_common.py` | imports the official package from a checkout; loads checkpoints; counts parameters |
| `inspect_checkpoints.py` | size, sha256, hyperparameters, training-data settings, parameter counts |
| `frontend_np.py` | NumPy log-mel front end and its check against the official `LogMelSpect` |
| `check_resampling.py` | effect of the 44.1/48 kHz -> 22.05 kHz resampler on the input and the beats |
| `bt_export.py` | conversion-friendly forward pass (same weights, no einops, fixed length, FP16-safe norm) |
| `convert_coreml.py` | trace at a fixed length, convert to Core ML (mlprogram, iOS 17) in FP32 and FP16 |
| `bench_speed.py` | CPU time per forward pass, per checkpoint and window |
| `live_eval.py` | the live-use evaluation (prepare / swift / beatthis / score) |
| `compound_proto.py` | simple-vs-compound decision from Beat This! beats with MeterDetector's check |
| `kclive/` | Swift command-line drivers on KeyCatchCore: `kclive` (MusicAnalyzer, 1024-sample chunks), `kconsets` (onset signal dump) |

## 1. Upstream code, checkpoints, licences

Measured (downloaded 2026-11-13):

| item | URL | bytes | sha256 |
|---|---|---|---|
| code | `https://github.com/CPJKU/beat_this.git`, main at `b95c8ab0c58c2d9fcfd40508ae8dffbc05ac4f5c` (2026-05-28); tags `v1.0` = `e3de351a`, `v1.1.0` = `ad797484` | | |
| `small0.ckpt` | `https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/small0.ckpt` | 8,451,101 | `6074be2c4d490c5f6101fcc374a1ec72ae93456e23bb6019783b849f5dc7d47b` |
| `final0.ckpt` | `https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/final0.ckpt` | 81,058,141 | `8c328b45f59d8dd3dff219253ff6a8d6482be57d0133a29140e2febbf8eb8331` |
| `single_small0.ckpt` | same folder | 8,473,753 | `be0627cd323ae9fd1db5105e7c2dd593e9b4cdc0dfc7350f5cc8800af2de0957` |
| `single_final0.ckpt` | same folder | 81,065,078 | `38b96e499575734e71f66fa27538ecc5a24f057bdd67e5228b4efff219ac95dc` |

The checkpoint URL is the one the code itself downloads from (`CHECKPOINT_URL` in
`beat_this/inference.py`); the README's share page is `https://cloud.cp.jku.at/index.php/s/7ik4RrBKTS273gp`.
A WebDAV listing of that folder (measured) holds final0-2, small0-2, single_final0-2,
single_small0-2, **fold0-fold7** (81,054,590 bytes each), hung0-2 and the ablation models.
GitHub has tags v1.0 and v1.1.0; the GitHub releases page could not be opened from this
machine, and the upstream README points only to the JKU cloud for models.

Licences (read): code MIT (`LICENSE`, "Copyright (c) 2024 Institute of Computational
Perception, JKU Linz"; `pyproject.toml` `license = "MIT"`). Weights: the README section added in
commit b95c8ab (2026-05-28, "Clarify license for model checkpoints") says "The code and the
published model weights are released under the MIT license. Note that some of the training
files are fully copyrighted or under limited Creative Commons licenses, and it is up to the
user to assess whether this may impact their use case." Annotations repo
(github.com/CPJKU/beat_this_annotations): MIT. Spectrogram dataset (Zenodo 13922116): CC-BY-4.0.

Parameter counts (measured with `inspect_checkpoints.py`, trainable parameters of the loaded
`BeatThis` module; buffers are BatchNorm statistics and the rotary-embedding cache):

| checkpoint | transformer_dim | parameters | state_dict elements |
|---|---|---|---|
| small0 / single_small0 | 128 | **2,099,960** (so "2.1 M" is right) | 2,101,357 |
| final0 / single_final0 | 512 | **20,251,712** | 20,253,109 |

Training data (measured from each checkpoint's `datamodule_hyper_parameters`, plus the split
logic in `beat_this/dataset/dataset.py` and the split files in beat_this_annotations):

* `final0`, `small0`: `test_dataset="gtzan"`, `no_val=True`, `fold=None`: trained on the train
  **and** val parts of every dataset except GTZAN: asap, ballroom, beatles, candombe, filosax,
  groove_midi, guitarset, hainsworth, harmonix, hjdb, jaah, rwc, simac, smc, tapcorrect.
  So **Ballroom (all 685 annotated tracks) and GuitarSet are in their training data**. GTZAN is not.
* `single_final0`, `single_small0`: `no_val=False`, `fold=None`: trained on the "train" rows
  of each dataset's `single.split` only. Held out: 103 Ballroom tracks (27 of them Waltz or
  Viennese Waltz) and 27 GuitarSet comp tracks.
* `fold0..7`: published (see above); the split files `<dataset>/8-folds.split` are in
  beat_this_annotations, so each fold's held-out tracks are known. Fold models exist only at
  the full size (no small fold models). Not used below (the single split already gives a
  held-out set for both sizes).
* GuitarSet in Beat This! is the 180 **comp** recordings (`*_comp_mix`, the mono pickup mix).
  The 180 **solo** recordings are in no checkpoint's training data. The mic recording of a comp
  take is the same performance as the training mix, so it counts as seen.

## 2. Front end, model I/O and postprocessing (spec for Swift/Kotlin)

Read from `beat_this/preprocessing.py`, `inference.py`, `model/postprocessor.py`, torchaudio 2.7
`MelSpectrogram`/`spectrogram`/`melscale_fbanks`; checked numerically by `frontend_np.py`.

### Audio

* Mono: mean of the channels.
* Resample to **22050 Hz**. Official code: `soxr.resample(x, in_rate=sr, out_rate=22050)`
  (soxr default quality "HQ"). Training audio was resampled with soxr as well.
  On a phone: 44100 -> 22050 is a halving, 48000 -> 22050 is 147/320.
  Measured (`check_resampling.py`, small0, 4 Ballroom/GuitarSet files): a plain Kaiser
  polyphase filter (`scipy.signal.resample_poly`) instead of soxr changes only the top four mel
  bands (centres >= 9.9 kHz; max log-mel difference 2.0-2.6 there, < 0.04 below 9.4 kHz), the
  logits by at most 1.2, and 216 of 223 beats land on the same frame (the rest one frame off).
  So any decent resampler works; one with a passband up to ~10.5 kHz matches soxr best.

### Log-mel spectrogram (`LogMelSpect`)

| parameter | value |
|---|---|
| sample rate | 22050 Hz |
| n_fft / window length | 1024 / 1024 |
| hop | 441 samples (20 ms) -> **50 frames per second** |
| window | periodic Hann, `w[n] = 0.5 - 0.5 cos(2 pi n / 1024)`, n = 0..1023 |
| centring | `center=True`, **reflect** padding of 512 samples at both ends of the signal; frame t is centred on sample 441 t (time t / 50 s) and spans samples 441 t - 512 .. 441 t + 511 |
| frames | T = 1 + floor(N / 441) for N samples |
| spectrum | magnitude, `power=1`: \|X_k\|, k = 0..512 |
| normalisation | `normalized="frame_length"`: \|X_k\| / sqrt(1024) = \|X_k\| / 32 |
| bin frequencies | f_k = k * 11025 / 512 Hz (torchaudio uses `linspace(0, sr // 2, 513)`) |
| mel filters | 128 triangular filters, `mel_scale="slaney"`, `norm=None` (peak 1, **no** area normalisation), f_min 30 Hz, f_max 11000 Hz |
| Slaney mel | m(f) = 3 f / 200 for f < 1000 Hz, else 15 + ln(f / 1000) / (ln(6.4) / 27); inverse f(m) = 200 m / 3 for m < 15, else 1000 exp((m - 15) ln(6.4) / 27) |
| filter edges | 130 points equally spaced in mel from m(30) to m(11000), mapped back to Hz: p_0..p_129. Filter i (0..127) weight at f_k: max(0, min((f_k - p_i) / (p_{i+1} - p_i), (p_{i+2} - f_k) / (p_{i+2} - p_{i+1}))) |
| mel energy | M_i = sum_k W[k, i] \|X_k\| / 32 |
| log | **y = ln(1 + 1000 M_i)** (`log_multiplier=1000`, `torch.log1p`) |
| output | float32, shape (T, 128), time-major |

Measured: `frontend_np.py` (float64 NumPy) vs official (float32 torch) on 5 GTZAN clips:
filterbank max difference 1.3e-5; log-mel max abs difference **1.74e-4** (values 0..8.6,
relative 2.2e-5), mean 2e-6. A float32 implementation should land in the same range.

Live use: every frame needs only its own 1024 samples, so frames can be computed one by one as
audio arrives, keeping the last N frames in a ring. The reflect padding only matters at the very
start and end of a file; live, use only frames whose window is complete (the newest frame is
centred 512 samples before "now") and fill frames before the start of listening with zeros
(= silence, ln(1 + 0) = 0). That is what `live_eval.py` does.

### Model

* Input `(batch, T, 128)` float32 log-mel frames. Any T works (rotary position embeddings, no
  fixed positions); trained on T = 1500 (30 s excerpts). Inside: BatchNorm over the 128 bands,
  a (4x3, stride 4x1) conv stem, three blocks of [frequency-attention + time-attention
  transformer, (2x3, stride 2x1) conv, BatchNorm, GELU] (32/64/128 channels, 32/16/8 bands),
  a linear projection of 256 x 4 to 512 (final) or 128 (small), 6 transformer layers
  (head size 32, gated attention, RMSNorm, rotary embeddings), final RMSNorm, one linear layer
  to 2 values per frame.
* Output: two logits per frame, 50 fps, same T: `beat` and `downbeat`. With the default
  `sum_head`, `beat = raw_beat + downbeat` already, so a downbeat also scores as a beat.
  Probability = sigmoid(logit).
* Long input (official `split_predict_aggregate`): chunks of 1500 frames that overlap by
  2 x 6 frames; the first and last chunk are zero-padded by 6 frames; 6 frames ("border") are
  dropped from each side of every chunk's output; on overlaps the earlier chunk wins. A piece
  shorter than 1500 frames is one chunk, padded with 6 zero frames on each side, and the 6
  border frames are dropped again. The live evaluation does the same for each window:
  input = 6 zero frames + the last N = 50 W frames + 6 zero frames (N + 12 frames in total).

### Postprocessing ("minimal", the default; no DBN)

For the beat and downbeat logit sequences separately:

1. A frame is a peak if its logit equals the maximum over the 7 frames centred on it
   (`max_pool1d(kernel 7, stride 1, padding 3)`, i.e. +-3 frames = +-60 ms; padding acts as
   -inf) **and** the logit is > 0 (probability > 0.5).
2. Runs of peak frames that are each at most 1 frame apart are replaced by their mean frame
   (can be fractional, e.g. 412.5).
3. Time = frame / 50 s.
4. Each downbeat is moved to the nearest beat time (`argmin |beat - downbeat|`), then duplicate
   downbeat times are removed (`np.unique`, so the result is sorted).

Output: beat times and downbeat times in seconds. Beat This! gives no tempo, meter or
beat-in-bar numbers; `utils.infer_beat_numbers` counts beats from each downbeat for files.

### What KeyCatch needs on top (as used in the evaluation, `live_eval.py: bt_readouts`)

* tempo: 60 / median of the last 8 inter-beat intervals in the window (at least 3 beats).
* last beat: the newest picked beat; beat period: that median interval. The next beat is
  extrapolated as last + k * period.
* beats per bar ("comb", default): for 2, 3 and 4 beats per bar and every phase, the mean
  downbeat probability (sigmoid of the downbeat logit at each picked beat, max over +-1 frame)
  on the bar grid minus the mean on the other beats; these scores decay by half at each
  readout (once a second) so a few windows are pooled; the best bar length wins. A naive
  alternative ("peaks", `--meter peaks`) counts beats between consecutive official downbeats;
  it is much worse in short windows because one missed or extra downbeat changes the count.
* simple vs compound: Beat This! does not label it; see section 5.

## 3. Core ML conversion

Measured with `convert_coreml.py` (coremltools 9.0, torch 2.7.1+cpu, Linux; a fixed input of
412 frames = W 8 s + 2 x 6 border frames).

The official module does not convert as is: tracing it fails in coremltools on einops'
shape arithmetic (`ERROR - converting 'int' op ... TypeError: only 0-dimensional arrays can be
converted to Python scalars`), and the rotary embedding's cache makes `torch.jit.trace`'s check
fail. `bt_export.py` re-expresses the same forward pass with plain permute/reshape, static
shapes, precomputed rotary cos/sin tables and explicit attention. It uses the official
module's weights directly; max difference to the official eager model on real music:
4.7e-5 (small0), 6.7e-5 (final0) in logits.

FP16 needs one change: in final0 the sum of squares inside some RMSNorms reaches **6.6e5**
on real music (max |activation| 214; measured), beyond float16's 65504, so a plain
`sum(x*x)` overflows in an FP16 graph. small0 peaks at 3.1e4 (under the limit, but only by 2x).
`bt_export.rms_norm` divides each vector by its largest magnitude before squaring
(mathematically identical, exact in FP32).

| model | precision | converts | .mlpackage size | ops | FP16 stand-in vs official FP32 (30 windows of GTZAN music) |
|---|---|---|---|---|---|
| small0, 412 frames | FP32 | yes | 8.77 MB | 1756 | - |
| small0, 412 frames | FP16 | yes | 4.54 MB | 1758 | max logit diff 0.24; 8 of 24,720 frame logits change sign; beat peaks 476 vs 477, 474 identical |
| final0, 412 frames | FP32 | yes | 81.38 MB | 1758 | - |
| final0, 412 frames | FP16 | yes | 40.85 MB | 1760 | max logit diff 0.12; 7 of 24,720 change sign; beat peaks 474 vs 474, all identical |

Ops in the converted programs: linear, matmul, softmax, conv (4), batch_norm (1), gelu,
sigmoid, reduce_sum/reduce_max/sqrt/real_div (norms), reshape/transpose/slice/stack.
Core ML cannot run on Linux and coremltools has no MIL interpreter, so the Core ML models
themselves were **not** run. The FP16 column is the export module run in torch float16 on the
CPU (the same op sequence as the Core ML graph), which is only a stand-in for Core ML's FP16
arithmetic (Core ML / the Neural Engine may accumulate differently). Before shipping, check the
.mlpackage on a Mac against the reference logits.

## 4. Speed (CPU, PyTorch)

Measured with `bench_speed.py`: Intel Xeon @ 2.10 GHz (4 vCPU, cloud VM), torch 2.7.1+cpu,
one forward pass of W s of frames + 12 border frames, median (min) of 15 runs after 3 warm-ups.

SPEED_TABLE

small0 is only about 1.3-2x faster than final0 although it has a tenth of the parameters:
most of its time goes to the frequency/time attention of the front end at full frame rate
(shared by both sizes). Cost grows faster than linearly with W (attention is quadratic in T).
Batching several windows was slower per window than one at a time on this CPU (measured:
small0 batch 1/4/8: 170/215/256 ms per window, 1 thread). The log-mel front end is negligible.
A phone CPU core is typically slower than this server core, and running once a second leaves
a real-time budget of 1000 ms per run.

## 5. Live evaluation

RESULTS

## Reproducing

```
S=<scratch dir>; V=$S/venv/bin/python   # numpy, torch 2.7.1+cpu, torchaudio, einops, soxr,
                                        # soundfile, rotary-embedding-torch, mir_eval, scipy, coremltools 9.0
git clone https://github.com/CPJKU/beat_this.git $S/dl/beat_this_src
git clone https://github.com/CPJKU/beat_this_annotations.git $S/dl/annotations
for m in small0 final0 single_small0 single_final0; do
  curl -L -o $S/dl/ckpt/$m.ckpt https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/$m.ckpt; done
# GTZAN audio: https://huggingface.co/datasets/marsyas/gtzan/resolve/main/data/genres.tar.gz
# GTZAN-Rhythm v2 (meter, ternary, swing): http://anasynth.ircam.fr/home/system/files/attachment_uploads/marchand/private/GTZAN-Rhythm_v2_ismir2015_lbd_2015-10-28.tar_.gz
# Ballroom audio: https://mtg.upf.edu/ismir2004/contest/tempoContest/data1.tar.gz
# GuitarSet: https://zenodo.org/records/3371780 (annotation.zip, audio_mono-mic.zip)

$V -I inspect_checkpoints.py --beat-this-src $S/dl/beat_this_src $S/dl/ckpt/*.ckpt
$V -I frontend_np.py --beat-this-src $S/dl/beat_this_src <some .wav files>
$V -I convert_coreml.py --beat-this-src $S/dl/beat_this_src --ckpt $S/dl/ckpt/final0.ckpt --frames 412 --out $S/coreml --audio <wavs>
$V -I bench_speed.py --beat-this-src $S/dl/beat_this_src --ckpt $S/dl/ckpt/small0.ckpt $S/dl/ckpt/final0.ckpt

# Swift drivers: copy kclive/ outside the repo, then
KCORE=<repo>/ios/KeyCatchCore swift build -c release --scratch-path $S/kc-beat

E=$S/eval
$V -I live_eval.py --work $E prepare --gtzan-audio .../genres --gtzan-ann $S/dl/annotations/gtzan/annotations/beats \
   --gtzan-rhythm .../GTZAN-Rhythm_v2_ismir2015_lbd/stats.csv --ballroom-audio .../BallroomData \
   --ballroom-ann $S/dl/annotations/ballroom/annotations/beats --ballroom-split $S/dl/annotations/ballroom/single.split \
   --guitarset-audio .../mic --guitarset-ann .../annotation --guitarset-split $S/dl/annotations/guitarset/single.split
$V -I live_eval.py --work $E swift --kclive $S/kc-beat/release/kclive
$V -I live_eval.py --work $E beatthis --beat-this-src $S/dl/beat_this_src --ckpt small0=... final0=...
$V -I live_eval.py --work $E beatthis --beat-this-src $S/dl/beat_this_src --ckpt single_small0=... single_final0=... --datasets ballroom guitarset_comp
$V -I live_eval.py --work $E score [--meter peaks]
# compound check: dump onsets with kconsets (list lines: <clip_44100.f32> <out.f32> 44100 band) into $E/onsets, then
$V -I compound_proto.py --work $E
```
