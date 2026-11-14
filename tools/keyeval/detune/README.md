# Voices off concert pitch: why +39 cents loses 20 points, and a fix

These tests trace the Voices path (CREPE, note segmentation, SingerModel, KeyLevelTracker,
MelodicKeyFinder, RelativeKeyResolver) stage by stage. They compare in-tune clips with copies
detuned by resampling (as `tools/skey` and `../kv_run.py` do). The schedule and scoring are those
of `tools/keyeval`: the shown key at 10, 14, 18, ... s, exact %, with relative % second. All
numbers are measured. No app code was changed. Everything ran in scratch copies of the repo.

## Short answer

* **Cause.** The singer model (`SingerModel` in `PitchTracker.kt`) does not move its grid for a
  scattered singer. It moves by `0.8 x excess^2 x mean`, where `excess` is the notes' circular
  consistency beyond chance over an 8 s memory. For 30 cents of scatter that consistency (about
  0.17) never clears the chance level (about 0.2). So for the "average" RS200 singer at +39 c, the
  grid stays near 0 (median 6 c, 9 c at 30 s). About 29 % of the note time then rounds to the
  semitone above, and the key finder sees a mix of K and K+1 (pair errors +1, +5, +8). The key-level
  tracker does see the singer at about +40. But it is only used when the singers wander 70 c or
  more, and its centre (10-cent lattice) leans toward 50 for scattered singers: 24 % of its
  readings are at 50, where the key flips.
* **Who loses.** The average (30 c) singers go from 70.2 to 32.3. The good (15 c) singers go
  from 71.0 to 66.8: their grid follows to +38 and 95 % of notes are right. CREPE is not
  involved (median note error 0.0 c), and neither is segmentation (the segmented notes average
  +39.0 c, sd 29 c).
* **+39 alone is hard only for scattered singers.** 11 c from the boundary, a 30 c singer
  gives too little per-note information. Even a whole-tune scale fit misses by more than 11 c in
  16-19 % of clips at 14-30 s (`dt_estimators.py`).
* **Native tuning.** The rendered RS200 tunes have no offset (0.1 c). The real sets do. CSD
  sits at -8 c (deciles -15 to -2). GTSinger clips sit between -20 and +15 c, so two of the 14
  "+39" GTSinger copies are really at +46 and +54. For the second, the app rightly names the key
  a semitone up, and the label counts that as wrong.
* **Noise floor.** A +10 c copy of a good singer, with 98 % of notes right, still loses 5 points
  (71.0 to 66.0): readings flip on small changes. So differences of a few points are within noise.

## Before / after (exact / relative %)

* **current** is HEAD 9ad92ca.
* **fix** is the proposed patch (`detune_fix.patch`, applied by `apply_fix.py`), exactly as given.
* **35 c prototype** is a less cautious version. Its variants with a 30 c or 35 c gate fail
  SingerRobustnessTest.
* **oracle grid** uses the true detune as the grid for the key notes: the most that any tuning
  estimate can gain.

| set | clips | readings | current | fix | 35 c prototype | oracle grid |
|---|---|---|---|---|---|---|
| RS200 sung, in tune | 104 | 1316 | 70.6 / 8.4 | 70.2 / 8.1 | 70.4 / 8.1 | |
| RS200 +39 c | 104 | 1296 | 49.5 / 8.3 | **52.7** / 7.9 | 57.3 / 8.1 | 65.2 / 9.9 |
| (average singer +39) | 52 | 648 | 32.3 / 7.7 | 38.4 / 7.1 | 47.5 / 7.4 | 61.3 / 10.3 |
| (good singer +39) | 52 | 648 | 66.8 / 8.8 | 67.0 / 8.8 | 67.0 / 8.8 | 69.1 / 9.4 |
| RS200 +10 / +20 / +30 | 104 | ~1300 each | 64.7 / 62.6 / 60.8 | 64.8 / 63.7 / 62.2 | 65.6 / 64.3 / 64.6 | |
| RS200 +45 / +50 / -39 | 104 | ~1300 each | 37.6 / 28.2 / 57.4 | 39.0 / 31.2 / 58.2 | 46.7 / 34.5 / 60.1 | |
| GTSinger in tune | 14 | 321 | 53.0 / 15.9 | 52.6 / 15.9 | 52.3 / 15.9 | |
| GTSinger +39 c | 14 | 314 | 41.1 / 15.6 | **45.2** / 14.6 | 45.9 / 14.6 | |
| GTSinger +30 / +45 / -39 | 14 | ~315 each | 50.6 / 34.7 / 38.2 | 53.8 / 39.2 / 39.1 | 54.1 / 39.2 / 40.3 | |
| CSD English | 84 | 1592 | 86.9 / 1.8 | 86.4 / 1.8 | 87.1 / 1.7 | |
| CSD English +39 c | 84 | 1562 | 85.7 / 1.4 | 86.0 / 1.3 | 85.7 / 1.5 | |
| app singing test tunes | 45 | 222 | 81.1 / 0.0 | 81.1 / 0.0 | 81.5 / 0.0 | |

The **fix** column is the patch as given. Its parameters were fixed before its scores were
computed. The 25 c gate was chosen as the widest gate that passes the unit tests, not to raise
any score.

