# Third-party material in Mini Piano

## Groove MIDI Dataset

The band's drum grooves for Worship Slow, 8-Beat Rock, Power Rock, Pop 16-Beat and Gospel
Shuffle are excerpts from the Groove MIDI Dataset by Google LLC (Magenta),
https://magenta.tensorflow.org/datasets/groove, licensed under the Creative Commons
Attribution 4.0 International License, https://creativecommons.org/licenses/by/4.0/.

Changes made: four-bar excerpts of five performances (named in `Grooves.kt`); each loop's
first downbeat moved onto the beat; Roland TD-11 drum notes mapped to Mini Piano's synthesised
drum voices; velocities scaled to Mini Piano's drum levels. The sounds themselves are
Mini Piano's own; no audio from the dataset is included.

Gillick, Roberts, Engel, Eck, Bamman. "Learning to Groove with Inverse Sequence
Transformations." International Conference on Machine Learning (ICML), 2019.
