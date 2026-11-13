# S-KEY for KeyCatch: licence, live evaluation, conversion, speed

An evaluation of S-KEY (Kong, Meseguer-Brocal, Lostanlen, Lagrange, Hennequin, "S-KEY:
Self-supervised Learning of Major and Minor Keys from Audio", ICASSP 2025, arXiv 2501.12907;
code github.com/deezer/skey) as an addition to KeyCatch's key finder. Measurement only: no app
code was changed. Numbers are **measured** with the scripts in this folder unless marked
**read** (taken from upstream docs, code or the paper) or **estimated**.

**Short answer.** The published weights may not be used in the app: since 2026-10-27 the
upstream README says they are for non-commercial research only (section 1). On the numbers, S-KEY
is much better than KeyCatch on full mixes (Band: +10 to +18 points exact key on GTZAN and
GiantSteps), worse on strummed guitar, and bad on solo singing. Its promised strength, telling a
major key from its relative minor, does not show here: its relative-minor errors are about as
frequent as the app's, and on real a cappella singing it reads a major song as the relative
minor 31 % of the time. Section 7 has the recommendation.

| file | does |
|---|---|
| `skey_common.py` | imports the official package from a checkout; loads the model; key indices, MIREX score, relative/parallel |
| `skey_live_eval.py` | the live evaluation: `prepare`, `run` (Swift app + S-KEY), `ktrun` (Kotlin app with Basic Pitch / CREPE), `check`, `score` |
| `kckey/` | Swift driver on ios/KeyCatchCore (MusicAnalyzer, 1024-sample chunks), key readout once a second, as `tools/beatthis/kclive` |
| `kt/KeyReadingsDump.kt` | the same driver for the Kotlin engine with the app's default models (run in a scratch copy of the repo, see Reproducing) |
| `singing_synth.py` | the sung test tunes of the app's own unit tests, rendered to audio (Java `Random` reproduced) |
| `fetch_giantsteps.sh` | GiantSteps Key audio, md5-checked against the dataset's list |
| `remote_zip.py`, `fetch_csd.py`, `csd_labels.py` | Children's Song Dataset (English) by range requests; key labels derived from its notes |
| `skey_export.py` | conversion-friendly ChromaNet (same weights, static shapes) |
| `convert_coreml.py` | Core ML conversion, FP32 and FP16, fixed input length |
| `coreml_check.py` | runs the converted MIL programs (NumPy/torch interpreter) against PyTorch on real windows |
| `bench_speed.py` | CPU time per run, 1 and 4 threads, 20 s and 30 s windows |

## 1. Model, weights, licence

Read from github.com/deezer/skey at `80d9590e150b836a341dfc6dbf05aa20bc81d10b` (2026-10-27), the
checkpoint and the paper (arXiv 2501.12907v2, sha256 `00b0b556...f1b198`):

| item | value |
|---|---|
| code licence | MIT ("Copyright (c) 2019-present, Deezer SA") |
| **weights licence** | **not MIT**: README since commit 80d9590 (2026-10-27, "Update README to specify model weights usage"): "The MIT license does not apply to the model weights in skey.pt. You may use the model weights only for non-commercial research purposes." No separate weights licence file. |
| checkpoint | `skey/models/skey.pt` in the repo (not a release asset), 765,465 bytes, sha256 `78dfd0ad4fa9434bf7cec70a25934b7c575bda9c80e994700140770ad3a5ead4`; contains the model, the AdamW state and the training config |
| parameters (measured) | ChromaNet **47,284** trainable (+5 BatchNorm buffers); the VQT front end has no trainable parameters (28,003 elements of fixed CQT kernels and a low-pass filter) |
| size as Core ML (measured) | 255 KB FP32, 169 KB FP16 (.mlpackage) |
| input | mono audio at **22,050 Hz**, peak-normalised to 1 (official `load_audio`), any length |
| front end | nnAudio 0.3.3 VQT: fmin 27.5 Hz (A0), 12 bins per octave, 99 bins, hop 512 (43 frames/s), gamma 0 (a plain CQT), 9 octaves multi-rate (downsample by 2 per octave), reflect padding; magnitude; `AmplitudeToDB(top_db=80)` over the whole window; x/80 + 1; bins 0..83 kept (7 octaves from A0) |
| network | ChromaNet (from STONE, ISMIR 2024): 7 blocks of [LayerNorm, (1x2) stride-2 time conv, GELU, ConvNeXt block with 7x7/5x5 depthwise conv (replicate padding)], channels 2,3,40,40,30,10,3; octave average to 12 bins; mean over time; 1x1 conv to 2 channels; BatchNorm (no affine); softmax over the 24 outputs |
| output | 24 probabilities: indices 0..11 = A, Bb, B, ..., G# major; 12..23 = B, C, ..., Bb minor (`key_map` in `key_detection.py`) |
| training (read) | self-supervised, no key labels: Deezer catalogue audio, 15 s segments at 22,050 Hz. Paper: a 60k-song model (50 epochs, batch 128) and a 1M-song model (100 epochs, batch 256) |
| which checkpoint is published | not stated. The checkpoint says `epoch 98`, `batch_size = 128`, `duration = 15`, configs named `ismir24.*`. Measured: offline whole-clip MIREX on GTZAN (837 clips) **74.4 %** and on GiantSteps (604) **72.2 %**, the paper's 1M-song results (74.4, 72.1; the 60k model has 70.9, 71.7). So it is the **1M-song model**, with GTZAN and GiantSteps reproduced. |
| other limits | trained on commercial music, all 15 s excerpts of full mixes: nothing like lone voices or a phone mic. Fixed concert pitch (the VQT grid is A440; trained with whole-semitone shifts only). 24 major/minor keys only. |

