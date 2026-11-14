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
* **Proposal: v3** (`detune_fix_v3.patch`, against 9cccb20). It is the guarded patch plus one
  check: the grid adopts the level only when the level sits 20-40 c from concert pitch. This
  removes the guarded patch's in-tune regression (see "The in-tune case (v3)"), and RS200 +39
  stays at 53.4.
* **Earlier proposal: the guarded patch** (`detune_fix_guarded.patch`, against 6e4dad9). The grid
  takes the key level's steady offset only when that offset is within 40 c of concert pitch, and
  the sharp-singer test is added. Results with the guard:
  * RS200 +39: 49.5 to 53.4.
  * GTSinger +39: 41.1 to 43.0.
  * In-tune sets: within 0.5 points.
  * Decided-wrong readings over all sets: 815 to 782.
  * On the hymn at +39 and +45 c: no new decided-wrong case.

  Details are in "Guard near the boundary" below.

## Before / after, first patch (exact / relative %)

This section is the first measurement, of `detune_fix.patch` with no boundary guard. The next
section re-measures the patch with the guard, which is now the proposal.


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

## Guard near the boundary (re-measured; the proposal)

On the app's own hymn (SingerRobustnessTest's tune, sung 45 c sharp), the first patch made the
app sure of D flat. The key level read just past 50 and `adopt()` pulled the grid across the
boundary. The guard stops the grid from adopting a level more than 40 c from concert pitch
(`abs(l.steadyCents) <= 40.0`). The singer model can still cross on its own evidence.

**Patch (current = 6e4dad9).** `detune_fix_guarded.patch` (Kotlin and Swift, code and test,
against 6e4dad9; the Voices code is unchanged since 9ad92ca). `apply_fix.py` and
`apply_fix_swift.py` apply the same edits to a scratch copy.

**Decided-wrong** counts readings where the mode is decided and the shown key is wrong, or the
pair is decided and the pair is wrong. The guard 40 and guard 44 columns were measured with a
scratch build that read the guard from a property. The literal patch reproduces the guard-40
traces byte for byte (12 of 12 clips checked).

Each cell is exact % / relative % / decided-wrong readings.

