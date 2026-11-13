#!/bin/sh
# Builds bt.jar: the Beat This! prototype, the driver, and the app's BasicPitch/CrepePitch/Fft read from the repo.
set -e
H=$(cd "$(dirname "$0")" && pwd)
REPO=${REPO:-/home/user/music}
D=$REPO/analysis/src/main/kotlin/com/donfluids/music/dsp
"$H/../dl/kotlinc/kotlinc/bin/kotlinc" "$H/src/BeatThis.kt" "$H/src/Bench.kt" "$D/BasicPitch.kt" "$D/CrepePitch.kt" "$D/Fft.kt" -include-runtime -d "$H/bt.jar" 2>&1 | grep -v "^Picked up" || true
