# Mini Piano

A small touch piano that lives alongside LiveKey in this repository as the `:piano` module
and installs as its own app (`com.donfluids.piano`).

- One octave in portrait, two in landscape, starting at C4; − / + shift the octave.
- Multi-touch: play chords, slide between keys. Pressing lower on a key plays louder.
- **Sustain** keeps lifted keys ringing; **Names** toggles note names on the white keys. A
  small dot marks middle C.
- **Piano**, **E-piano** and **Organ** tones, all synthesised on the fly (six harmonics; on the
  piano tones the upper partials fade faster, as on a struck string; the organ holds steady),
  so the APK carries no sample files.
- **Scale**: pick a key and the out-of-scale white keys dim, in-scale black keys get a teal
  edge, and the tonic gets a dot. The seven chords of the key appear as buttons; hold one to
  sound it and light its notes on the keyboard.
- **Metronome**: start/stop, tempo, and 2/4, 3/4, 4/4 or 6/8, with an accented downbeat and
  a dot that flashes on the metronome's own clock.
- Keys sink when pressed. Octave, sustain, names, tone, scale and tempo are remembered.
- Audio stops when the app leaves the foreground.

```
./gradlew :piano:testDebugUnitTest    # synth and keyboard-layout tests on the JVM
./gradlew :piano:assembleRelease      # signed with the same keystore.properties as LiveKey
```
