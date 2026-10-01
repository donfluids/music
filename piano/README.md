# Mini Piano

A small touch piano that lives alongside LiveKey in this repository as the `:piano` module
and installs as its own app (`com.donfluids.piano`).

- Opens in landscape (either way up) with two octaves starting at C4. A strip in the control
  row shows C1 to B7 with the visible window highlighted; drag it or tap to move the keyboard.
- Multi-touch: play chords, slide between keys. Pressing lower on a key plays louder. Keys
  sink with an eased travel and light up from the bottom edge in teal while held.
- **Piano**, **E-piano** and **Organ** tones, all synthesised on the fly (six harmonics; on the
  piano tones the upper partials fade faster, as on a struck string; the organ holds steady),
  so the APK carries no sample files.
- **Scale**: pick a key and the out-of-scale white keys dim, in-scale keys get a teal dot and
  the tonic a larger one. The seven chords of the key appear above the keyboard; hold one to
  sound it and light its notes.
- **Metronome** in one pill: tap the dot to start or stop, the number for a tempo dialog with
  tap tempo, the signature to cycle 2/4, 3/4, 4/4 and 6/8. The downbeat is accented and the
  dot breathes with the beat on the metronome's own clock.
- The gear opens settings: sustain, note names, room (a light reverb), tick (a haptic tap on
  each key) and volume. Everything is remembered between launches.
- Audio stops when the app leaves the foreground.

```
./gradlew :piano:testDebugUnitTest    # synth and keyboard-layout tests on the JVM
./gradlew :piano:assembleRelease      # signed with the same keystore.properties as LiveKey
```
