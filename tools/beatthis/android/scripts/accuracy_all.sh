#!/bin/sh
# Accuracy of every variant against the official PyTorch FP32 model on all test windows
# (data/: GTZAN rock.00000 + GuitarSet solo; data2/: one GTZAN clip per other genre).
S=/tmp/claude-0/-home-user-music/5409049d-912e-5ba4-b258-003aa1be4770/scratchpad
H=$S/android-bt
SRC=$S/beatthis/dl/beat_this_src
cd "$H" || exit 1
for m in small0 final0; do
  for f in data/*.in.f32 data2/*.in.f32; do
    java -jar kotlin/bt.jar check data/beatthis-$m.bin "$f" "${f%.in.f32}.$m.kotlin.f32" 1 2>&1 | grep -v "^Picked"
  done
  for v in fp32 fp16 int8dyn; do
    $H/venv-lrt/bin/python -I scripts/run_tflite.py logits tflite/beatthis-$m-$v.tflite data/*.in.f32 data2/*.in.f32 --suffix $m.tflite-$v
  done
  # GPU-friendly graphs: on XNNPACK, and on LiteRT's GPU accelerator over Mesa lavapipe (software Vulkan)
  for v in fp32 fp16; do
    $H/venv-lrt/bin/python -I scripts/run_tflite.py logits tflite/beatthis-$m-gpu-$v.tflite data/*.in.f32 data2/*.in.f32 --suffix $m.tflite-gpu-$v
    VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json $H/venv-lrt/bin/python -I scripts/gpu_try.py many tflite/beatthis-$m-gpu-$v.tflite $m.gpudelegate-$v data/*.in.f32 data2/*.in.f32
  done
done
$S/venv/bin/python -I scripts/summarise_accuracy.py --beat-this-src $SRC data data2
