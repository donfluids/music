#!/bin/bash
# Downloads the GiantSteps Key audio (604 Beatport previews) from the JKU backup named in the
# dataset's own audio_dl.sh, checking each file against the dataset's md5 list.
# usage: fetch_giantsteps.sh <giantsteps-key-dataset checkout> <out dir>
set -u
DS=$1; OUT=$2; mkdir -p "$OUT"
ok=0; bad=0
for f in "$DS"/md5/*.md5; do
  b=$(basename "$f" .md5); mp3="$OUT/$b.mp3"; want=$(cat "$f" | tr -d ' \n')
  if [ -f "$mp3" ] && [ "$(md5sum "$mp3" | cut -d' ' -f1)" = "$want" ]; then ok=$((ok+1)); continue; fi
  curl -sS -f -o "$mp3" "https://www.cp.jku.at/datasets/giantsteps/backup/$b.mp3" || curl -sS -f -o "$mp3" "https://geo-samples.beatport.com/lofi/$b.mp3"
  if [ -f "$mp3" ] && [ "$(md5sum "$mp3" | cut -d' ' -f1)" = "$want" ]; then ok=$((ok+1)); else bad=$((bad+1)); echo "failed $b"; rm -f "$mp3"; fi
done
echo "ok=$ok failed=$bad"
