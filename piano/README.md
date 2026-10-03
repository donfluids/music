# Mini Piano

A small touch piano that lives alongside LiveKey in this repository as the `:piano` module
and installs as its own app (`com.donfluids.piano`).

- Opens in landscape (either way up) showing two octaves of a continuous C1 to B7 keyboard.
  Drag the red felt bar above the keys to slide the keyboard up or down the range, or push
  two fingers sideways together across the keys; it snaps to a key edge when released. Two
  fingers that simply land play two notes. The
  strip in the control row shows where you are and can be dragged or tapped too.
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
- Keys glow while they sound, not just while a finger is on them, so sustained notes stay
  visible. Under sustain a lifted piano key rings for at most ten seconds, and a lifted organ
  key fades over a second or two instead of holding forever. Thirty-two voices; when they run
  out, the quietest fading note gives way with a short fade rather than a cut.
- **Band**: pick a style (Worship Slow, Worship 6/8, 8-Beat Rock, Power Rock, Pop 16-Beat, Gospel
  Shuffle, Waltz) and the keys below middle C become the chord zone, marked by a teal line. One key
  gives a major chord; add the black key just below it for minor or the white key just below
  for a seventh; or play the chord with three or more fingers. The band (synthesised drums,
  bass and electric piano) starts on the first chord, or from the play button in the pill,
  and follows the shared tempo. The keys above middle C play as usual over it, and the scale
  chord buttons set the band's chord too. Band volume is in settings.
- **Listen**: tap the ear and sing or play a few bars. The piano runs LiveKey's analyser on
  the microphone, reads the key, tempo and time signature, and as soon as it is sure it picks
  the style that fits (compound time to Worship 6/8, three to Waltz, a slow four to Worship
  Slow, medium to Pop, driving to 8-Beat Rock, fast to Power Rock), sets the tempo and key,
  and, with "Band starts by itself on the next downbeat" on (the default), enters on the
  singers' next downbeat in their time. With "Band follows the chords it hears" on (also the
  default) it keeps changing chord with the singing, ignoring any heard chord that does not
  belong to the key. Turn either off in settings to start by hand or hold the chord yourself.
  The band keeps its own tempo once it has entered; if the singers drift, stop and listen
  again. Once the band plays, the microphone hears the band too.
- The gear opens settings: sustain, note names, room (a light reverb), tick (a haptic tap on
  each key) and volume. Everything is remembered between launches.
- Audio stops when the app leaves the foreground.

```
./gradlew :piano:testDebugUnitTest    # synth and keyboard-layout tests on the JVM
./gradlew :piano:assembleRelease      # signed with the same keystore.properties as LiveKey
```
