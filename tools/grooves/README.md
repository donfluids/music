# Drum grooves from the Groove MIDI Dataset

Mini Piano's band plays recorded drum loops for five styles. They are four-bar excerpts of
performances in the [Groove MIDI Dataset](https://magenta.tensorflow.org/datasets/groove) by
Google LLC (Magenta), licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The generated file is
`piano/src/main/kotlin/com/donfluids/piano/Grooves.kt`; see `piano/THIRD_PARTY_NOTICES.md`.

To regenerate (needs Python 3 and `pip install mido`):

```
curl -LO https://storage.googleapis.com/magentadata/datasets/groove/groove-v1.0.0-midionly.zip
python3 -c "import zipfile; zipfile.ZipFile('groove-v1.0.0-midionly.zip').extractall('gmd')"
python3 tools/grooves/pick.py gmd/groove tools/grooves picks.json   # candidates per style, with grids
python3 tools/grooves/gen.py gmd/groove tools/grooves piano/src/main/kotlin/com/donfluids/piano/Grooves.kt
```

`pick.py` scores every four-bar stretch of each candidate performance: a kick on each
downbeat, a backbeat for the 4/4 styles, steady cymbals for the straight ones, no toms or
mid-loop crashes, and how much the stretch resembles the four bars after it. The final choice
per style is set in `PICKS` in `gen.py`, after listening and looking at the grids.