RS200 0/+39 and GTSinger 0/+39 were in view while the prototypes were explored, so they are not
strictly held out. CSD, CSD +39, the test tunes and the other detunes were not used to choose
anything.

Unit tests:
* Kotlin, `./gradlew --offline :analysis:testDebugUnitTest` on 9ad92ca plus the fix: 154 tests,
  0 failures, 3 skipped. This is the same as 9ad92ca alone.
* Swift, the voice tests in a Linux scratch build of `ios/KeyCatchCore` with the port applied
  (SingerModel, SingerRobustness, WanderingSingers, KeyLevelTracker, MelodyKey, RelativeKey):
  37 tests, 0 failures, the same as 9ad92ca.

## The proposed change

The patch is `detune_fix.patch` against 9ad92ca: Kotlin and Swift, about 85 added lines each in
three files per platform. It makes three edits:

1. `KeyLevelTracker`: a new `Level.steadyCents` field. It is the centre moved by the notes'
   duration-weighted mean signed distance from the scale (notes within 1.5 x spread). This places
   a steady level more finely than the 10-cent lattice and corrects its pull toward 50. The curve
   itself, which the wandering case uses, is unchanged.
2. `SingerModel.adopt(cents)`: moves the grid and turns the circular sums, keeping the lean and
   the relabelling consistent.
3. `MusicAnalyzer.updateKey`: when the level is not used for wandering, three conditions must
   hold: it is steady (10-90 % range of 40 c or less, now within 30 c of the centre), it is tight
   (spread of 25 c or less), and it sits 10 c or more from the singer grid. Then the grid adopts
   `steadyCents`. While such a level is held, decision caution (`keySpreadCents`) also counts the
   level's spread.

**Risk.** The 30 and 35 c gates, and the version without the steadiness gate, all fail
`SingerRobustnessTest.aPoorOrDriftingAndSlidingSingerIsABestGuessNeverAFalseCertainty`: one seed
of a poor singer drifting flat is shown a wrong pair as decided. Without the range gate,
`WanderingSingersTest.wobblingBetweenCAndDCentresOnCSharp` fails. At 25 c most average (30 c)
singers are not covered (the level's spread has a median of 27 c), so the gain is modest. The
larger gain in the 35 c column needs a better measure of decision caution than the singer model's
spread, which looks tighter once the grid is right. That was not solved here.

Tried and not kept (RS200 +39 / in tune):
* a longer or slower singer-model memory: 50.6 / 70.6. The excess-squared gate still blocks it.
* the steady level's offset for the key notes only: 52.8 / 70.7. It flips at 50.
* always using the key level: 52.9 / 70.3.
* grid from the best MelodicKeyFinder score over offsets: about 31 / 58, much worse. It is drawn to half a
  semitone.
* a whole-tune scale fit fed to the singer model: 55.9 / 70.2.

## Files

| file | does |
|---|---|
| `scratch_trace.py` | prepares a scratch copy: exposes MusicAnalyzer's singer model, key level, segmenter and key notes (`internal`), adds `DetuneFix` flags (empty = the app), forwards `dt.*` gradle properties, copies `DetuneTrace.kt` |
| `DetuneTrace.kt` | JVM driver: the Voices analyser per clip. Once a second it writes the reading plus grid, lean, level, sigma and the top keys. At the live moments it also writes the key notes. CREPE's answers are cached per clip and replayed |
| `dt_run.py` | runs it over items at several detunes (`<id>_d39`, `_dm39`), in batches, with audio resampled as tools/skey. It reports clips that failed |
| `dt_truth.py` | the RS200 notes as rendered (pitch, scatter), for note-level checks |
| `dt_analyse.py` | stage table per set: exact, pair +1, grid at 10/30 s, lean, level use and centre, sigma, share of note time on the right / +1 / -1 pitch class, CREPE bias |
| `dt_compare.py` | exact / relative % for several runs side by side (the table above) |
| `dt_estimators.py` | offline comparison of tuning estimators on the segmented notes |
| `apply_fix.py`, `apply_fix_swift.py` | the proposed change, applied to a scratch copy (Kotlin repo / `ios/KeyCatchCore`) |
| `detune_fix.patch` | the same change as a patch against 9ad92ca |

## Reproducing

```
S=<scratch>; V=$S/skey/venv/bin/python; K=$S/keyeval; T=tools/keyeval/detune
# a scratch copy of the repo at 9ad92ca (git archive), then:
$V -I $T/scratch_trace.py $S/repo            # tracer only; add apply_fix.py for the fix
$V -I $T/dt_truth.py rs200_melody_nlt.zip rs200_harmony_clt.zip $S/rs200_truth.json
$V -I $T/dt_run.py --items $K/work/voice_items.json --datasets rs200 gtsinger --detunes 0 10 20 30 39 45 50 -39 \
   --repo $S/repo --out $S/out --jobs 4 --write-items $S/items.json
$V -I $T/dt_run.py --items $K/work/voice_items.json --datasets csd singing --detunes 0 --repo $S/repo --out $S/out
$V -I $T/dt_analyse.py --items $S/items.json --out $S/out --truth $S/rs200_truth.json --by-singer
$V -I $T/dt_compare.py --items $S/items.json --skey-work $S/skey/work --by-singer --run current=$S/out/base --run fix=$S/out_fix/base
```

A first pass of CREPE costs about 10 s of CPU per minute of audio. Replays cost about 1 s per clip.