| set | clips | readings | current | patch, no guard | **guard 40** | guard 44 |
|---|---|---|---|---|---|---|
| RS200 in tune | 104 | 1316 | 70.6 / 8.4 / 58 | 70.2 / 8.1 / 65 | 70.2 / 8.1 / 65 | 70.2 / 8.1 / 65 |
| RS200 +10 | 104 | 1304 | 64.7 / 9.9 / 61 | 64.8 / 10.0 / 60 | 64.9 / 10.0 / 60 | 64.9 / 10.0 / 60 |
| RS200 +20 | 104 | 1302 | 62.6 / 8.0 / 52 | 63.7 / 7.6 / 53 | 63.7 / 7.7 / 53 | 63.7 / 7.7 / 53 |
| RS200 +30 | 104 | 1300 | 60.8 / 7.2 / 51 | 62.2 / 7.4 / 48 | 62.4 / 7.1 / 42 | 62.8 / 7.4 / 42 |
| RS200 +39 | 104 | 1296 | 49.5 / 8.3 / 41 | 52.7 / 7.9 / 42 | 53.4 / 8.1 / 41 | 53.9 / 8.0 / 35 |
| RS200 +45 | 104 | 1294 | 37.6 / 9.1 / 76 | 39.0 / 8.7 / 75 | 39.1 / 9.0 / 77 | 38.5 / 8.9 / 74 |
| RS200 +50 | 104 | 1292 | 28.2 / 3.3 / 108 | 31.2 / 2.9 / 110 | 28.7 / 3.6 / 111 | 29.6 / 3.4 / 111 |
| RS200 -39 | 104 | 1382 | 57.4 / 7.9 / 51 | 58.2 / 8.7 / 48 | 57.9 / 8.8 / 48 | 58.5 / 8.8 / 48 |
| GTSinger in tune | 14 | 321 | 53.0 / 15.9 / 21 | 52.6 / 15.9 / 16 | 52.6 / 15.9 / 16 | 52.6 / 15.9 / 16 |
| GTSinger +10 | 14 | 318 | 54.7 / 16.0 / 20 | 54.7 / 16.7 / 12 | 54.7 / 16.7 / 12 | 54.7 / 16.7 / 12 |
| GTSinger +20 | 14 | 315 | 53.0 / 17.1 / 35 | 53.3 / 17.1 / 30 | 54.0 / 17.1 / 30 | 54.0 / 17.1 / 30 |
| GTSinger +30 | 14 | 314 | 50.6 / 16.6 / 54 | 53.8 / 15.9 / 50 | 53.8 / 15.3 / 50 | 53.5 / 15.6 / 50 |
| GTSinger +39 | 14 | 314 | 41.1 / 15.6 / 30 | 45.2 / 14.6 / 27 | 43.0 / 14.6 / 29 | 43.6 / 14.6 / 28 |
| GTSinger +45 | 14 | 311 | 34.7 / 16.7 / 42 | 39.2 / 16.7 / 36 | 35.0 / 16.7 / 43 | 37.9 / 16.7 / 43 |
| GTSinger +50 | 14 | 310 | 27.7 / 16.8 / 46 | 29.4 / 17.1 / 42 | 28.7 / 17.1 / 45 | 29.4 / 17.1 / 42 |
| GTSinger -39 | 14 | 330 | 38.2 / 2.7 / 53 | 39.1 / 2.7 / 46 | 38.5 / 2.7 / 45 | 38.8 / 2.7 / 47 |
| CSD English | 84 | 1592 | 86.9 / 1.8 / 9 | 86.4 / 1.8 / 8 | 86.4 / 1.8 / 8 | 86.4 / 1.8 / 8 |
| CSD English +39 | 84 | 1562 | 85.7 / 1.4 / 7 | 86.0 / 1.3 / 7 | 86.0 / 1.3 / 7 | 86.0 / 1.3 / 7 |
| app test tunes | 45 | 222 | 81.1 / 0.0 / 0 | 81.1 / 0.0 / 0 | 81.1 / 0.0 / 0 | 81.1 / 0.0 / 0 |
| **decided-wrong, all sets** | | | 815 | 775 | 782 | 771 |
| decided-wrong without the +50 rows | | | 661 | 623 | 626 | 618 |

The RS200 rows split by singer are in `dt_compare.py --by-singer`. For the average singer at +39,
current, no guard, guard 40 and guard 44 score 32.3 / 38.4 / 39.7 / 40.6 exact, with 14 / 16 / 15 / 9
decided-wrong readings.

At +50 the "true" key is a coin toss, so its decided-wrong counts mean little.

Where the patch adds decided-wrong readings:
* **RS200 in tune, 58 to 65, with or without the guard.** All of it comes from two average-singer
  clips:
  * bizarre_love_triangle: the grid moves to +12 c and G minor is decided over B flat, in 8 readings instead of 1.
  * brown-eyed_girl: one reading has B minor decided at a grid of +21 c.

  These are mode and pair decisions changed by a small grid move on an in-tune singer, not
  boundary crossings.
* **Guard 40 at +45 and +50.** One to three readings more than current: GTSinger +45 is 43 against
  42, RS200 +45 is 77 against 76, RS200 +50 is 111 against 108.

Everywhere else the patch has the same or fewer, and in total it has fewer than current (782
against 815).

**The app's hymn, sung sharp.** Each cell is right out of 5 seeds / decided-wrong. Tune and
generator are SingerRobustnessTest's, with 80 ms glides.

