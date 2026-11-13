# Beat This! for KeyCatch: spec, conversion and live evaluation

Groundwork for replacing or backing up KeyCatch's hand-written beat chain
(OnsetDetector -> TempoTracker -> MeterDetector) with the Beat This! beat/downbeat model
(Foscarin, Schlüter, Widmer, ISMIR 2024, github.com/CPJKU/beat_this, MIT). The iPhone app
follows this spec (section 7); the Android app does not use it yet. All numbers below were
measured with the scripts in this folder unless marked "read" (taken from upstream docs or code).

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
| `compound_proto.py` | simple-vs-compound decision from Beat This! beats with MeterDetector's check, and how separable the evidence is |
| `bt_live.py` | the reference for the apps' live reading: postprocessing, `Reader` (tempo, last beat, comb, bar position, lock), `Feel` (the shipped compound decision) |
| `make_fixtures.py` | writes the reference data for the Swift tests (front end, readings, compound, Core ML logits, end to end) |
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

| checkpoint | W (s) | input frames | 1 thread median (min) ms | 4 threads median (min) ms | NumPy log-mel of W s, ms |
|---|---|---|---|---|---|
| small0 | 6 | 312 | 116.0 (113.1) | 51.7 (45.0) | 2.3 |
| small0 | 8 | 412 | 176.1 (171.6) | 76.3 (71.8) | 2.9 |
| small0 | 12 | 612 | 344.2 (331.7) | 140.9 (123.2) | 5.7 |
| final0 | 6 | 312 | 221.9 (207.7) | 91.7 (79.1) | 2.3 |
| final0 | 8 | 412 | 304.8 (279.7) | 99.2 (95.8) | 3.0 |
| final0 | 12 | 612 | 471.2 (451.4) | 158.4 (153.9) | 6.1 |

small0 is only 1.4-1.9x faster than final0 on one thread (1.1-1.8x on four) although it has a tenth of the parameters:
most of its time goes to the frequency/time attention of the front end at full frame rate
(shared by both sizes). Cost grows faster than linearly with W (attention is quadratic in T).
Batching several windows was slower per window than one at a time on this CPU (measured:
small0 batch 1/4/8: 170/215/256 ms per window, 1 thread). The log-mel front end is negligible.
Running once a second leaves 1000 ms per run; phone speeds were not measured (a phone core is
usually slower than this server core). For comparison, KeyCatch's whole Swift MusicAnalyzer
(Band preset, everything it does) took 13 ms per second of audio here (measured in `kclive`,
4 processes in parallel).

## 5. Live evaluation

### Setup (what `live_eval.py` does)

* **Clips.** GTZAN (held out by small0 and final0, so fair): a stratified random sample of 30
  clips per genre (300; jazz.00054 is unreadable and has no annotation), plus every clip that
  GTZAN-Rhythm v2 labels 3/4 (44) or ternary 12/8 / 9/8 (31); 351 clips in all, 30 s each.
  Beats/downbeats: beat_this_annotations `gtzan` (the GTZAN-Rhythm-derived set the paper uses).
  Ballroom: the 103 clips of the **val** part of `single.split` (27 Waltz/Viennese Waltz),
  annotations from beat_this_annotations. GuitarSet (no drums, mono mic audio): the 27 comp
  takes of the val part of `single.split`, and every third solo take (60 of 180, 12 per style),
  beats from the JAMS `beat_position` annotations. All GuitarSet takes are 4/4.
* **Who saw what.** GTZAN: no checkpoint. Ballroom val and GuitarSet comp val: seen by small0 and
  final0 (marked **trained** in the tables), held out from single_small0 and single_final0
  (same architecture, trained on the rest of the data). GuitarSet solo: seen by none.
  Ballroom has been reported in the literature to contain a few duplicate recordings (not
  checked here); if any straddle the split, the single_* Ballroom numbers are slightly optimistic.
