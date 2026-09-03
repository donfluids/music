# LiveKey

An Android app for the keyboard player who has to join a song that has already started.
Point the phone at the singing and it reports:

- **Key** – tonic and mode (for example *G major*), with the relative key, the scale notes,
  the seven diatonic chords with roman numerals, and how sharp or flat the singers are of
  A = 440 Hz. That last number matters: an impromptu song often lands between two keys.
- **Tempo** – beats per minute with a pulsing beat indicator and a 1‑2‑3‑4 beat counter
  that highlights the downbeat.
- **Time signature** – 2/4, 3/4, 4/4, 5/4, 6/8, 7/8, 9/8 or 12/8, with the runner‑up
  candidates shown so you can see how sure the guess is.

Everything runs on the phone; no audio leaves the device.

## Using it

1. Tap **Start listening** and grant the microphone permission.
2. Let the song run for a few bars. The key usually settles within two or three seconds,
   the tempo within about four, and the time signature needs two to four bars.
3. Tap **New song** when the next song starts so the previous one stops influencing the result.
4. If the tempo shows the wrong pulse (double or half of what you feel), tap
   **feel it ×2 / ÷2**. That only changes the displayed number and the pulse rate.

The screen stays on while listening.

### Presets

Pick the preset that matches what the phone is hearing. The pipeline is the same; the
presets change which frequencies count and how patient the estimates are.

| Preset | Use when | What changes |
| --- | --- | --- |
| **Band** | Drums, guitar, keys and voices, e.g. a monitor mix | Onsets below 250 Hz (kick, bass) get extra weight so the drums set the beat. |
| **Guitar** | Guitar-led, no drums | Softer log compression keeps strum accents; the key finder ignores string overtones above 800 Hz. |
| **Voices** | Mostly singing | Only 150 Hz to 5 kHz counts as an onset (syllables), the key memory is longer, and the meter waits for more beats before deciding. |

The choice is remembered between launches. Switching presets while listening starts the
analysis over.

### Recording clips for tuning

The bottom card records clips so the presets can be tuned to your room.

1. While listening, tap **Record clip**. It stops by itself after 60 seconds, or earlier
   when you tap **Stop clip**.
2. A dialog asks what the song really was: key, tempo, time signature and any notes.
   Fill in what you know; the rest can stay blank.
3. Tap **Share** to send every clip as one zip (each clip is a WAV file plus a JSON
   sidecar with the preset, the app's decisions over time, and your labels).

Clips stay on the phone under `Android/data/com.donfluids.music/files/clips` until you
tap **Delete all**. Nothing is uploaded unless you share it.

To replay clips through the analyser on a computer, unzip them and run:

```
./gradlew :app:testDebugUnitTest --tests '*ClipReplayTest*' -Dclips.dir=/path/to/clips -i
```

It prints, for every clip and every preset, what the app decided next to your labels.

## How it works

All signal processing is plain Kotlin under `app/src/main/kotlin/com/donfluids/music/dsp`
and has no Android dependencies, so it is covered by JVM unit tests.

| Stage | Method |
| --- | --- |
| Chroma | 8192‑point FFT every 46 ms; spectral peaks are refined by parabolic interpolation and mapped to pitch classes. Only peaks count, which keeps neighbouring bass semitones apart. |
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
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # DSP unit tests on the JVM
```

Minimum Android version is 8.0 (API 26).