| offset, scatter | current | no guard | guard 40 | guard 44 |
|---|---|---|---|---|
| +30 c, 10-30 c (5 cases) | 5 / 0 each | 5 / 0 each | 5 / 0 each | 5 / 0 each |
| +39 c, 10-25 c (4 cases) | 5 / 0 each | 5 / 0 each | 5 / 0 each | 5 / 0 each |
| +39 c, 30 c | 3 / 0 | 5 / 0 | 4 / 0 | 4 / 0 |
| +45 c, 10 c | 5 / 0 | 5 / 0 | 5 / 0 | 5 / 0 |
| +45 c, 15 c | 4 / 1 | 4 / 1 | 4 / 1 | 4 / 1 |
| +45 c, 20 c | 5 / 0 | 4 / **1** | 5 / 0 | 4 / 0 |
| +45 c, 25 c | 5 / 0 | 3 / **2** | 5 / 0 | 5 / 0 |
| +45 c, 30 c | 3 / 0 | 4 / **1** | 3 / 0 | 4 / 0 |

The one decided-wrong case at +45 c / 15 c is already there in the current code. On seed 2 the
singer model's own grid reads 54 c (tuning -46) and D flat is decided.

**New test.** `SingerRobustnessTest.aSingerSteadilySharpIsNeverSureOfTheKeyAbove` (Kotlin) and
`testASingerSteadilySharpIsNeverSureOfTheKeyAbove` (Swift) sing the hymn at +39 and +45 c with
10/15/20/25/30 c of scatter. Each case asserts:
* `neverSureAndWrong`. For +45 c / 15 c, at most the one decided-wrong seed it has today.
* At least 3 of 5 seeds show C major.

Results:
* Kotlin: it passes on current code, with guard 40 and with guard 44. It fails with the unguarded patch.
* Swift: it passes with guard 40. It was not run against the unguarded Swift port.

**Unit tests with guard 40:**
* Kotlin, full suite (`./gradlew --offline :analysis:testDebugUnitTest`): 155 tests, 0 failures,
  3 skipped.
* Swift voice suites (SingerModel, SingerRobustness, WanderingSingers, KeyLevelTracker,
  MelodyKey, RelativeKey) in a Linux scratch build: 38 tests, 0 failures. That is the 37 from
  before plus the new one. The new test is slow in a Swift debug build: SingerRobustnessTests went
  from about 580 s to 1175 s.

## The in-tune case (v3)

**What happened.** With every earlier patch variant, RS200 in tune went from 58 to 65
decided-wrong readings. All seven extra readings came from two average-singer clips.

* **Why the grid moved.** In both clips the key level read the in-tune singer as sharp, at
  `steadyCents` +10.5 c (bizarre_love_triangle, at 13 s) and +21.0 c (brown-eyed_girl, at 15 s).
  Both levels passed the held-level gates: spread 22-24 c (25 c or less), range 0-20 c (40 c or
  less), now within 0-20 c of the centre. Both sat at least 10 c from the singer grid, which was at
  0 to 2.5 c. So `adopt()` moved the grid to +12 c and +21 c. Across the in-tune RS200 average
  clips, the level's centre falls in the 0 c lattice bin 35 % of the time, the 10 c bin 31 % and
  the 20 c bin 8 %: for scattered singers it leans sharp by about 5-10 c.
* **Why the mode or pair decision changed.** It is the move itself, not the spread term. A
  variant with `keySpreadCents` ignoring `heldLevel` gave identical readings on both clips. In
  both clips the current code already leans to the wrong key: bizarre_love_triangle shows G minor
  (the song is B flat major) from 16 s on, and brown-eyed_girl shows B minor (the song is G) early
  on. The move changed the evidence a little:
  * In bizarre_love_triangle, the grid at +12 c tightened the singer model's spread (decision
    spread 25 c instead of 30 c), so less evidence was needed. The mode score also got stronger
    (-2.3 instead of -1.9). The wrong G minor is then decided at 8 of the live readings instead of 1.
  * In brown-eyed_girl, at 18 s the B minor decision is kept one reading longer.

  The move doesn't cross the key boundary. It makes the app more sure of a lean that was already
  wrong.

**Checks considered.**

* **The level must sit at least N c from concert pitch.** This is the simplest check, and it
  targets the cause: offsets smaller than the level's own lean are not evidence. Measured at
  N = 15, 20 and 25 (table below).
