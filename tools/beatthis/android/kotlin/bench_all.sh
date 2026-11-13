#!/bin/sh
# Kotlin timings: one 412-frame window, median of 21 runs after 5 warm-ups, small0/final0, 1/2/4 threads,
# then the per-stage profile on one thread, then the yardstick (BasicPitch.notes, CrepePitch).
H=$(cd "$(dirname "$0")/.." && pwd)
J="java -jar $H/kotlin/bt.jar"
for m in small0 final0; do for th in 1 2 4; do
  $J bench $H/data/beatthis-$m.bin $H/data/rock_00000.in.f32 $th 21 5 2>&1 | grep -v "^Picked"
done; done
for m in small0 final0; do echo "profile $m, 1 thread:"; BT_PROFILE=1 $J bench $H/data/beatthis-$m.bin $H/data/rock_00000.in.f32 1 9 3 2>&1 | grep -v "^Picked"; done
$J yard /home/user/music/app/src/main/assets/basic-pitch.bin /home/user/music/app/src/main/assets/crepe-tiny.bin 50 2>&1 | grep -v "^Picked"
