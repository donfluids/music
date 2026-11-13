# Key finding for Voices and Guitar: candidate changes, measured

These tests measure candidate changes to KeyCatch's key finder for the Voices (solo singing) and
Guitar modes against the current method. No app code was changed. All changes were tested only in a
scratch copy of the repo (`kt/scratch_edits.py`). The harness, live schedule and scoring are the
same as in `tools/skey`. Readings are taken at 10, 14, 18, ... s into each clip. A reading scores
when the key is exact; **relative %** is the share of readings that name the true key's relative
major or minor. Each cell below reads "exact % / relative %". Numbers are **measured** unless
marked **estimated**.

Rules were fixed before scoring. Trained methods use grouped cross-validation, and only
held-out numbers are reported:
* GuitarSet: leave-one-progression-out, 15 folds. Each progression is played by 6 players and in
  2 tempos/keys, and all of those takes are held out together.
* Voices: 5 folds by song. A +39 c copy stays in the same fold as its original.

**Short answer.**
* **Guitar.** Swapping the Krumhansl-Kessler profile for Temperley 1999 (and keeping 8 s of
  memory) gains +13.7 on solo lines and +6.7 on solo lines at +39 c. Comp and comp +39 c stay
  level (-0.3 / 0.0). A longer memory alone gains about 1 point on comp. A trained chord-root model
  (C3) on the app's own chords reaches 82-85 % on comp against 71.8 %, but only when it is trained
  on GuitarSet. Trained on the RS200 rock corpus, it does no better than the app's current chord
  rule, so that gain is mostly in-domain. Refitting the major/minor weights (C2) gains nothing.
* **Voices.** No literature profile beats the current Temperley 1999 profile across the sung
  sets. Every swap loses real a cappella singing (CSD), several by 10-30 points. Refitting the
  RelativeKeyResolver weights (C2) gains +0.9 over all readings, held out. Real minor singing is
  scarce: CSD has none, and only 6 GTSinger clips pass the labelling rule. The largest problem
  found is detuning: the RS200 sung tunes drop from 70.6 to 49.5 % at +39 cents.

## Files

| file | does |
|---|---|
| `keyeval_common.py` | key indices, profiles (with sources), scoring, the app's ChordKeyEstimator ported, RS200 root counts |
| `oracle_guitarset.py` | step 1: C1/C4/C3 on GuitarSet's JAMS notes, chords and beats (perfect transcription) |
| `oracle_voices.py` | the same for the sung sets' own note lists (CSD CSV, RS200 notes, GTSinger notes) |
| `kt/KeyVariantsDump.kt` | JVM driver: several key-finder variants per clip in lockstep, sharing one Basic Pitch / CREPE; dumps readings, mode evidence and chord segments |
| `kt/scratch_edits.py` | instruments a scratch copy (profile override, shared Basic Pitch, evidence hook, gradle properties) |
| `kv_run.py` | runs the driver over clips in batches (audio written as f32 at 44.1 kHz, +39 c by resampling, as tools/skey) |
| `score_variants.py` | exact / relative % per data set, per mode, per variant |
| `c2_resolver.py` | C2: replays RelativeModeTracker (and the chord override for Guitar) on the dumped evidence with logistic-regression weights, CV |
| `c3_app_chords.py` | C3 on the app's own ChordTracker output (GuitarSet comp) |
| `fetch_csd_lang.py` | CSD Korean half by range requests (labels: `tools/skey/csd_labels.py`) |
| `gts_select.py`, `gts_audio.py` | GTSinger: choose clips, label them from their MusicXML notes, fetch and join the audio |
| `rs200_synth.py` | sung tunes from RS200 melodies, rendered with the voice from `tools/skey/singing_synth.py` |

`rs200_synth.py` and `fetch_csd_lang.py` import `singing_synth.py` and `remote_zip.py` from `../skey`.

## Profiles and sources