* **Ignore `heldLevel` in `keySpreadCents`.** No effect on the two clips, so not the cause.
* **Adopt only when the singer model is unsure.** Its consistency was 0.0-0.1 in both clips when
  the grid moved (as unsure as for a scattered singer off pitch), so it would not stop them.
  Not measured over the sets.
* **A minimum time held.** In brown-eyed_girl the level held about 21 c for the first 10 s
  (18.6-21 c), so a hold time would not stop it either. Not measured.

**Chosen: N = 20 c.** It is the smallest N that brings RS200 in tune back to the current count
(58). N = 25 gives the same, and N = 15 leaves 59. RS200 +39 is unchanged from guard 40.

Each cell is exact % / relative % / decided-wrong readings.

| set | current | guard 40 | + min 15 | **+ min 20 (v3)** | + min 25 |
|---|---|---|---|---|---|
| RS200 in tune | 70.6 / 8.4 / 58 | 70.2 / 8.1 / 65 | 70.4 / 8.4 / 59 | 70.5 / 8.4 / 58 | 70.5 / 8.4 / 58 |
| (average singer, in tune) | 70.2 / 7.0 / 12 | 69.5 / 6.4 / 20 | 69.9 / 7.1 / 14 | 70.1 / 7.0 / 13 | 70.1 / 7.0 / 13 |
| RS200 +10 | 64.7 / 9.9 / 61 | 64.9 / 10.0 / 60 | 64.5 / 9.8 / 61 | 64.5 / 9.8 / 60 | 64.5 / 9.8 / 61 |
| RS200 +20 | 62.6 / 8.0 / 52 | 63.7 / 7.7 / 53 | 63.7 / 7.7 / 54 | 63.6 / 7.7 / 53 | 63.6 / 7.7 / 53 |
| RS200 +30 | 60.8 / 7.2 / 51 | 62.4 / 7.1 / 42 | 62.4 / 7.1 / 42 | 62.3 / 7.2 / 42 | 62.2 / 7.2 / 42 |
| RS200 +39 | 49.5 / 8.3 / 41 | 53.4 / 8.1 / 41 | 53.4 / 8.1 / 41 | 53.4 / 7.9 / 41 | 53.2 / 7.9 / 41 |
| RS200 +45 | 37.6 / 9.1 / 76 | 39.1 / 9.0 / 77 | 39.1 / 9.0 / 77 | 39.1 / 9.0 / 77 | 39.1 / 9.0 / 76 |
| RS200 +50 | 28.2 / 3.3 / 108 | 28.7 / 3.6 / 111 | 28.7 / 3.6 / 111 | 28.7 / 3.6 / 111 | 28.7 / 3.6 / 111 |
| RS200 -39 | 57.4 / 7.9 / 51 | 57.9 / 8.8 / 48 | 57.9 / 8.8 / 48 | 57.9 / 8.8 / 48 | 58.0 / 8.6 / 48 |
| GTSinger in tune | 53.0 / 15.9 / 21 | 52.6 / 15.9 / 16 | 52.0 / 15.9 / 21 | 52.0 / 15.9 / 21 | 52.0 / 15.9 / 21 |
| GTSinger +10 | 54.7 / 16.0 / 20 | 54.7 / 16.7 / 12 | 54.7 / 16.0 / 21 | 54.7 / 16.0 / 21 | 54.7 / 16.0 / 20 |
| GTSinger +20 | 53.0 / 17.1 / 35 | 54.0 / 17.1 / 30 | 54.0 / 17.1 / 24 | 53.0 / 17.1 / 23 | 53.0 / 17.1 / 30 |
| GTSinger +30 | 50.6 / 16.6 / 54 | 53.8 / 15.3 / 50 | 53.8 / 15.3 / 50 | 53.8 / 15.3 / 50 | 53.5 / 15.6 / 45 |
| GTSinger +39 | 41.1 / 15.6 / 30 | 43.0 / 14.6 / 29 | 43.0 / 14.6 / 29 | 43.3 / 14.3 / 29 | 43.3 / 14.3 / 29 |
| GTSinger +45 | 34.7 / 16.7 / 42 | 35.0 / 16.7 / 43 | 35.0 / 16.7 / 43 | 35.0 / 16.7 / 43 | 35.0 / 16.7 / 43 |
| GTSinger +50 | 27.7 / 16.8 / 46 | 28.7 / 17.1 / 45 | 28.7 / 17.1 / 45 | 28.7 / 17.1 / 45 | 28.7 / 17.1 / 45 |
| GTSinger -39 | 38.2 / 2.7 / 53 | 38.5 / 2.7 / 45 | 38.5 / 2.7 / 45 | 38.5 / 2.7 / 45 | 38.5 / 2.7 / 45 |
| CSD English | 86.9 / 1.8 / 9 | 86.4 / 1.8 / 8 | 86.4 / 1.8 / 8 | 86.4 / 1.8 / 8 | 86.4 / 1.8 / 8 |
| CSD English +39 | 85.7 / 1.4 / 7 | 86.0 / 1.3 / 7 | 86.0 / 1.3 / 7 | 86.0 / 1.3 / 7 | 86.0 / 1.3 / 7 |
| app test tunes | 81.1 / 0.0 / 0 | 81.1 / 0.0 / 0 | 81.1 / 0.0 / 0 | 81.1 / 0.0 / 0 | 81.1 / 0.0 / 0 |
| **decided-wrong, all sets** | 815 | 782 | 786 | 782 | 783 |
| without the +50 rows | 661 | 626 | 630 | 626 | 627 |