* **Swift (the app as it is).** `kclive` feeds 44.1 kHz mono (soxr from the files' rates) to
  `MusicAnalyzer(sampleRate: 44100, preset:)` in 1024-sample chunks with
  `nowNanos` = clock time of the chunk's last sample, and reads `result` after the chunk that
  reaches each whole second from 4 s: `bpm`, `beatsPerBar`, `lastBeatAtNanos`,
  `beatPeriodMillis`, `meterCompound`. Band preset for GTZAN and Ballroom, Guitar preset for
  GuitarSet. The other preset gave numbers within ~3 points, except on the waltzes, where Guitar
  did better than Band (tempo ±4 % 26.1 vs 21.7, with x2 43.1 vs 34.1). At 48 kHz (GTZAN sample,
  Band) the numbers were the same within 1.2 points (64.7 / 81.2 / 43.6 / 74.6 vs
  65.2 / 81.6 / 43.8 / 75.8).
* **Beat This live.** At each moment t (every 1 s from 4 s): the newest W x 50 complete log-mel
  frames before t (zeros before the clip starts), plus 6 zero frames on each side, one forward
  pass, official minimal postprocessing, then the readout of section 2 (tempo from the median
  of the last 8 intervals, last beat, beats per bar by the downbeat comb pooled over readouts).
  No other state is carried between windows.
* **Scores per moment.** Annotated local tempo = 60 / median interval of the annotated beats
  within t +- 3 s; annotated bar length = beats between the downbeats around t.
  tempo ±4 % strict; ±4 % also allowing x2 and /2; beats per bar equal; phase: next beat
  = last + k x period (first one after now) within 70 ms of an annotated beat. A readout
  without a value counts as wrong. Percentages are over all moments of all clips (Swift has
  ~0.3 % more moments because its clock runs to the end of the last whole chunk).
  "Time to first correct tempo" and "3 correct in a row" are medians over clips of the first
  such moment (4 s is the first possible); "never" = more than half the clips never got there.
  Offline beat/downbeat F: mir_eval F-measure, 70 ms, first 5 s trimmed (as in the paper),
  whole-clip official inference for Beat This; for Swift, the beats the screen pulses on
  (each new `lastBeatAtNanos` that moves forward) and those with bar position 0.
* Sanity check: final0 offline beat F on the GTZAN sample is 90.2 and downbeat F 82.0, close to
  the paper's GTZAN figures as I remember them (about 89 beat F1); the paper's table was not
  re-read here, and this is a 300-clip sample, not all of GTZAN.

### Results (measured)

#### GTZAN (stratified random sample)

| system | clips | moments | tempo ±4% | tempo ±4% (×2, ÷2 ok) | beats/bar | phase ±70 ms | median time to 1st correct tempo | median time to 3 correct in a row | clips never correct | offline beat F | offline downbeat F |
|---|---|---|---|---|---|---|---|---|---|---|---|
| KeyCatch Swift (band) | 300 | 7796 | 65.2 | 81.6 | 43.8 | 75.8 | 4 s | 5 s | 36 | 72.8 | 12.9 |
| Beat This small0, W=6 s | 300 | 7787 | 74.7 | 86.1 | 68.5 | 82.8 | 4 s | 4 s | 9 | 89.4 | 81.1 |
| Beat This small0, W=8 s | 300 | 7787 | 78.1 | 88.9 | 81.1 | 85.2 | 4 s | 4 s | 9 | 89.4 | 81.1 |
| Beat This small0, W=12 s | 300 | 7787 | 79.0 | 89.4 | 80.2 | 85.4 | 4 s | 4 s | 13 | 89.4 | 81.1 |
| Beat This final0, W=6 s | 300 | 7787 | 78.6 | 89.4 | 74.1 | 85.0 | 4 s | 4 s | 11 | 90.2 | 82.0 |
| Beat This final0, W=8 s | 300 | 7787 | 79.7 | 90.3 | 80.7 | 85.7 | 4 s | 4 s | 13 | 90.2 | 82.0 |
| Beat This final0, W=12 s | 300 | 7787 | 80.3 | 90.8 | 82.4 | 86.4 | 4 s | 4 s | 15 | 90.2 | 82.0 |

#### GTZAN 3/4 (all)

| system | clips | moments | tempo ±4% | tempo ±4% (×2, ÷2 ok) | beats/bar | phase ±70 ms | median time to 1st correct tempo | median time to 3 correct in a row | clips never correct | offline beat F | offline downbeat F |
|---|---|---|---|---|---|---|---|---|---|---|---|
| KeyCatch Swift (band) | 44 | 1141 | 35.5 | 41.5 | 28.8 | 47.0 | 8 s | 16 s | 9 | 46.0 | 29.3 |
| Beat This small0, W=6 s | 44 | 1138 | 58.9 | 68.9 | 51.3 | 63.8 | 4 s | 6 s | 2 | 80.8 | 69.8 |
| Beat This small0, W=8 s | 44 | 1138 | 62.9 | 71.1 | 58.0 | 66.3 | 5 s | 5 s | 2 | 80.8 | 69.8 |
| Beat This small0, W=12 s | 44 | 1138 | 62.8 | 72.2 | 61.2 | 65.0 | 5 s | 6 s | 5 | 80.8 | 69.8 |
| Beat This final0, W=6 s | 44 | 1138 | 63.7 | 72.8 | 64.2 | 65.9 | 4 s | 4 s | 1 | 81.9 | 72.5 |
| Beat This final0, W=8 s | 44 | 1138 | 64.8 | 74.0 | 69.0 | 67.9 | 4 s | 5 s | 1 | 81.9 | 72.5 |
| Beat This final0, W=12 s | 44 | 1138 | 66.9 | 75.6 | 71.5 | 68.5 | 4 s | 5 s | 2 | 81.9 | 72.5 |

#### GTZAN 12/8 + 9/8 (all, ternary)

| system | clips | moments | tempo ±4% | tempo ±4% (×2, ÷2 ok) | beats/bar | phase ±70 ms | median time to 1st correct tempo | median time to 3 correct in a row | clips never correct | offline beat F | offline downbeat F |
|---|---|---|---|---|---|---|---|---|---|---|---|
| KeyCatch Swift (band) | 31 | 805 | 56.0 | 69.2 | 24.2 | 69.7 | 8 s | 9 s | 2 | 69.1 | 15.6 |
| Beat This small0, W=6 s | 31 | 805 | 53.0 | 56.5 | 62.7 | 64.6 | 5 s | 5 s | 6 | 76.0 | 67.0 |
| Beat This small0, W=8 s | 31 | 805 | 54.2 | 57.3 | 63.4 | 65.1 | 5 s | 5 s | 6 | 76.0 | 67.0 |
| Beat This small0, W=12 s | 31 | 805 | 58.1 | 60.7 | 62.6 | 68.0 | 4 s | 5 s | 5 | 76.0 | 67.0 |
| Beat This final0, W=6 s | 31 | 805 | 57.3 | 58.4 | 59.1 | 66.5 | 6 s | 6 s | 6 | 78.8 | 71.1 |
| Beat This final0, W=8 s | 31 | 805 | 58.8 | 59.9 | 62.0 | 67.6 | 4 s | 5 s | 6 | 78.8 | 71.1 |
| Beat This final0, W=12 s | 31 | 805 | 60.0 | 60.9 | 62.6 | 68.3 | 4 s | 4 s | 7 | 78.8 | 71.1 |

#### Ballroom val split (all styles)

| system | clips | moments | tempo ±4% | tempo ±4% (×2, ÷2 ok) | beats/bar | phase ±70 ms | median time to 1st correct tempo | median time to 3 correct in a row | clips never correct | offline beat F | offline downbeat F |
|---|---|---|---|---|---|---|---|---|---|---|---|
| KeyCatch Swift (band) | 103 | 2682 | 54.8 | 74.5 | 31.5 | 72.9 | 6 s | 10 s | 26 | 66.5 | 22.0 |
| Beat This small0, W=6 s **(model trained on these tracks)** | 103 | 2675 | 84.2 | 89.2 | 74.0 | 88.8 | 4 s | 4 s | 0 | 98.7 | 98.1 |
| Beat This small0, W=8 s **(model trained on these tracks)** | 103 | 2675 | 93.5 | 95.3 | 88.4 | 93.3 | 4 s | 4 s | 0 | 98.7 | 98.1 |
| Beat This small0, W=12 s **(model trained on these tracks)** | 103 | 2675 | 93.9 | 95.5 | 93.2 | 94.1 | 4 s | 4 s | 0 | 98.7 | 98.1 |
| Beat This final0, W=6 s **(model trained on these tracks)** | 103 | 2675 | 92.8 | 94.7 | 90.1 | 93.6 | 4 s | 4 s | 0 | 99.0 | 98.5 |
| Beat This final0, W=8 s **(model trained on these tracks)** | 103 | 2675 | 94.4 | 95.6 | 95.0 | 95.2 | 4 s | 4 s | 0 | 99.0 | 98.5 |
| Beat This final0, W=12 s **(model trained on these tracks)** | 103 | 2675 | 95.2 | 95.9 | 95.9 | 95.8 | 4 s | 4 s | 0 | 99.0 | 98.5 |
| Beat This single_small0, W=6 s | 103 | 2675 | 89.6 | 92.7 | 88.1 | 89.2 | 4 s | 4 s | 0 | 97.2 | 94.7 |
| Beat This single_small0, W=8 s | 103 | 2675 | 91.4 | 93.7 | 90.3 | 91.0 | 4 s | 4 s | 0 | 97.2 | 94.7 |
| Beat This single_small0, W=12 s | 103 | 2675 | 92.4 | 94.7 | 91.9 | 91.1 | 4 s | 4 s | 0 | 97.2 | 94.7 |
| Beat This single_final0, W=6 s | 103 | 2675 | 91.2 | 95.1 | 87.3 | 91.4 | 4 s | 4 s | 1 | 98.2 | 97.1 |
| Beat This single_final0, W=8 s | 103 | 2675 | 92.1 | 95.5 | 91.2 | 92.8 | 4 s | 4 s | 1 | 98.2 | 97.1 |
| Beat This single_final0, W=12 s | 103 | 2675 | 92.4 | 95.3 | 92.6 | 93.7 | 4 s | 4 s | 1 | 98.2 | 97.1 |

#### Ballroom val split, Waltz + Viennese Waltz

| system | clips | moments | tempo ±4% | tempo ±4% (×2, ÷2 ok) | beats/bar | phase ±70 ms | median time to 1st correct tempo | median time to 3 correct in a row | clips never correct | offline beat F | offline downbeat F |
|---|---|---|---|---|---|---|---|---|---|---|---|
| KeyCatch Swift (band) | 27 | 706 | 21.7 | 34.1 | 27.9 | 53.3 | never | never | 14 | 44.1 | 25.8 |
| Beat This small0, W=6 s **(model trained on these tracks)** | 27 | 704 | 81.5 | 84.2 | 83.7 | 85.7 | 4 s | 4 s | 0 | 97.5 | 96.5 |
| Beat This small0, W=8 s **(model trained on these tracks)** | 27 | 704 | 90.9 | 92.0 | 89.5 | 88.9 | 4 s | 4 s | 0 | 97.5 | 96.5 |
| Beat This small0, W=12 s **(model trained on these tracks)** | 27 | 704 | 92.9 | 92.9 | 93.6 | 91.6 | 4 s | 4 s | 0 | 97.5 | 96.5 |
| Beat This final0, W=6 s **(model trained on these tracks)** | 27 | 704 | 87.2 | 89.6 | 90.5 | 86.9 | 4 s | 4 s | 0 | 98.2 | 97.8 |
| Beat This final0, W=8 s **(model trained on these tracks)** | 27 | 704 | 90.3 | 92.0 | 93.9 | 90.5 | 4 s | 4 s | 0 | 98.2 | 97.8 |
| Beat This final0, W=12 s **(model trained on these tracks)** | 27 | 704 | 92.9 | 93.8 | 94.2 | 92.2 | 4 s | 4 s | 0 | 98.2 | 97.8 |
| Beat This single_small0, W=6 s | 27 | 704 | 84.4 | 86.5 | 87.6 | 83.2 | 4 s | 4 s | 0 | 96.6 | 93.7 |
| Beat This single_small0, W=8 s | 27 | 704 | 87.4 | 88.5 | 89.5 | 85.4 | 4 s | 4 s | 0 | 96.6 | 93.7 |
| Beat This single_small0, W=12 s | 27 | 704 | 91.2 | 92.5 | 92.0 | 87.9 | 4 s | 4 s | 0 | 96.6 | 93.7 |
| Beat This single_final0, W=6 s | 27 | 704 | 89.6 | 90.9 | 87.4 | 87.2 | 4 s | 4 s | 0 | 97.2 | 94.3 |
| Beat This single_final0, W=8 s | 27 | 704 | 91.9 | 93.0 | 89.6 | 88.4 | 4 s | 4 s | 0 | 97.2 | 94.3 |
| Beat This single_final0, W=12 s | 27 | 704 | 92.0 | 92.6 | 91.9 | 90.6 | 4 s | 4 s | 0 | 97.2 | 94.3 |

#### GuitarSet solo (mic)

| system | clips | moments | tempo ±4% | tempo ±4% (×2, ÷2 ok) | beats/bar | phase ±70 ms | median time to 1st correct tempo | median time to 3 correct in a row | clips never correct | offline beat F | offline downbeat F |
|---|---|---|---|---|---|---|---|---|---|---|---|
| KeyCatch Swift (guitar) | 60 | 1464 | 40.2 | 52.5 | 10.1 | 39.6 | 9 s | 10 s | 9 | 40.5 | 14.1 |
| Beat This small0, W=6 s | 60 | 1452 | 34.0 | 52.4 | 26.7 | 42.4 | 6 s | 25 s | 4 | 54.7 | 37.8 |
| Beat This small0, W=8 s | 60 | 1452 | 37.5 | 57.1 | 51.0 | 45.2 | 6 s | 14 s | 7 | 54.7 | 37.8 |
| Beat This small0, W=12 s | 60 | 1452 | 44.7 | 61.8 | 61.4 | 47.2 | 7 s | 13 s | 6 | 54.7 | 37.8 |
| Beat This final0, W=6 s | 60 | 1452 | 41.4 | 56.3 | 53.1 | 48.8 | 6 s | 12 s | 2 | 61.5 | 42.9 |
| Beat This final0, W=8 s | 60 | 1452 | 45.0 | 60.6 | 71.5 | 48.8 | 6 s | 11 s | 4 | 61.5 | 42.9 |
| Beat This final0, W=12 s | 60 | 1452 | 44.7 | 60.1 | 77.0 | 50.7 | 8 s | 12 s | 4 | 61.5 | 42.9 |

#### GuitarSet comp (val split, mic)

| system | clips | moments | tempo ±4% | tempo ±4% (×2, ÷2 ok) | beats/bar | phase ±70 ms | median time to 1st correct tempo | median time to 3 correct in a row | clips never correct | offline beat F | offline downbeat F |
|---|---|---|---|---|---|---|---|---|---|---|---|
| KeyCatch Swift (guitar) | 27 | 679 | 45.1 | 59.4 | 22.8 | 59.5 | 8 s | 8 s | 7 | 52.2 | 15.0 |
| Beat This small0, W=6 s **(model trained on these tracks)** | 27 | 668 | 73.8 | 86.2 | 55.7 | 83.2 | 4 s | 4 s | 0 | 92.9 | 96.0 |
| Beat This small0, W=8 s **(model trained on these tracks)** | 27 | 668 | 81.9 | 93.3 | 82.9 | 87.1 | 4 s | 4 s | 0 | 92.9 | 96.0 |
| Beat This small0, W=12 s **(model trained on these tracks)** | 27 | 668 | 89.4 | 96.7 | 89.8 | 91.5 | 4 s | 4 s | 0 | 92.9 | 96.0 |
| Beat This final0, W=6 s **(model trained on these tracks)** | 27 | 668 | 85.0 | 93.9 | 88.6 | 89.4 | 4 s | 4 s | 0 | 96.7 | 94.8 |
| Beat This final0, W=8 s **(model trained on these tracks)** | 27 | 668 | 87.4 | 94.8 | 93.6 | 91.6 | 4 s | 4 s | 0 | 96.7 | 94.8 |
| Beat This final0, W=12 s **(model trained on these tracks)** | 27 | 668 | 91.6 | 97.9 | 96.3 | 93.6 | 4 s | 4 s | 0 | 96.7 | 94.8 |
| Beat This single_small0, W=6 s | 27 | 668 | 71.0 | 81.7 | 89.8 | 70.4 | 4 s | 4 s | 0 | 85.0 | 80.7 |
| Beat This single_small0, W=8 s | 27 | 668 | 75.4 | 85.5 | 93.4 | 73.7 | 4 s | 4 s | 0 | 85.0 | 80.7 |
| Beat This single_small0, W=12 s | 27 | 668 | 78.7 | 85.9 | 92.8 | 75.1 | 5 s | 5 s | 0 | 85.0 | 80.7 |
| Beat This single_final0, W=6 s | 27 | 668 | 76.2 | 93.1 | 87.4 | 79.2 | 4 s | 4 s | 0 | 87.0 | 85.7 |
| Beat This single_final0, W=8 s | 27 | 668 | 78.1 | 92.7 | 90.9 | 80.2 | 4 s | 4 s | 0 | 87.0 | 85.7 |
| Beat This single_final0, W=12 s | 27 | 668 | 81.7 | 93.4 | 94.2 | 81.9 | 4 s | 4 s | 0 | 87.0 | 85.7 |

#### Swift tracker: share of scored moments shown as compound (6/8, 9/8, 12/8)

| group | preset | moments | compound shown |
|---|---|---|---|
| GTZAN (stratified random sample) | band | 7796 | 9.8% |
| GTZAN 3/4 (all) | band | 1141 | 23.0% |
| GTZAN 12/8 + 9/8 (all, ternary) | band | 805 | 29.7% |
| Ballroom val split (all styles) | band | 2682 | 10.7% |
| Ballroom val split, Waltz + Viennese Waltz | band | 706 | 23.2% |
| GuitarSet solo (mic) | guitar | 1464 | 25.4% |
| GuitarSet comp (val split, mic) | guitar | 679 | 29.5% |

#### GTZAN random sample per genre: tempo ±4% (×2 ok) / phase

| system | blues | classical | country | disco | hiphop | jazz | metal | pop | reggae | rock |
|---|---|---|---|---|---|---|---|---|---|---|
| KeyCatch Swift (band) | 83 / 76 | 39 / 35 | 83 / 78 | 93 / 90 | 90 / 85 | 54 / 48 | 92 / 84 | 91 / 90 | 97 / 96 | 86 / 75 |
| Beat This small0, W=6 s | 79 / 72 | 55 / 51 | 88 / 89 | 92 / 90 | 94 / 90 | 81 / 73 | 88 / 86 | 88 / 88 | 97 / 98 | 92 / 87 |
| Beat This small0, W=8 s | 82 / 75 | 58 / 54 | 91 / 90 | 94 / 93 | 97 / 93 | 81 / 72 | 90 / 88 | 95 / 93 | 99 / 99 | 95 / 89 |
| Beat This small0, W=12 s | 82 / 75 | 57 / 54 | 92 / 92 | 96 / 94 | 97 / 92 | 83 / 72 | 91 / 88 | 95 / 94 | 99 / 98 | 96 / 90 |
| Beat This final0, W=6 s | 83 / 75 | 61 / 55 | 92 / 93 | 94 / 92 | 94 / 89 | 84 / 74 | 93 / 87 | 94 / 93 | 99 / 98 | 93 / 87 |
| Beat This final0, W=8 s | 84 / 75 | 64 / 55 | 93 / 93 | 93 / 93 | 95 / 91 | 85 / 75 | 93 / 88 | 95 / 94 | 99 / 98 | 95 / 88 |
| Beat This final0, W=12 s | 85 / 76 | 67 / 57 | 93 / 94 | 93 / 93 | 95 / 92 | 85 / 73 | 93 / 88 | 96 / 96 | 99 / 99 | 96 / 90 |

Beats-per-bar with the naive "peaks" readout instead of the comb (W 8 s, `--meter peaks`):
GTZAN sample 60.9 (small0) / 60.7 (final0), GTZAN 3/4 47.1 / 56.0, Ballroom val (single_*)
77.5 / 81.6, GuitarSet solo 13.6 / 13.6, GuitarSet comp val (single_*) 59.0 / 65.3. The comb
readout only chooses between 2, 3 and 4 beats per bar, which is a small advantage on the
all-4/4 GuitarSet compared with the Swift MeterDetector, which also considers 5, 6 and 7.

### Compound metres

How often it matters (read from GTZAN-Rhythm v2 `stats.csv`, all 1000 GTZAN tracks): meter 4/4
895, 3/4 44, 12/8 27, 9/8 4, 2/4 7, 5/4 2, unlabelled 21; 31 tracks (3.1 %) are ternary (beat
divides in three), 147 swing. Ballroom and GuitarSet carry no compound labels (all Ballroom
styles and all GuitarSet takes are simple metres; GuitarSet jazz and Ballroom jive swing).
In these annotations a 12/8 song has 4 beats per bar at the dotted quarter, so the
beats-per-bar and tempo scores above count 12/8 as "4"; what KeyCatch adds is the 12/8 label.

The Swift tracker shows a compound label on 9.8 % of moments in the GTZAN sample, 29.7 % on
the ternary clips, and also on 23-30 % of moments of 3/4 waltzes and of the (simple, 4/4)
GuitarSet takes: its compound decision is weak today. Prototype of keeping it with Beat This
(`compound_proto.py`: MeterDetector.isCompound ported line for line, run on KeyCatchCore's own
onset signal, with the Beat This beat period and newest beat at each moment; GTZAN):

GTZAN: 31 ternary clips (12/8, 9/8), 289 non-ternary clips of the random sample

| compound decision from | share of moments called compound: ternary clips | non-ternary clips | clips called compound by majority of moments: ternary | non-ternary |
|---|---|---|---|---|
| KeyCatch Swift (band), MeterDetector as shipped | 29.9% | 9.3% | 8/31 | 15/289 |
| isCompound on Beat This small0 beats, W=8 s | 19.1% | 8.5% | 2/31 | 0/289 |
| isCompound on Beat This final0 beats, W=8 s | 20.1% | 9.0% | 2/31 | 0/289 |
| same, energies pooled over readouts (x0.9 per s), small0 | 18.1% | 6.4% | 2/31 | 9/289 |
| same, energies pooled over readouts (x0.9 per s), final0 | 18.7% | 7.0% | 4/31 | 12/289 |

Measured separability of the underlying evidence (energy at both thirds vs halfway, pooled over
a whole clip, final0/small0 beats): clip-level AUC 0.81 for ternary vs other, but at a
threshold with <= 5 % false alarms only 10-23 % of ternary clips are found. So reusing
MeterDetector's subdivision check on Beat This beats works mechanically but is no better at telling 12/8 from 4/4 swing than the
check is today. Proposal: keep the 6/8-12/8 decision outside the network, computed on the
Beat This beat grid from the onset signal the app already has, pooled over many beats (tens of
seconds) and with hysteresis before the label switches; and treat it as a separate, slower
"feel" decision that never changes the beat count.

What the iPhone app does (`bt_live.Feel`, Swift `BeatFeel`): at each reading with a tempo, the
four sums of the check (beat, half, first third, second third) over the 8 s onset history,
divided by the number of beats, go into a pool that keeps 0.95 of itself per reading (about
20 s). Compound is shown only after the pool has been strong for 5 readings in a row: at least
10 readings' worth pooled, thirds above 2 x the halfway point, the two thirds within a factor
2 of each other, and above a quarter of the beat. It goes back to simple after 5 readings in a
row without thirds above 1.1 x halfway, and a beat period more than 8 % from the pool's starts
the pool over (simple). The thresholds were picked from a small grid (pool 0.9/0.95, 5/10
readings, ratio 1.3-2.0, balance 0.35/0.5, hold 3/5) as the setting with the most ternary
moments while every false-alarm column stayed near or under 1 %, on these same GTZAN clips, so
the numbers below are optimistic. Measured (`compound_proto.py`, W 8 s):

| bt_live.Feel on | ternary (12/8, 9/8): moments shown compound | clips with any | other clips of the random sample: moments shown compound | clips with any | 3/4: moments shown compound | clips with any |
|---|---|---|---|---|---|---|
| Beat This small0, W=8 s | 1.4% | 2/31 | 0.1% | 1/289 | 0.7% | 1/44 |
| Beat This final0, W=8 s | 2.1% | 2/31 | 0.4% | 3/289 | 0.0% | 0/44 |

So the label is almost never wrong and almost never shown: a 12/8 song reads as 4/4 (at the
dotted quarter) nearly always, which is the conservative choice. The 30 s clips also cut the
pooling short; a whole song gives the pool more time. If better accuracy is wanted, the next step
is a tiny classifier on per-beat subdivision profiles (onset energy at 1/4, 1/3, 1/2, 2/3, 3/4
of each beat), trained on GTZAN-Rhythm's ternary/swing labels with clips held out, or reading
the Beat This beat activation between beats; neither was tried here.

## 6. Recommendation

**Window: W = 8 s.** Going from 6 to 8 s helps everywhere and a lot for the bar
(GTZAN sample, beats per bar: small0 68.5 -> 81.1, final0 74.1 -> 80.7; tempo +1 to +3 points).
12 s adds at most ~1-2 points on GTZAN and Ballroom (more on GuitarSet solo, e.g. small0 bar
51 -> 61), costs 2x the compute of 8 s (attention is quadratic) and reacts more slowly to
tempo changes, which these steady 30 s clips do not test. A 12 s window is worth trying only
for the Guitar preset, if the CPU budget allows.

**Checkpoint.** On drummed music the two are almost equal: GTZAN sample at W 8 s, small0 vs
final0: tempo 78.1 vs 79.7, tempo with x2 88.9 vs 90.3, bar 81.1 vs 80.7, phase 85.2 vs 85.7;
Ballroom val (single_small0 vs single_final0) 91.4 vs 92.1, 90.3 vs 91.2, 91.0 vs 92.8.
Without drums the big model is clearly better: GuitarSet solo (unseen by both) tempo 37.5 vs
45.0, bar 51.0 vs 71.5, phase 45.2 vs 48.8; GuitarSet comp val (single_*) tempo 75.4 vs 78.1,
x2-tempo 85.5 vs 92.7, phase 73.7 vs 80.2; GTZAN 3/4 bar 58.0 vs 69.0. Costs: small0 2.1 M
parameters, 4.5 MB as FP16 Core ML / 8.5 MB FP32, 176 ms per 8 s window on one core here;
final0 20.3 M, 40.9 MB / 81.4 MB, 305 ms (99 ms on 4 threads).
So: **small0 with W = 8 s as the default for both apps** (Android on the CPU: about a fifth of
a core here at one run per second; iPhone: 4.5 MB). **final0 is the upgrade for the Guitar
preset**, where it is clearly better, if 41 MB (FP16) and roughly 2x the CPU are acceptable,
e.g. on iPhone through Core ML. Either way Beat This beats the current tracker by a wide
margin on everything except the drumless solo-guitar tempo (small0 37.5 vs Swift 40.2 strict)
and the x2-tolerant tempo on 12/8 songs (Swift 69.2 vs Beat This 57-61: Beat This more often
locks to a level 3/2 or 3x off the annotated dotted-quarter beat). The bar count is where the
gain is largest (GTZAN 43.8 -> ~81 %, waltzes 28 -> ~90 %).

Before shipping: run the .mlpackage on a Mac/iPhone against these logits (FP16 checked only
by a stand-in), keep the FP16-safe RMSNorm, port `frontend_np.py` (float32 is enough) and the
readout (tempo median, comb meter, phase extrapolation), and keep a compound/"feel" decision
outside the network as proposed above. Not measured here: phone speeds, behaviour on tempo
changes and live microphone audio (room, phone mic, AGC).

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
# sha256 of what was used: genres.tar.gz b28cc067ff6199bd826f5d1a6931458586d64acae7f44b2e882b7a97af057531,
#   GTZAN-Rhythm v2 a5f8b910f5dd16937352d4a40e0652e4f689b3fea5ed33f845a465f719868c96,
#   data1.tar.gz e2f85f5ac230523498ca3b9addf225de19f1d4d1cb7d8724a40bc0044b7c5947,
#   annotation.zip 8daa02e6417ccca1685feb44b135e95928ad7037e5032ecb326b5791856fda99 (md5 matches Zenodo),
#   audio_mono-mic.zip 237cdc58353d25c3c9683f4565a0f1cf2db30a9051abca545a919f8f1296dc28 (md5 matches Zenodo);
#   beat_this_annotations at fd9fcc0896cb78730bae735102c8753ef3a3badd (main, v1.1 annotations)

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
$V -I live_eval.py --work $E beatthis --beat-this-src $S/dl/beat_this_src --ckpt small0=... final0=... --datasets guitarset_solo ballroom guitarset_comp
$V -I live_eval.py --work $E score --ckpts small0 final0 single_small0 single_final0 [--meter peaks]
# every step skips clips whose result file exists (written atomically), so a killed run can be restarted;
# about 48 s of one CPU core per clip for small0+final0 at W 6, 8, 12 s.
# compound check: dump onsets with kconsets (list lines: <clip_44100.f32> <out.f32> 44100 band) into $E/onsets, then
$V -I compound_proto.py --work $E
```

## 7. In the iPhone app

`ios/KeyCatchCore/Sources/KeyCatchCore/BeatThis.swift` (plain Swift, written to port to Kotlin
line by line) and `CoreMLModels.swift` (`CoreMLBeatThis`). Settings has a beat switch for the
Band and Guitar presets: Standard (TempoTracker + MeterDetector) or Accurate (Beat This!, the
default). Band uses small0, Guitar final0, both as FP16 Core ML packages of 412 frames.

* Resampling: KeyCatchCore's StreamResampler (Blackman-windowed sinc, 256 phases) with its
  cutoff at 10.5 kHz. Measured against soxr on 6 GTZAN and 3 GuitarSet files with small0
  (`check_resampling`-style, whole files): cutoff 9.5 kHz changed the logits by up to 10.6 and
  left 489/513 beats on the same frame; 10.5 kHz by 0.75 (44.1 kHz) / 0.78 (48 kHz), 506/511
  and 502/511 beats on the same frame; 10.8 kHz by 1.3.
* Front end: frames are made one by one as audio arrives, silence before the start; float32,
  0.00026 from `frontend_np.py` (float64) on the test signal.
* Every second from 4 s the newest 400 frames plus 6 zero frames on each side go to the network
  on a worker queue; a window that is all silence skips the network (it finds no beats in zeros).
  Readings are dated in seconds of audio, so a late answer still puts the beats where they were.
* Reading: `bt_live.Reader` (= `live_eval.bt_readouts` for tempo, last beat and beats per bar),
  plus the bar position of the newest beat (the comb's best phase in this window for the chosen
  bar length, or the previous position carried on by whole beats) and "locked" (the last three
  readings within 4 % of each other; the screen says "settling" until then).
* Compound: `bt_live.Feel` above, on the onset signal of the analyser's own OnsetDetector.
* The standard tracker keeps running beside it: the chord grid stays on it (chords are the same
  in both modes), and if the network fails the analyser goes back to it.