The profile numbers come from AMADS (MIT licence), github.com/music-computing/amads at
`f250bbd99b9ab18543d3c92ac110e2c12cee58f8`, `amads/pitch/key/profiles.py`:
* `KrumhanslKessler` (the app's Guitar/Band `KeyDetector`; identical numbers)
* `Temperley` (Temperley 1999: the app's Voices `MelodicKeyFinder`; identical)
* `AardenEssen`
* `TemperleyDeClerq` (`major`/`minor` = melody, `harmony_major`/`harmony_minor`)
* `AlbrechtShanahan`
* `DeClerqTemperley.roots` (rock root distribution, used in C3-dt).

Matching is the app's own rule: Pearson correlation with the 24 rotated profiles. Learned models
(C3, C4) use a log-likelihood with add-0.5 smoothing.

## Data and labels

| set | clips (major / minor) | readings | source, licence | labels |
|---|---|---|---|---|
| GuitarSet comp, solo, each also +39 c | 180 each (120 / 60) | 1014 (984 at +39 c) | as tools/skey (CC BY 4.0) | JAMS `key_mode` |
| CSD English | 84 (84 / 0) | 1592 | tools/skey (CC BY-NC-SA 4.0, evaluation only) | derived (`csd_labels.py`) |
| CSD Korean | 86 labelled (86 / **0**) of 100 | not run | zenodo 4785016, notes only fetched | same rule. No minor song. The 14 unlabelled ones fit no harmonic minor either. Audio not fetched (adds only major). |
| app test tunes (`singing`) | 45 (44 / 1) | 222 | tools/skey `singing_synth.py` | the tests' expectations |
| GTSinger, also +39 c | 14 (8 / **6**) | 321 (314) | huggingface GTSinger/GTSinger @ `4426c862` (CC BY-NC-SA 4.0, evaluation only) | from the MusicXML notes, rule below |
| RS200 sung tunes, also +39 c | 104 = 52 tunes x 2 singers (52 / 52; 26 minor songs) | 1316 (1296) | rockcorpus.midside.com `rs200_melody_nlt.zip` sha256 `92d2c3cf...`, `rs200_harmony_clt.zip` sha256 `796582dc...` (CC BY 4.0) | derived from the harmonic analyses and the melody, rule below |

**GTSinger.** The scores' own `<key>` elements are unusable. They agree with the notes' best
major scale in only 24 of 100 English clips (for example 4 flats written over F-sharps). Labels
therefore come from the MusicXML **notes**, with this rule:
* The tonic is the clip's last note, and it must also be the pitch class held longest.
* The mode is the one whose scale covers at least 95 % of the note time: major, or natural minor
  plus the raised 7th. If both or neither do, the clip is left out.

The written pitches themselves are noisy: most clips cover only 70-90 % of any scale. So few
clips pass: 14 of 664 Control_Group clips in 9 languages (English 5, French 5, German 2,
Italian 1, Japanese 1). A clip is one singer's Control_Group of one song, with its segments
joined by 0.4 s of silence. Only the scores of candidate clips and the audio of the 14 labelled
clips were fetched.

**RS200.** A song is used only when it stays in one key. It is labelled minor when at least 2/3
of the tonic-chord time is a minor tonic chord and the melody's b3 is held at least twice as long
as its 3. Major is the mirror case. Of 200 songs, 116 are labelled (26 minor, 90 major). All 26
minor songs and the first 26 major ones (by name) were rendered. Each clip is the first 60 s of
melody, with notes lasting min(0.92 x IOI, one bar). There are two synthetic singers: good (15 c
scatter, 80 ms glides) and average (30 c, 120 ms).

## Guitar

### Step 1: oracle on GuitarSet annotations (perfect transcription), live readings

C1 = fixed profiles, with "8s" = exponential 8 s memory and no suffix = the whole take so far.
C4 and C3 are cross-validated. Cells are exact % / relative %.

| method | comp | solo |
|---|---|---|
| C1 K-K (whole / 8s) | 69.1 / 10.7 ; 64.0 / 11.8 | 38.5 / 11.2 ; 37.5 / 11.9 |
| C1 Temperley 1999 | 61.8 / 29.6 ; 64.5 / 23.3 | **50.5** / 22.8 ; **51.4** / 17.9 |
| C1 Aarden-Essen | 63.8 / 10.0 ; 58.7 / 10.7 | 49.5 / 19.3 ; 47.3 / 17.4 |
| C1 TdC melody | 45.4 / 14.8 ; 43.2 / 13.8 | 37.6 / 15.7 ; 36.3 / 14.5 |
| C1 TdC harmony | 61.8 / 13.8 ; 62.4 / 13.6 | 45.6 / 13.0 ; 41.2 / 14.1 |
| C1 Albrecht-Shanahan | 68.2 / 11.5 ; 60.6 / 12.4 | 48.6 / 15.5 ; 45.3 / 16.5 |
| C4 learned 12-bin profile (CV) | 83.1 / 8.8 | 53.6 / 20.8 |
| C4 interval profile (CV) | | **57.7** / 14.5 |
| C3-M5 roots, each chord once (CV) | 100.0 / 0.0 | |
| C3-M6 half-bar tokens (CV) | 100.0 / 0.0 | |
| C3-M7 strong/weak (CV) | 98.2 / 0.0 | |
| C3-M8 half-bar + strong/weak (CV) | 100.0 / 0.0 | |
| C3-dt, fixed rock roots, mode from tonic chord | 62.7 / 0.0 | |
| C3-M6s profiles from RS200 (fixed, no GuitarSet in training) | 91.7 / 5.3 | |
| the app's ChordKeyEstimator rule on true chords | 94.1 / 5.9 | |

Clips and readings: 180 / 1014 per column. The chords are the lead-sheet ("instructed") chords.
Strong = the downbeat of an odd-numbered bar.

What this says:
* **Solo is mostly a method problem.** K-K reaches only 38.5 % even from perfect notes, while
  Temperley 1999 reaches 50.5 % and interval profiles 57.7 %.
* **Comp is mostly a transcription and chord problem.** From perfect chords, even the app's own
  rule reaches 94 %.
* GuitarSet's lead sheets are so regular that any root model trained on GuitarSet is perfect.
  This explains the CV-trained C3 results on app chords below.

### Step 2: app path (Kotlin, Basic Pitch, Guitar preset), memory x profile

| memory, profile | comp | solo | comp +39 c | solo +39 c | comp, minor takes | mean of 4 |
|---|---|---|---|---|---|---|
| **8 s, K-K (current)** | 71.8 / 12.2 | 35.2 / 11.4 | 61.9 / 13.8 | 38.1 / 12.2 | 50.3 / 27.2 | 51.8 |
| **8 s, Temperley 1999** | 71.5 / 13.2 | **48.9** / 15.8 | 61.9 / 14.5 | **44.8** / 13.8 | 47.0 / 32.3 | **56.8** |
| 8 s, Aarden-Essen | 71.6 / 12.3 | 42.4 / 15.0 | 60.0 / 14.1 | 41.4 / 14.3 | 53.0 / 25.5 | 53.8 |
| 8 s, TdC melody | 69.5 / 13.2 | 33.3 / 12.9 | 58.7 / 14.1 | 34.5 / 11.0 | 44.4 / 31.7 | 49.0 |
| 8 s, TdC harmony | 70.2 / 13.6 | 41.2 / 12.9 | 60.2 / 14.5 | 41.6 / 13.0 | 47.3 / 30.9 | 53.3 |
| 8 s, Albrecht-Shanahan | 70.7 / 12.8 | 41.7 / 14.1 | 59.9 / 14.4 | 41.8 / 13.8 | 50.3 / 27.4 | 53.5 |
| 20 s, K-K | **72.9** / 11.7 | 37.2 / 11.1 | 61.0 / 13.4 | 39.4 / 12.8 | 51.6 / 26.9 | 52.6 |
| 20 s, Temperley 1999 | 69.9 / 14.7 | 50.5 / 16.1 | 61.7 / 14.5 | 44.3 / 13.2 | 41.1 / 37.9 | 56.6 |
| 20 s, Aarden-Essen | 71.6 / 12.8 | 44.1 / 16.6 | 60.4 / 14.1 | 42.3 / 13.2 | 50.8 / 28.2 | 54.6 |
| 20 s, Albrecht-Shanahan | 71.4 / 12.8 | 44.6 / 14.4 | 60.2 / 14.7 | 42.6 / 13.8 | 49.2 / 29.8 | 54.7 |
| 30 s, K-K | 72.8 / 11.7 | 37.2 / 11.7 | 61.3 / 12.9 | 38.2 / 13.8 | 51.3 / 27.2 | 52.4 |
| 30 s, Temperley 1999 | 68.8 / 15.7 | **50.8** / 16.1 | 62.0 / 14.0 | 43.7 / 13.7 | 38.2 / 40.6 | 56.3 |
| whole take, K-K | 71.9 / 12.6 | 37.4 / 12.3 | 60.3 / 13.4 | 38.5 / 13.0 | 48.7 / 29.8 | 52.0 |
| whole take, Temperley 1999 | 68.2 / 16.3 | 49.2 / 17.4 | 61.3 / 14.5 | 41.2 / 15.7 | 36.6 / 42.2 | 55.0 |

* Clips and readings: 180 per set, with 1014 / 1014 / 984 / 984 readings (comp minor: 60 clips,
  372 readings).
* All 24 combinations are in `work/guitar_scores.md`. TdC melody/harmony at 20/30 s and whole
  take are all below the current method.
* The current row reproduces tools/skey's numbers exactly (71.8, 35.2, 61.9, 38.1).
* Temperley 1999 makes more relative-minor errors on minor strumming (27 to 32 % at 8 s). A longer
  memory makes this worse with Temperley 1999 and helps K-K slightly.

### Step 4: C3 on the app's own chords, and C2

All cells are held out. The app's own display key scores 71.8 / 61.9.

| method on the app's ChordTracker segments | comp | comp +39 c |
|---|---|---|
| the app's ChordKeyEstimator rule (ported) | 69.4 / 13.3 | 58.9 / 15.9 |
| M6s, RS200-trained (fixed, independent corpus) | 68.0 / 13.5 | 55.0 / 16.0 |
| M6s (segment durations), trained on GuitarSet annotations | 81.7 / 5.3 | 68.2 / 7.3 |
| M6s, trained on the app's chords for GuitarSet | 82.2 / 4.9 | 72.6 / 6.9 |
| M8b (half bars, strong/weak, **annotated** beats), annotation-trained | 83.4 / 4.3 | 68.1 / 7.4 |
| M8b, app-chord-trained | **84.8** / 4.4 | **73.3** / 7.3 |
| C2 (LR refit of resolver weights; see the step 2 table) | 71.3 / 12.2 | 61.4 / 13.1 |

* Counts: 180 clips and 1014 readings for comp; 179 clips and 979 readings for comp +39 c. One
  +39 c take lacks the chord dump because a killed first run wrote it.
* C2 on solo: 35.7 (solo) and 39.1 (solo +39 c). C2-bal: 36.2 and 38.1.
* The replay of the app with its own weights matches 98.9 % of the app's readings. The C2 rows
  should therefore be compared with the replayed app: 71.1 / 35.3 / 61.1 / 38.0.
* The C2 weights are stable across folds: leading tone 0.6-0.9, chords 0.5-0.8, profile 1.2-1.3,
  bias 0.1-0.5. The model wants the profile term a little stronger. The accuracy does not move.
* C3 is scored **alone**, as a replacement for the shown key on comp. It cannot read solo lines
  (no chords). Its effect inside the app, as a replacement for ChordKeyEstimator in the chord
  override, is **not measured**.

## Voices

### Oracle on the sung sets' own notes (no CREPE, no drift)

Cells are exact % / relative %. Input is the app's MelodicKeyFinder weighting.

| profile | CSD major | RS200 major | RS200 minor | GTSinger major | GTSinger minor |
|---|---|---|---|---|---|
| Temperley 1999 (current) | **97.6** / 0.0 | **77.8** / 6.8 | 70.4 / 10.5 | 87.0 / 10.4 | 47.9 / 12.7 |
| Krumhansl-Kessler | 68.5 / 1.9 | 57.1 / 15.7 | 74.2 / 1.0 | 83.1 / 15.6 | **84.2** / 5.5 |
| Aarden-Essen | 86.1 / 5.7 | 59.5 / 29.9 | 65.9 / 4.8 | 41.6 / 24.0 | 61.2 / 0.6 |
| TdC melody | 80.0 / 0.4 | 58.6 / 11.8 | 70.1 / 8.0 | 43.5 / 14.3 | 42.4 / 17.0 |
| TdC harmony | 84.7 / 0.9 | 65.1 / 19.5 | **80.9** / 7.0 | 85.1 / 10.4 | 57.0 / 6.1 |
| Albrecht-Shanahan | 92.9 / 2.4 | 68.0 / 21.3 | 80.3 / 4.5 | 83.8 / 10.4 | 57.0 / 6.1 |
| C4 interval profile (CV) | 96.7 / 1.9 | 60.7 / 31.4 | 77.4 / 7.6 | 70.1 / 11.7 | 36.4 / 23.6 |

Clips and readings: CSD 84 / 1592; RS200 52 / 676 and 52 / 628; GTSinger 8 / 154 and 6 / 165.
Plain duration weighting gives about the same results (`work/oracle_voices.md`).

### Steps 2-3: app path (Kotlin, CREPE, Voices preset)

The C1 rows swap the MelodicKeyFinder profile, which also feeds the resolver's profile term and
the pair-clarity check. The C2 rows refit the RelativeKeyResolver weights by logistic regression,
5-fold CV by song, held-out readings only. The replay with the app's weights matches 100 % of its
readings.

| variant | CSD (major) | test tunes | RS200 maj | RS200 min | RS200 +39 maj | RS200 +39 min | GTS maj | GTS min | GTS +39 maj | GTS +39 min | all | all minor |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **current: Temperley 1999** | **86.9** / 1.8 | 81.1 / 0.0 | 68.7 / 8.0 | 72.6 / 8.8 | 51.6 / 9.1 | 47.3 / 7.4 | 76.1 / 3.2 | 31.3 / 27.7 | 47.7 / 4.6 | 35.0 / 25.8 | 67.9 / 6.8 | 54.5 / 11.9 |
| C1 Krumhansl-Kessler | 54.8 / 6.2 | 78.4 / 0.0 | 52.1 / 14.5 | 56.6 / 6.7 | 43.2 / 13.8 | 47.3 / 6.6 | 71.0 / 9.7 | **86.7** / 3.6 | 48.3 / 9.3 | **82.2** / 10.4 | 55.4 / 8.4 | **58.7** / 6.7 |
| C1 Aarden-Essen | 79.8 / 8.2 | **89.6** / 0.0 | 67.7 / 17.7 | 49.4 / 10.8 | 51.6 / 17.1 | 36.1 / 7.2 | 41.9 / 22.6 | 55.4 / 4.8 | 21.9 / 12.6 | 53.4 / 4.9 | 61.1 / 10.8 | 45.2 / 8.2 |
| C1 TdC melody | 65.3 / 3.0 | 83.3 / 0.0 | 50.1 / 13.0 | 61.9 / 6.7 | 41.4 / 13.2 | 49.2 / 6.2 | 41.9 / 8.4 | 48.2 / 13.9 | 24.5 / 6.0 | 39.3 / 22.7 | 55.1 / 7.7 | 53.2 / 8.9 |
| C1 TdC harmony | 67.7 / 5.4 | 87.4 / 0.0 | 58.3 / 17.0 | 69.6 / 7.5 | 48.2 / 13.8 | 51.3 / 8.0 | 76.8 / 6.5 | 48.2 / 16.9 | 53.0 / 6.6 | 41.1 / 25.8 | 61.3 / 9.5 | 57.3 / 10.6 |
| C1 Albrecht-Shanahan | 84.3 / 6.2 | 86.9 / 0.0 | 58.7 / 19.9 | 66.1 / 5.6 | 51.0 / 15.2 | 48.4 / 6.6 | 78.7 / 6.5 | 51.2 / 10.2 | 57.6 / 4.0 | 41.7 / 22.1 | 66.4 / 9.5 | 55.1 / 8.2 |
| C2 (LR, held-out) | **87.8** / 0.9 | 81.1 / 0.0 | 69.6 / 7.1 | 73.5 / 7.8 | 53.0 / 7.7 | 47.3 / 7.4 | 79.4 / 0.0 | 31.9 / 27.1 | 52.3 / 0.0 | 33.7 / 27.0 | **68.8** / 5.9 | 54.8 / 11.6 |
| C2-bal (LR, classes balanced, held-out) | 85.2 / 3.5 | 81.1 / 0.0 | 62.1 / 14.6 | 75.1 / 6.3 | 48.2 / 12.5 | 50.0 / 4.6 | 72.3 / 7.1 | 34.9 / 24.1 | 49.7 / 2.6 | 35.6 / 25.2 | 66.7 / 8.0 | 57.0 / 9.4 |
| clips | 84 | 45 | 52 | 52 | 52 | 52 | 8 | 6 | 8 | 6 | 365 | 116 |
| readings | 1592 | 222 | 678 | 638 | 672 | 624 | 155 | 166 | 151 | 163 | 5061 | 1591 |

What this says:
* Temperley 1999 is the best single profile for major material, real or synthetic. K-K is far
  better on the 6 real minor GTSinger clips (86.7 vs 31.3) but loses 32 points on CSD.
* No fixed profile wins on both. The oracle shows the same trade-off, so the trade-off belongs to
  the method, not to CREPE.
* The C2 weights are not stable across folds: the "approaches" term ranges from -3.9 to +3.9.
  The fit leans on the profile term (2.5-4.0) and the leading tone (1.0-1.5) and keeps a bias of
  about +0.4 toward major. The gain is +0.9 overall, held out, and none on minor material.
* **Detuning (measured, not a key-method finding):** every Voices variant loses about 20 points on
  the RS200 tunes at +39 c (70.6 -> 49.5 for the current method) and 12 on GTSinger. GuitarSet
  loses less than that through Basic Pitch. The singer tuning model or note segmentation near
  half a semitone looks like the place to look. This was not investigated.
* Mode errors on real minor singing are large: GTSinger minor readings are relative-major 28 % of
  the time. The data behind this (6 clips, 3 singers) is too small to tune on.

## Recommendations

| mode | change | expected gain | port effort (Kotlin + Swift) |
|---|---|---|---|
| Guitar | use Temperley 1999 instead of K-K in KeyDetector **for the Guitar preset only**; keep 8 s memory | **measured** on GuitarSet: solo +13.7, solo +39 c +6.7, comp -0.3, comp +39 c 0.0; comp minor takes -3.3 (more relative errors) | small: a per-preset profile in `KeyDetector.kt` / `KeyDetector.swift` (12+12 numbers, used by `MusicAnalyzer` and the resolver's chroma profile term); Band must keep K-K unless it is measured (not done here) |
| Guitar | key memory 8 -> 20 s (only if the profile stays K-K) | measured: comp +1.1, solo +2.0, +39 c -0.9 / +1.3 | trivial: `keyMemorySeconds` in `AnalysisPreset.kt` and `Music.swift` |
| Guitar | a learned chord-root model (C3) in place of ChordKeyEstimator | **estimated / uncertain**: +10 to +13 on comp when trained on GuitarSet (held-out progressions), but -4 to -7 when trained on RS200. Expect little on real songs until it is trained on chord data like the app's | medium: 2 x 2 x 24 log-probabilities plus a half-bar tokeniser (it needs the bar position) in both apps; training data is the open question. Do not ship yet |
| Guitar | C2 resolver refit | none (+-1) | not worth it |
| Voices | keep Temperley 1999 | the profile swaps lose 2.6-32 points on CSD | none |
| Voices | C2 refit of RelativeKeyResolver | +0.9 overall (CSD +0.9, RS200 +0.9, GTSinger major +3.3), held out; none on minor | small (5 weights and a prior in `RelativeKeyResolver.kt/.swift`), but the weights are unstable across folds. Not recommended until there is more real minor singing |
| Voices | look into +39 c robustness (singer tuning / segmentation) | the largest loss found: -21 points on RS200 tunes | not scoped |

## Reproducing

```
S=<scratch>; V=$S/skey/venv/bin/python; K=$S/keyeval      # the tools/skey venv (numpy, soundfile, soxr)
cp -a <repo copy without .git> $K/repo && $V -I kt/scratch_edits.py $K/repo   # never the real checkout
$V -I oracle_guitarset.py <GuitarSet annotation dir> $S/skey/work $K/work/oracle.json --rs200 rs200_harmony_clt.zip
# Guitar: 24 variants in lockstep (about 70 min on 3 threads for the 720 GuitarSet clips)
$V -I kv_run.py --items $S/skey/work/items.json --datasets guitarset_comp guitarset_solo guitarset_comp_d39 guitarset_solo_d39 \
   --preset guitar --variants "app:app,8:t99,...,inf:as" --repo $K/repo --out $K/work/kv_guitar --evidence --jobs 3
$V -I score_variants.py --items $S/skey/work/items.json --kv $K/work/kv_guitar --skey-work $S/skey/work
$V -I c2_resolver.py --mode guitar --items $S/skey/work/items.json --kv $K/work/kv_guitar --datasets ... --skey-work $S/skey/work
$V -I c3_app_chords.py <GuitarSet annotation dir> $K/work/kv_guitar $S/skey/work --rs200 rs200_harmony_clt.zip
# sung sets
$V -I fetch_csd_lang.py korean $K/dl/csd_ko --notes-only && $V -I ../skey/csd_labels.py $K/dl/csd_ko/CSD/korean $K/work/csd_ko_labels.json
curl .../processed/<Language>/metadata.json   # GTSinger metadata, one per language
python3 -I gts_select.py <metadata.json ...> --xml-dir $K/dl/gtsinger_xml --out $K/work/gts_labels.json --fetch --prefilter
$V -I gts_audio.py $K/work/gts_labels.json $K/dl/gtsinger_wav $K/work/gtsinger
$V -I rs200_synth.py rs200_melody_nlt.zip rs200_harmony_clt.zip $K/work/rs200_sung --max-major 26
# voice_items.json = tools/skey's csd + singing items, plus rs200 and gtsinger items (detune 0 and 39)
$V -I kv_run.py --items $K/work/voice_items.json --datasets singing csd rs200 rs200_d39 gtsinger gtsinger_d39 \
   --preset voices --variants "app:app,app:kk,app:ae,app:tdcm,app:tdch,app:as" --repo $K/repo --out $K/work/kv_voices --evidence --jobs 4
$V -I score_variants.py --items $K/work/voice_items.json --kv $K/work/kv_voices --skey-work $S/skey/work
$V -I c2_resolver.py --mode voices --items $K/work/voice_items.json --kv $K/work/kv_voices --datasets singing csd rs200 rs200_d39 gtsinger gtsinger_d39 --skey-work $S/skey/work
$V -I oracle_voices.py --items $K/work/voice_items.json --csd-csv <CSD english csv> --rs200 $K/work/rs200_sung/rs200.json \
   --rs200-mel rs200_melody_nlt.zip --gts-clips $K/work/gtsinger/gtsinger.json --gts-meta <metadata.json ...> --skey-work $S/skey/work
```