**What v3 leaves, compared with current.**
* **Remaining extra readings.** Per clip, the in-tune sets differ from current only where
  `heldLevel` touches decision caution:
  * one reading in brown-eyed_girl, whose level reads +21 c, just past the minimum;
  * one in GTSinger manon +10;
  * two fewer, in stairway_to_heaven and csd_en048b, where no grid moved.
* **Given back.** The guard-40 patch also lowered GTSinger's in-tune and +10 decided-wrong counts
  (21 to 16 and 20 to 12). Those cuts came from grid moves under 20 c and are given back.
* **Kept.** The sharp-singer gains: RS200 +30 and +39, GTSinger +39, and RS200 and GTSinger -39.

**The hymn probe** (`SingerRobustnessTest`'s tune at +30, +39 and +45 c, scatter 10-30 c, 5 seeds):
identical for guard 40 and v3. Every case is right on at least as many seeds as current. The only
decided-wrong case is the one current also has, +45 c with 15 c scatter (1 seed).

`aSingerSteadilySharpIsNeverSureOfTheKeyAbove` passes with v3.

**Unit tests with v3:**
* Kotlin, full suite: 155 tests, 0 failures, 3 skipped.
* Swift voice suites (SingerModel, SingerRobustness, WanderingSingers, KeyLevelTracker,
  MelodyKey, RelativeKey) in a Linux scratch build: 38 tests, 0 failures.

**Equivalence check.** The scores were measured with a scratch build that reads the minimum from
a property. The literal v3 code reproduces those readings on 15 of 15 clips checked (5 clips at
0, +20 and +39 c).

## The change (first patch; the guarded patch adds the 40 c guard and the test)

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

**Risk (first patch).** The 30 and 35 c gates, and the version without the steadiness gate, all fail
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
| `dt_compare.py` | exact / relative % and decided-wrong readings for several runs side by side (the tables above) |
| `dt_estimators.py` | offline comparison of tuning estimators on the segmented notes |
| `apply_fix.py`, `apply_fix_swift.py` | the proposed change, applied to a scratch copy (Kotlin repo / `ios/KeyCatchCore`) |
| `detune_fix.patch` | the first change (no boundary guard) as a patch against 9ad92ca |
| `detune_fix_v3.patch` | the proposal: the guarded patch with the 20 c minimum, Kotlin and Swift, code and test, against 9cccb20 |
| `detune_fix_guarded.patch` | the earlier proposal: the change with the 40 c guard, plus the sharp-singer test, Kotlin and Swift, against 6e4dad9 |
| `test_edits.py` | the test edits, used by `apply_fix.py` / `apply_fix_swift.py` |

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