**What this means for the app:** KeyCatch is a published app; shipping `skey.pt` (or anything
converted from it) is outside "non-commercial research" use. Using S-KEY needs written permission
or a licence from Deezer, or a model retrained with the MIT training code (`training_utils/`) on
audio the project may use; the paper needed 60k-1M songs for that. The code itself (MIT) is not
the problem.

## 2. Data and labels

| set | what | source | checksum (sha256) |
|---|---|---|---|
| GTZAN audio | 1000 x 30 s, 22,050 Hz | `https://huggingface.co/datasets/marsyas/gtzan/resolve/main/data/genres.tar.gz` (the Beat This! copy) | `b28cc067ff6199bd826f5d1a6931458586d64acae7f44b2e882b7a97af057531` |
| GTZAN keys | Kraft/Lerch labels (O'Brien & Lerch, ICMC 2015; the set the S-KEY paper uses, [22]), MIT | github.com/alexanderlerch/gtzan_key at `c6dc7fe8d6949ff29b8b9fc33cd4fdd18be5b6b7` | concatenation of the 1000 `*.lerch.txt` in sorted path order: `85a2a821d817ecb6279884551db8add574f45a7336dfb5fdde80ff46f4183466` |
| | 837 labelled (classical all "-1", 63 others unknown) | | |
| GuitarSet | 360 mono-mic recordings (180 comp, 180 solo); key from each JAMS `key_mode` (one value per file, 19 keys) | zenodo.org/records/3371780 `audio_mono-mic.zip`, `annotation.zip` | `237cdc58353d25c3c9683f4565a0f1cf2db30a9051abca545a919f8f1296dc28`, `8daa02e6417ccca1685feb44b135e95928ad7037e5032ecb326b5791856fda99` |
| GuitarSet +39 cents | the same 360, resampled with soxr so every pitch is 39 cents higher (and 2.3 % faster) | made here | |
| GiantSteps Key | 604 two-minute Beatport previews (EDM, 84 % minor) | labels: github.com/GiantSteps/giantsteps-key-dataset at `6bcd492c825ac9b8597bc650a5f6fd18b6c43d2b`; audio from the JKU backup in its `audio_dl.sh` (`https://www.cp.jku.at/datasets/giantsteps/backup/<id>.LOFI.mp3`), all 604 md5-checked | `annotations/key/*.key` concatenated in name order `9e69f9a8089863de3ef46cba4b4459cc55f2b4559b458c930fa5c4b5a803ee19`; the 604 mp3 concatenated `a1ebdaccf5f058565919470e639b241bea6a54fa9f2c333fc8dbc347c5df7ed0` |
| singing (app tests) | 45 renders of the sung tunes in `SingerRobustnessTest` (8 singer types x 5 seeds, C major), `WanderingSingersTest` (steady), `MelodyKeyTest` (E major sharp/flat, C major vs A minor); expected keys as the tests assert | `singing_synth.py` | deterministic |
| Children's Song Dataset (CSD), English | 100 a cappella recordings (one professional singer, 50 songs, each in two keys), CC BY-NC-SA 4.0 | zenodo.org/records/4785016 `CSD.zip` (members read by range request; zip CRC checked) | 100 FLACs concatenated in name order `d93a6fdb3d06fba64260398c7f8cd48d21c3df04346cc0d6d23a03d7954c01fc`; labels file `c507a41d7f269b453850dfd4e3fbf8db0fd27d1a737a9c5e437564aacfe0130f` |

About the labels:

* The app's own tests use **no recorded voices**: every "singing" test is synthesised. "IDMT" in
  `HeardNotes` is a chord set (guitar/piano progressions), not singing. So the synthetic tunes are
  evaluated as the tests' cases, and CSD was added as real singing.
* **CSD has no key labels**, and its MIDI files carry no key signature. `csd_labels.py` derives
  them: the major scale covering most sung time (>= 97 %) gives the seven notes, the last sung
  note on that scale's do or la gives the key; ties between scales are broken by the last note;
  songs ending elsewhere or ambiguous are left out; each song's two recordings must agree
  (`key_diff` in metadata). 84 of 100 recordings labelled, **all major**. These are derived
  labels, not annotations; the app (an independent method) agrees with them 87 % of the time.
* GiantSteps Key audio came from the JKU backup host; `geo-samples.beatport.com` (the fallback)
  returned 404 for the file tried. Nothing needed was blocked.
* Not used: GiantSteps MTG Key (no free audio), FMAKv2 (labels from the S-KEY authors' STONE
  work; not looked for), Schubert Winterreise (classical, modulating).

## 3. Method (live readings)

At 10, 14, 18, ... s into each clip (every 4 s, until its end; the short sung test tunes also at
their last whole second), as the screen would show it:

* **app (Swift)**: `AnalysisResult.displayKey` from KeyCatchCore's MusicAnalyzer (`kckey`), audio
  at 44.1 kHz in 1024-sample chunks. On Linux there is no Core ML, so this is the app with the
  Core ML models off: spectral chroma for Band/Guitar, autocorrelation pitch for Voices.
* **app (Kotlin, default models)**: the same readout from the Kotlin engine with Basic Pitch for
  Band/Guitar ("Chords and key", default on) and CREPE for Voices (default on), via
  `KeyReadingsDump.kt` on the JVM. This is what both apps show by default. Run on all GuitarSet,
  singing and CSD clips, a 30-per-genre GTZAN sample (270) and 60 GiantSteps clips (CPU time:
  about 0.4 s per second of audio). Band and Guitar both hear through Basic Pitch, so one preset
  was run (Guitar for GuitarSet, Band elsewhere).
* **skeyW**: S-KEY on the last W = 20 or 30 s (all of it before W s have been heard), the
  official preprocessing. For speed the VQT magnitude is computed once per clip and each window's
  frames are sliced from it, then peak-normalised, dB-converted and clipped per window exactly as
  the official forward does. `check` compares this with the official per-window run: argmax
  identical in **80/80** windows, max probability difference median 0.005 (p95 0.077). A window
  ending 0.75 s earlier (no audio after the moment, as a streaming front end with the lowest
  octave's 0.75 s half-kernel would see) gives the same key in 78/80.
* **skeyW-tuned**: S-KEY on audio first moved onto concert pitch by the app's own tuning reading
  at that moment (`tuningCents`, rounded to 5 cents): what a shipped version would have to do.
* **Rules** (S-KEY as an extra vote; fixed before looking at the results, named):
  * `pair`: keep the app's pair of keys; S-KEY picks major or relative minor (p_major vs p_minor).
  * `pair-undecided`: as `pair`, but only while the app has not decided the mode (`modeDecided` false).
  * `sum`: app's `modeScore` (RelativeKeyResolver evidence; +-1.5 when the chord estimate
    overrides) + w ln(p_major / p_minor) for the app's pair, w = 1; the sign picks the mode.
  * `override`: S-KEY's key when its top probability >= 0.5 and it is another pair; else `pair`.
  * suffix `t`: the rule fed by skey-tuned.
* Scores per reading: exact; MIREX (1 / 0.5 fifth same mode / 0.3 relative / 0.2 parallel);
  **relative %** = readings that are the true key's relative (the major vs relative-minor error);
  "rel. share in pair" in the full tables = relative / (exact + relative). Readings without a key
  count as wrong (none occurred at 10 s or later).

## 4. Results

### 4a. App with its default models (Kotlin, Basic Pitch / CREPE) vs S-KEY

Exact key %, all readings (MIREX % and relative % below):

| set, preset | clips | readings | app | skey20 | skey30 | skey20-tuned | pair/20 | pair-undecided/20 | sum/20 | override/20 | pair-undecided/20t | sum/20t | override/20t |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| singing (tests), Voices | 45 | 222 | **81.1** | 68.9 | 79.7 | 73.4 | 77.0 | 77.5 | **81.1** | 72.5 | 78.4 | **81.1** | 74.8 |
| CSD real singing, Voices | 84 | 1592 | **86.9** | 38.2 | 37.6 | 40.8 | 44.8 | 62.9 | 64.5 | 41.6 | 64.6 | 65.8 | 44.6 |
| GuitarSet comp, Guitar | 180 | 1014 | 71.8 | 60.9 | 60.9 | 61.9 | 68.7 | 72.6 | 71.9 | 65.2 | **72.7** | 72.0 | 66.7 |
| GuitarSet solo, Guitar | 180 | 1014 | 35.2 | 40.4 | **41.2** | 40.8 | 34.8 | 36.2 | 37.3 | 40.5 | 36.7 | 37.0 | 40.8 |
| GuitarSet comp +39 c, Guitar | 180 | 984 | 61.9 | 26.6 | 25.6 | 49.9 | 44.3 | 58.8 | 59.7 | 37.6 | 62.8 | **63.1** | 54.8 |
| GuitarSet solo +39 c, Guitar | 180 | 984 | 38.1 | 23.0 | 23.2 | 40.2 | 32.2 | 34.2 | 38.4 | 30.6 | 38.2 | **40.8** | 40.5 |
| GTZAN (30/genre), Band | 270 | 1360 | 48.4 | **58.2** | 58.1 | | 49.4 | 49.3 | 49.5 | 56.8 | | | |
| GiantSteps (60), Band | 60 | 1680 | 43.5 | 58.2 | **61.7** | | 47.6 | 44.2 | 46.2 | 58.7 | | | |

MIREX %:

| set, preset | app | skey20 | skey30 | skey20-tuned | pair/20 | pair-undecided/20 | sum/20 | override/20 | pair-undecided/20t | sum/20t | override/20t |
|---|---|---|---|---|---|---|---|---|---|---|---|
| singing (tests), Voices | 83.5 | 81.0 | 87.2 | 80.6 | 83.3 | 83.6 | 84.4 | 82.3 | 84.0 | 84.1 | 82.2 |
| CSD, Voices | 92.3 | 58.1 | 58.6 | 60.3 | 62.2 | 74.9 | 76.2 | 60.9 | 76.2 | 77.2 | 63.1 |
| GuitarSet comp, Guitar | 78.4 | 71.8 | 71.5 | 72.2 | 77.0 | 78.7 | 78.7 | 74.9 | 78.8 | 78.8 | 75.7 |
| GuitarSet solo, Guitar | 50.0 | 55.1 | 55.5 | 55.3 | 51.6 | 52.5 | 52.6 | 55.8 | 52.8 | 52.6 | 55.9 |
| GuitarSet comp +39 c | 70.0 | 36.5 | 35.7 | 59.1 | 58.4 | 68.0 | 69.0 | 49.9 | 70.7 | 71.3 | 63.4 |
| GuitarSet solo +39 c | 51.0 | 33.6 | 33.8 | 54.3 | 47.8 | 49.1 | 51.3 | 43.8 | 52.4 | 54.0 | 54.8 |
| GTZAN (270), Band | 59.5 | 68.7 | 68.6 | | 59.5 | 59.8 | 60.2 | 67.0 | | | |
| GiantSteps (60), Band | 53.3 | 68.7 | 71.2 | | 57.9 | 54.1 | 56.0 | 68.1 | | | |

**Relative %** (the reading is the true key's relative major/minor):

| set, preset | app | skey20 | skey30 | skey20-tuned | pair/20 | pair-undecided/20 | sum/20 | override/20 | pair-undecided/20t | sum/20t | override/20t |
|---|---|---|---|---|---|---|---|---|---|---|---|
| singing (tests), Voices | 0.0 | 0.0 | 0.0 | 0.0 | 4.1 | 3.6 | 0.0 | 0.5 | 2.7 | 0.0 | 1.4 |
| CSD, Voices | **1.8** | **31.4** | 33.0 | 29.5 | 43.9 | 25.8 | 24.2 | 37.6 | 24.1 | 22.9 | 35.1 |
| GuitarSet comp, Guitar | 12.2 | 13.1 | 14.2 | 13.8 | 15.3 | 11.4 | 12.1 | 15.7 | 11.3 | 12.0 | 15.4 |
| GuitarSet solo, Guitar | 11.4 | 13.1 | 12.3 | 13.2 | 11.8 | 10.5 | 9.4 | 11.9 | 10.0 | 9.7 | 11.8 |
| GuitarSet comp +39 c | 13.8 | 9.9 | 10.8 | 12.1 | 31.4 | 16.9 | 16.1 | 23.5 | 12.9 | 12.6 | 13.1 |
| GuitarSet solo +39 c | 12.2 | 11.4 | 12.1 | 12.7 | 18.1 | 16.1 | 11.9 | 16.3 | 12.1 | 9.6 | 13.7 |
| GTZAN (270), Band | 5.4 | 6.2 | 6.6 | | 4.4 | 4.6 | 4.3 | 5.5 | | | |
| GiantSteps (60), Band | 11.3 | 7.3 | 6.4 | | 7.2 | 10.7 | 8.6 | 7.6 | | | |

Split by the app's own mode decision (exact % / relative %; readings with a key):

| set | app decided: n | app | skey20 | app undecided: n | app | skey20 |
|---|---|---|---|---|---|---|
| CSD | 683 | 99.0 / 0.0 | 50.1 / 33.4 | 909 | 77.9 / 3.1 | 29.3 / 29.9 |
| GuitarSet comp | 850 | 74.5 / 10.1 | 62.5 / 12.9 | 164 | 57.9 / 23.2 | 53.0 / 14.0 |
| GuitarSet solo | 359 | 44.8 / 5.8 | 46.0 / 8.9 | 655 | 29.9 / 14.5 | 37.4 / 15.4 |
| GTZAN (270) | 1101 | 50.6 / 3.0 | 62.9 / 4.7 | 259 | 39.0 / 15.8 | 38.2 / 12.7 |
| GiantSteps (60) | 1438 | 44.8 / 11.0 | 61.1 / 7.8 | 242 | 36.0 / 13.2 | 40.9 / 4.5 |

Rule `sum`, weight sweep (exact %, W 20; the rule uses w = 1, fixed beforehand): CSD 83.6 / 76.4 /
64.5 / 55.2 / 49.8 for w = 0.25 / 0.5 / 1 / 2 / 4; GuitarSet comp 73.0 / 72.8 / 71.9 / 70.6 / 70.2;
solo 37.1 / 37.2 / 37.3 / 36.6 / 36.1; GTZAN 48.9 / 49.3 / 49.5 / 49.9 / 49.7; GiantSteps
44.2 / 45.5 / 46.2 / 47.1 / 47.0. No weight helps everywhere; small weights only avoid harm.

First reading (10 s, exact % / relative %): GuitarSet comp app 55.6 / 23.9, skey20 41.7 / 21.7;
solo 29.4 / 12.2 vs 36.1 / 11.1; GTZAN 46.3 / 5.6 vs 52.6 / 6.7; GiantSteps 43.3 / 11.7 vs
61.7 / 3.3; CSD 50.0 / 0.0 vs 41.7 / 29.8.

### 4b. App without Core ML models (Swift on Linux: spectral chroma, autocorrelation pitch), all clips

Exact % (relative % in the full tables from `score`):

| set, preset | clips | readings | app | skey20 | skey30 | skey20-tuned | pair/20 | pair-undecided/20 | sum/20 | override/20 | pair-undecided/20t | sum/20t | override/20t |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| singing (tests), Voices | 45 | 222 | 86.9 | 68.9 | 79.7 | 73.4 | 80.2 | 82.0 | 84.7 | 74.8 | 84.2 | 86.0 | 76.6 |
| CSD, Voices | 84 | 1592 | 87.4 | 38.2 | 37.6 | 40.8 | 46.5 | 65.1 | 66.0 | 42.0 | 66.7 | 67.5 | 45.0 |
| GuitarSet comp, Band | 180 | 1014 | 69.5 | 60.9 | 60.9 | 61.9 | 67.0 | 72.0 | 71.0 | 63.5 | 71.7 | 70.9 | 64.9 |
| GuitarSet comp, Guitar | 180 | 1014 | 70.0 | 60.9 | 60.9 | 61.9 | 67.4 | 72.3 | 71.8 | 63.8 | 72.1 | 71.8 | 65.3 |
| GuitarSet solo, Band | 180 | 1014 | 29.0 | 40.4 | 41.2 | 40.8 | 25.9 | 29.1 | 28.2 | 36.9 | 29.3 | 27.8 | 37.9 |
| GuitarSet solo, Guitar | 180 | 1014 | 28.8 | 40.4 | 41.2 | 40.8 | 26.1 | 29.6 | 28.3 | 37.6 | 29.5 | 28.1 | 38.2 |
| GuitarSet comp +39 c, Band | 180 | 984 | 51.2 | 26.6 | 25.6 | 49.9 | 37.3 | 49.3 | 50.1 | 33.1 | 52.1 | 52.9 | 51.7 |
| GuitarSet comp +39 c, Guitar | 180 | 984 | 51.6 | 26.6 | 25.6 | 49.9 | 38.0 | 50.7 | 51.3 | 33.2 | 53.2 | 53.0 | 51.6 |
| GuitarSet solo +39 c, Band | 180 | 984 | 25.2 | 23.0 | 23.2 | 40.2 | 21.0 | 24.7 | 25.1 | 24.3 | 25.4 | 25.6 | 36.0 |
| GuitarSet solo +39 c, Guitar | 180 | 984 | 26.4 | 23.0 | 23.2 | 40.2 | 21.3 | 25.5 | 26.5 | 24.6 | 27.2 | 27.1 | 36.7 |
| GTZAN (all), Band | 837 | 4221 | 44.7 | 58.8 | 59.5 | | 45.2 | 45.0 | 45.3 | 56.3 | | | |
| GiantSteps (all), Band | 604 | 16853 | 33.7 | 58.2 | 60.2 | | 35.6 | 34.3 | 35.3 | 56.1 | | | |

Relative % (Swift app / skey20): CSD 1.0 / 31.4; GuitarSet comp (Guitar) 12.4 / 13.1; solo
8.8 / 13.1; comp +39 c 11.2 / 9.9; solo +39 c 8.2 / 11.4; GTZAN 5.6 / 5.6; GiantSteps 9.2 / 6.4.

GTZAN per genre (Swift app / skey20, exact %): blues 10.8 / 30.4, country 75.6 / 80.4, disco
48.7 / 62.0, hiphop 23.6 / 47.1, jazz 31.2 / 39.8, metal 32.3 / 61.3, pop 65.1 / 76.4, reggae
62.7 / 72.6, rock 46.2 / 54.4 (blues is labelled 95 % minor in this set).

### 4c. Offline, whole clip, official pipeline (one reading per clip)

| set | clips | exact | MIREX | relative |
|---|---|---|---|---|
| singing (tests) | 45 | 75.6 | 84.4 | 0.0 |
| CSD | 84 | 35.7 | 59.0 | 38.1 |
| GuitarSet comp | 180 | 69.4 | 77.0 | 8.9 |
| GuitarSet solo | 180 | 49.4 | 61.7 | 9.4 |
| GuitarSet comp +39 c | 180 | 30.0 | 38.8 | 9.4 |
| GuitarSet solo +39 c | 180 | 22.2 | 33.2 | 14.4 |
| GTZAN | 837 | 65.8 | **74.4** (paper, 1M model: 74.4) | 4.1 |
| GiantSteps | 604 | 64.6 | **72.2** (paper: 72.1) | 5.6 |

### What the numbers say

* **Full mixes (Band): S-KEY is far better than the app**, with the default models (GTZAN +10,
  GiantSteps +15 to +18 exact) and without them (+14, +25). The app's errors there are mostly
  the wrong pair of keys, not major vs relative minor (app relative 5 % on GTZAN), so the
  pair-only rules gain only 1-4 points; `override` gains 8-15; S-KEY alone gains the most.
* **Strummed guitar (GuitarSet comp): the app is better** (71.8 vs 60.9). Only the cautious rules
  help, by about a point (`pair-undecided/20t` 72.7). `override` costs 5-7 points.
* **Solo guitar lines: S-KEY is better** (41 vs 35 exact with Basic Pitch).
* **Singing (Voices): S-KEY must not be used.** On real a cappella singing it calls major songs
  their relative minor 31 % of the time (app 2 %); every rule that lets it vote loses 20+ points.
  On the synthetic test tunes it is also weaker, mostly on scattered singers.
* **Detuning breaks S-KEY.** At +39 cents it drops from 61 to 27 % (comp) and from 40 to 23 %
  (solo); the app drops 72 -> 62 and holds 35 -> 38. Moving the audio onto concert pitch with the
  app's own tuning reading first restores most of it (50 and 40 %). Any use must do this.
* **Major vs relative minor, the promised strength, is not better overall.** S-KEY's
  relative-error rate is equal to or higher than the app's on singing, GuitarSet in tune and GTZAN;
  lower on GiantSteps (EDM, 84 % minor: 7 vs 11 %) and on the detuned takes (where S-KEY is mostly
  wrong in other ways). Where the app has not decided the mode, S-KEY makes fewer relative errors
  on strummed guitar (14 vs 23 %) but is right less often there (53 vs 58 % exact), and it is no
  better on solo lines (15 vs 15 %).
* **Window:** 30 s is better than 20 s only on GiantSteps (+2-3.5 exact); elsewhere they are within
  a point, and 20 s is better on short guitar takes and costs two-thirds.

## 5. Core ML

Measured with `convert_coreml.py` and `coreml_check.py` (coremltools 9.0, torch 2.7.1+cpu,
Linux, mlprogram, iOS 17, fixed input (1, 1, 84, T): T = 862 for 20 s, 1292 for 30 s):

| model | converts | .mlpackage | ops |
|---|---|---|---|
| official ChromaNet, as is | **no**: `TypeError: only 0-dimensional arrays can be converted to Python scalars` (an `int` op: `layer_norm`'s shape taken from `x.shape` and einops' rearrange in `OctavePool` trace to shape arithmetic) | | |
| `skey_export.ExportChromaNet` FP32, T 862 / 1292 | yes | 255 KB | 181 non-const: conv 15, reduce_mean 30, gelu 14, linear 14, pad (replicate) 7, transpose 14, add/sub/mul/real_div/sqrt, reshape 2, softmax 1 |
| same, FP16 | yes | 169 KB | same |
| whole graph incl. nnAudio VQT, FP32, 20 s of audio | yes | 307 KB | adds conv1d front end, pow/sqrt, log, reduce_max, clip; not checked numerically |

`ExportChromaNet` uses the same weights with static shapes (manual layer norm over C, H, W;
reshape for the octave average); vs the official module: max difference 1.9e-7.

Output vs PyTorch (Core ML itself cannot run on Linux; `coreml_check.py` runs the converted MIL
program with a NumPy/torch interpreter; FP16 = every op's result rounded to float16, computed in
float32 inside: a stand-in for GPU/ANE arithmetic, not a device measurement):

| program | windows (GTZAN/GuitarSet) | max abs probability difference, median / max | same top key |
|---|---|---|---|
| FP32, 20 s | 118 | 8.9e-8 / 5.1e-7 | 118/118 |
| FP16, 20 s | 118 | 3.2e-4 / 1.7e-3 | 118/118 |
| FP32, 30 s | 22 | 1.2e-7 / 3.3e-7 | 22/22 |
| FP16, 30 s | 22 | 3.6e-4 / 2.1e-3 | 22/22 |

What broke: (1) the official module (above). (2) Converting twice from the same torch module in
one process gave a **wrong second program** (its folded classifier bias came out as
(b + mean)/sigma instead of (b - mean)/sigma; the first program was right); converting each from
`copy.deepcopy` of the model fixes it, which the scripts do. Not done: running the .mlpackage on
a Mac or iPhone, an Android (LiteRT) conversion, and a phone-side VQT.

## 6. Speed

Measured with `bench_speed.py`: Intel Xeon @ 2.10 GHz (4 vCPU cloud VM, load average about 2.6
decaying from the evaluation runs that had just finished), torch 2.7.1+cpu, median (min) of 15 runs (5 for the full run).

| window | threads | official run (nnAudio VQT + net), ms | network only, ms | nnAudio VQT of 4 s, ms |
|---|---|---|---|---|
| 20 s | 1 | 1025 (1015) | 15.0 (14.0) | 177 (166) |
| 30 s | 1 | 1498 (1443) | 28.0 (26.0) | 171 (163) |
| 20 s | 4 | 328 (266) | 11.5 (10.9) | 58 (45) |
| 30 s | 4 | 530 (418) | 17.5 (14.7) | 50 (43) |

Almost all the time is nnAudio's VQT (98 % of a 20 s run); the network is 15-28 ms. Live, the
VQT frames would be computed once as audio arrives and kept, and only the network rerun every
4 s. **Estimated**: with a native multirate CQT (99 bins, hop 512, nine octaves of 128-tap
filters) the front end is well under 1 % of a core, so one reading every 4 s costs roughly
15-30 ms of CPU on this machine, a few times that on a mid-range phone; not measured on a phone.

## 7. Recommendation

1. **Do not ship the published weights.** They are licensed for non-commercial research only.
   Nothing below changes that; it only says whether a licence (or retraining) would be worth
   pursuing.
2. **If a licence can be had, S-KEY is worth it for Band only, as a replacement or strong vote,
   not as a major/minor tie-breaker.** On full mixes it beats the app by 10-18 points of exact key
   with the default models. Measured best: S-KEY alone with W = 30 s (GTZAN 58, GiantSteps 62 vs
   app 48, 44); `override/20` keeps most of that (57, 59). It must get audio moved onto concert
   pitch by the app's tuning reading first (the +39-cent test). Even where the app has decided the
   mode, S-KEY is more often right on these sets (GTZAN 63 vs 51 %, GiantSteps 61 vs 45 %), so
   a plain replacement is the simplest rule; check it on held-out data and live audio first.
3. **Guitar: at most the cautious vote.** `pair-undecided/20t` (S-KEY picks major vs relative
   minor only while the app is undecided, on tuned audio) gains about a point on comp and solo
   and does not lose at +39 cents; solo lines alone would favour S-KEY (+6), strumming the app (+11).
   A small gain for a 2-4 % CPU cost and a licence: not worth adding on its own.
4. **Voices: never.** It reads real a cappella singing as the relative minor a third of the time.
5. **Window:** 20 s for Guitar (short takes, faster), 30 s for Band if it is used.

Left to check before any app change: the weights licence (Deezer); live microphone audio (room,
phone mic, AGC; all tests here are clean recordings); key changes within a song; the .mlpackage
on a device and a LiteRT version for Android; a native streaming VQT and its cost on a phone; the
rule thresholds on held-out data (they were fixed a priori here and only `sum`'s weight was swept);
the GTZAN and GiantSteps comparisons with the default models on all clips (270 and 60 here); real
singing with minor keys (CSD is all major, so it only measures false minor readings).

## Reproducing

```
S=<scratch dir>; V=$S/skey/venv/bin/python   # shared venv (torch 2.7.1+cpu, torchaudio, coremltools 9.0,
                                              # soundfile, soxr, numpy) + nnAudio 0.3.3 (pip --no-deps)
git clone https://github.com/deezer/skey.git $S/skey/dl/skey_src            # 80d9590e
git clone https://github.com/alexanderlerch/gtzan_key.git $S/skey/dl/gtzan_key
git clone https://github.com/GiantSteps/giantsteps-key-dataset.git $S/skey/dl/giantsteps-key-dataset
./fetch_giantsteps.sh $S/skey/dl/giantsteps-key-dataset $S/skey/dl/giantsteps_audio
$V -I fetch_csd.py $S/skey/dl/csd && $V -I csd_labels.py $S/skey/dl/csd/CSD/english $S/skey/work/csd_labels.json
$V -I singing_synth.py $S/skey/work/singing
# GTZAN and GuitarSet audio as in tools/beatthis/README.md

# Swift driver: copy kckey/ outside the repo, then
KCORE=<repo>/ios/KeyCatchCore swift build -c release --scratch-path $S/skey/kc-key-build

W=$S/skey/work
$V -I skey_live_eval.py --work $W prepare --gtzan-audio .../genres --gtzan-keys $S/skey/dl/gtzan_key/gtzan_key/genres \
   --guitarset-audio .../mic --guitarset-ann .../annotation --giantsteps-audio $S/skey/dl/giantsteps_audio \
   --giantsteps-ann $S/skey/dl/giantsteps-key-dataset/annotations/key --singing $W/singing/singing.json \
   --csd-audio $S/skey/dl/csd/flac --csd-labels $W/csd_labels.json
$V -I skey_live_eval.py --work $W run --kckey $S/skey/kc-key-build/release/kckey --skey-src $S/skey/dl/skey_src --jobs 3
$V -I skey_live_eval.py --work $W check --skey-src $S/skey/dl/skey_src --n 40

# Kotlin engine with Basic Pitch / CREPE: a scratch copy of the repo (no .git, build, ios), with
# kt/KeyReadingsDump.kt in analysis/src/test/kotlin/com/donfluids/music/dsp/ and this at the end of
# analysis/build.gradle.kts:
#   tasks.withType<Test>().configureEach {
#       providers.gradleProperty("kc.list").orNull?.let { systemProperty("kc.list", it); maxHeapSize = "3g" }
#       providers.gradleProperty("kc.threads").orNull?.let { systemProperty("kc.threads", it) }
#   }
$V -I skey_live_eval.py --work $W ktrun --repo <copy> --datasets singing csd guitarset_comp guitarset_solo guitarset_comp_d39 guitarset_solo_d39
$V -I skey_live_eval.py --work $W ktrun --repo <copy> --datasets gtzan --sample-per-group 30
$V -I skey_live_eval.py --work $W ktrun --repo <copy> --datasets giantsteps --sample-per-group 60

$V -I skey_live_eval.py --work $W score [--brief] [--groups] [--app kt]
$V -I convert_coreml.py --skey-src $S/skey/dl/skey_src --out $W/coreml
$V -I coreml_check.py --skey-src $S/skey/dl/skey_src --work $W --n 120
$V -I bench_speed.py --skey-src $S/skey/dl/skey_src
```

Each step skips work already done (results are written atomically), so a killed run restarts.
Run time here: `run` about 1.5 h on 3 workers for all 2,290 clips; `ktrun` about 1 h on 2-3 threads.
