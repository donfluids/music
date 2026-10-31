# KeyCatch

The app was called LiveKey during development. The Kotlin package (`com.donfluids.music`)
and some code names still carry the old name; the app ID is `com.donfluids.keycatch`.

An Android app for the keyboard player who has to join a song that has already started.
Point the phone at the singing and it reports:

- **Key** – tonic and mode (for example *G major*), with the relative key, the scale notes,
  the seven diatonic chords with roman numerals, and how sharp or flat the singers are of
  A = 440 Hz. That last number matters: an impromptu song often lands between two keys.
- **Chords in this song** – the triad sounding now, the sequence of chords heard since the
  song began with roman numerals, and a second key suggestion made from the whole chord
  sequence: which key the chords function in (I, IV, V, vi), how many V–I cadences were
  heard, and how the song opened and closed. This is the one to trust once a verse has gone
  by; the Key card above it reacts faster but only looks at the last eight seconds.
- **Tempo** – beats per minute with a pulsing beat indicator and a 1‑2‑3‑4 beat counter
  that highlights the downbeat.
- **Time signature** – 2/4, 3/4, 4/4, 5/4, 6/8, 7/8, 9/8 or 12/8, with the runner‑up
  candidates shown so you can see how sure the guess is.

Everything runs on the phone; no audio leaves the device.

The mathematics behind every stage, with equations and the constants the code uses, is in
[docs/math/KeyCatch-math.pdf](docs/math/KeyCatch-math.pdf) (source and build steps in
`docs/math`).

## Using it

1. Tap **Listen** and grant the microphone permission.
2. The top row shows the key to play in and the chord sounding now; the row under it shows
   the tempo with beat dots and the time signature. When the app is not sure yet, the line
   under the key says so ("a guess"); otherwise it just names the mode. The key usually settles within
   two or three seconds, the tempo within about four, and the time signature needs two to four
   bars. The chord is confirmed with a little look-ahead, so it lands about a second late (one to two beats),
   and about a second more with Basic Pitch, the default note listener for Band and Guitar.
   Melodies (Voices) are followed with CREPE by default. In Settings these are the "Accurate"
   choices; "Standard" switches back to the older method, which asks less of the phone.
3. **Hold** freezes the key so nothing changes under your hands; the chord and beat keep
   running. Tap again to release.
4. **Transpose**, beside the chords of the key, shifts every displayed key and chord to what you should play, for example
   when the singers are in Db and you would rather play in C with the keyboard transposed
   up one. **Numbers** shows Nashville numbers (1, 4, 5, 6m) instead of letters.
5. Tap the BPM to hear the tempo as double or half when the tracker has picked the wrong
   pulse.
6. Under the values: a note when the notes and the chords disagree or the singers are
   between two keys, the seven chords of the key with the one sounding now highlighted, and
   the recent chords.
7. **Details** at the bottom shows the tuning, why the app chose the key, the notes heard,
   and whether the phone is keeping up with the learned models. A long press on Details also
   shows their raw cost as a share of real time, for testing on a new phone.
8. Tap **New song** when the next song starts so the previous one stops influencing the
   result. **Stop** keeps the last result on screen until you listen again.

Settings has the theme: follow the system, or always light or dark.
The screen stays on while listening, and the microphone is released whenever the app leaves
the foreground.

### Presets

Pick the preset on the start screen, or later from the dropdown in the top bar, to match what the phone is hearing. The pipeline is the same; the
presets change which frequencies count and how patient the estimates are.

| Preset | Use when | What changes |
| --- | --- | --- |
| **Band** | Drums, guitar, keys and voices, e.g. a monitor mix | Onsets below 250 Hz (kick, bass) get extra weight so the drums set the beat. |
| **Guitar** | Guitar-led, no drums | Softer log compression keeps strum accents; the key finder ignores string overtones above 800 Hz. |
| **Voices** | Mostly singing | Only 150 Hz to 5 kHz counts as an onset (syllables), the key memory is longer, and the meter waits for more beats before deciding. |

The choice is remembered between launches. Switching presets while listening starts the
analysis over.

### Recording clips for tuning

The ⋮ menu records clips so the presets can be tuned to your room.

