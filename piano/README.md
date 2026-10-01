# Mini Piano

A small touch piano that lives alongside LiveKey in this repository as the `:piano` module
and installs as its own app (`com.donfluids.piano`).

- One octave in portrait, two in landscape, starting at C4; − / + shift the octave.
- Multi-touch: play chords, slide between keys. Pressing lower on a key plays louder.
- **Sustain** keeps lifted keys ringing; **Names** toggles note names on the white keys.
- The sound is synthesised on the fly (six harmonics, upper ones fading faster, as on a
  struck string), so the APK carries no sample files. Audio stops when the app leaves the
  foreground.

```
./gradlew :piano:testDebugUnitTest    # synth and keyboard-layout tests on the JVM
./gradlew :piano:assembleRelease      # signed with the same keystore.properties as LiveKey
```
