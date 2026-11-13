# Core ML models for the iPhone app

- `BasicPitchNotes.mlpackage` is Spotify's own Core ML export of Basic Pitch
  (`basic_pitch/saved_models/icassp_2022/nmp.mlpackage` in github.com/spotify/basic-pitch, Apache 2.0),
  copied unchanged.
- `CrepeTiny.mlpackage` is built from the same CREPE-tiny weights the Android app ships
  (`app/src/main/assets/crepe-tiny.bin`):

```
python3 -m venv venv && venv/bin/pip install coremltools==9.0 numpy
venv/bin/python crepe_np.py ../../app/src/main/assets/crepe-tiny.bin ../../app/src/test/resources/crepe-reference.json   # NumPy check: max diff ~2e-7
venv/bin/python crepe_coreml.py ../../app/src/main/assets/crepe-tiny.bin ../../ios/KeyCatchCore/Sources/KeyCatchCore/Resources/CrepeTiny.mlpackage .
```

Both run on Linux. The models themselves can only be run on Apple platforms; the Swift
package's `CoreMLModelsTests` check them against the reference activations on macOS.