1. While listening, choose **Record clip for tuning**. The first time, a notice explains
   what a clip stores; clips record everyone within earshot, so let the people singing know.
   A coral REC chip shows the elapsed seconds; tap it or choose Stop clip to end early,
   otherwise it stops at 60 seconds.
2. A dialog asks what the song really was: key, tempo, time signature and any notes.
   Fill in what you know; the rest can stay blank.
3. **Share clips** sends every clip as one zip (each clip is a WAV file plus a JSON sidecar
   with the preset, the app's decisions over time, the chord sequence, and your labels).

Clips are kept in the app's private storage, where other apps cannot read them, until you
choose **Delete clips**. Nothing is uploaded unless you share it. Sidecars record the Android
version but not the phone model.

To replay clips through the analyser on a computer, unzip them and run:

```
./gradlew :app:testDebugUnitTest --tests '*ClipReplayTest*' -Pclips.dir=/path/to/clips -i --rerun
```

It prints, for every clip and every preset, what the app decided next to your labels. The `--rerun` flag matters: without it Gradle treats a second run on the same folder as already done and prints nothing.

## How it works

All signal processing is plain Kotlin under `app/src/main/kotlin/com/donfluids/music/dsp`
and has no Android dependencies, so it is covered by JVM unit tests.

| Stage | Method |
| --- | --- |
| Chroma | 8192‑point FFT every 46 ms; spectral peaks are refined by parabolic interpolation and mapped to pitch classes. Only peaks count, which keeps neighbouring bass semitones apart. |
| Chroma | Two spectra: a 16384‑point window for 55–260 Hz, where semitones are only a few hertz apart, and an 8192‑point window above that. Spectral peaks are refined by parabolic interpolation and mapped to pitch classes. |
| Chords | Chroma frames are averaged per half beat once the tempo has locked (every 185 ms before that) and compared with 84 templates: major, minor, 7, maj7, m7, sus4 and sus2 on every root. A hidden Markov model decoded by fixed‑lag Viterbi (four observations of delay) chooses the sequence, so a chord has to persist to be announced and one‑off misreads disappear. |
| Song key | Every key scores each chord by its function (tonic 1.0, IV and V 0.8, vi 0.6, ii 0.5, iii 0.4, bVII 0.25, foreign chords negative; a dominant seventh on V counts extra, suspended chords count less) weighted by how long it sounded, plus bonuses for V–I cadences and for opening or closing on the tonic. |
| Key | Krumhansl–Schmuckler correlation of the exponentially decayed chroma (8 s memory) with the 24 major/minor profiles, plus a short hysteresis so the display does not flicker. |
| Tuning | Weighted circular mean of each peak's distance from the equal‑tempered grid. |
| Onsets | Log‑compressed spectral flux, 2048‑point FFT, 512‑sample hop. |
| Tempo | Autocorrelation of the detrended onset curve over 40–220 BPM, weighted by a log‑normal prior centred on 100 BPM, with the phase chosen to line beats up with the strongest onsets. |
| Meter | Onset strength sampled on each beat, folded into candidate bars of 2–7 beats at every downbeat offset and correlated with accent templates (4/4, 3/4, 6/8, 2+2+3 for 7/8, …). A separate check of energy halfway versus a third of the way between beats tells simple from compound meter. |

Limits worth knowing: a congregation without a rhythm section gives weak onsets, so the
tempo and meter are less certain than the key. Modal songs (Dorian, Mixolydian) are reported
as the nearest major or minor key. The key finder assumes twelve‑tone equal temperament.

## Building

Requires JDK 17 or newer and the Android SDK (compile SDK 36). Android Studio opens the
project directly; from a terminal:

```
./gradlew :app:assembleRelease      # signed APK at app/build/outputs/apk/release/ (needs keystore.properties)
./gradlew :app:testDebugUnitTest    # unit tests on the JVM
```

Install release builds on phones. A debug build (`assembleDebug`) is debuggable, uses the
application id `com.donfluids.music.debug`, and lets anyone with a USB cable read its private
files, which defeats the clip privacy described above. Release builds are signed with the key
in `keystore.properties`, which is ignored by git; without that file the release APK is unsigned.

Minimum Android version is 8.0 (API 26).
