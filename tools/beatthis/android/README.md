# Beat This! on Android: plain Kotlin vs LiteRT

Sections 1-7 are the measurements the choice was made from; section 8 is what the app ships and
how that file was checked. For sections 1-7 the repo's working tree was not touched (the app's
`BasicPitch.kt`, `CrepePitch.kt`, `Fft.kt` and `tools/beatthis/*.py` are only read / compiled
from their paths). Inputs: one window of 412 frames x 128 log-mel bands (8 s + 6 zero border
frames each side), as the app would run once a second. small0 = Band, final0 = Guitar.

**M** = measured on this machine. **E** = estimated (method and sources in section 4).

Machine (M): 4 vCPU KVM guest, "Intel Xeon Processor @ 2.10GHz", family 6 model 207 (Emerald
Rapids), AVX-512, no GPU. A dependent-add loop runs at 2.6-3.0 GHz (`scripts/clock.c`), i.e. it
turbos. OpenJDK 21.0.12 (HotSpot C2), Kotlin 2.3.21 (kotlinc), LiteRT 2.3.0 Python interpreter
(`ai-edge-litert`), litert-torch 0.10.0, torch 2.14.1 (conversion) / 2.7.1 (reference).
Timings are noisy (shared VM): repeated runs of the same configuration moved by up to ~15 %.

## 1. Server timings (M), one 412-frame window, median of 21 runs after 5 warm-ups

| route | model | 1 thread | 2 threads | 4 threads |
|---|---|---|---|---|
| plain Kotlin (JVM, HotSpot) | small0 | **428 ms** (407-472 over 4 runs) | 241 ms | 189 ms |
| plain Kotlin (JVM, HotSpot) | final0 | **1142 ms** (974-1154 over 3 runs) | 529-539 ms | 406-435 ms |
| plain Kotlin, C2 limited to 128-bit vectors (NEON width) | small0 / final0 | 507 / 1285 ms | | |
| plain Kotlin, C2 vectoriser off (`-XX:-UseSuperWord`) | small0 / final0 | 1570 / 3964 ms | | |
| plain Kotlin, first version (flat-offset loops, scalar exp) | small0 | 1918-2665 ms | | |
| LiteRT XNNPACK, GPU-friendly graph, FP32 | small0 | **122 ms** | | **32 ms** |
| LiteRT XNNPACK, GPU-friendly graph, FP16 weights | small0 | 118 ms | | 34 ms |
| LiteRT XNNPACK, GPU-friendly graph, FP32 | final0 | **251 ms** | | **82 ms** |
| LiteRT XNNPACK, GPU-friendly graph, FP16 weights | final0 | 248 ms | | 75 ms |
| LiteRT XNNPACK, bt_export graph, FP32 | small0 | 162 ms | 106 ms | 58 ms |
| LiteRT XNNPACK, bt_export graph, FP16 weights | small0 | 164 ms | 103 ms | 71 ms |
| LiteRT XNNPACK, bt_export graph, INT8 dynamic | small0 | 137 ms | 91 ms | 58 ms |
| LiteRT XNNPACK, bt_export graph, FP32 | final0 | 321 ms | 186 ms | 123 ms |
| LiteRT XNNPACK, bt_export graph, FP16 weights | final0 | 363 ms | 200 ms | 112 ms |
| LiteRT XNNPACK, bt_export graph, INT8 dynamic | final0 | 228 ms | 132 ms | 80 ms |
| LiteRT without XNNPACK (reference kernels), FP32 | small0 | 1006 ms | | 978 ms |
| PyTorch 2.7.1 (tools/beatthis/README.md section 4) | small0 / final0 | 176 / 305 ms | | 76 / 99 ms |

"GPU-friendly graph" = `scripts/bt_export_gpu.py` (section 3); it is also 25-45 % faster on the
CPU than the `bt_export.py` graph, because the 5-D reshapes/transposes are gone. FP16 weights are
dequantised to FP32 on this x86 CPU, so FP16 runs at FP32 speed here (on ARMv8.2 cores XNNPACK
can run FP16 arithmetic; not measurable here). LiteRT load + first run: 70-360 ms (once).
Arithmetic per window (LiteRT's count): small0 8.6 GFLOP, final0 24.4 GFLOP, so XNNPACK reaches
70-98 GFLOPS on one core here and the Kotlin code about 19-24 GFLOPS.

Kotlin per-stage profile, 1 thread (M): small0 matmuls 231 ms, attention core 161 ms, GELU 12,
RMSNorm 8, gates 8, stem 8; final0 matmuls 642 ms, attention 257 ms, gates 25, GELU 16.

Yardstick: the app's own Kotlin models on the same JVM (M):

| model | call | per call | per second of audio in the app |
|---|---|---|---|
| Basic Pitch (Band/Guitar, `HeardNotes`, every 0.5 s) | `notes(x, 114, 157)` as `HeardNotes` calls it | 73 ms | **146 ms/s** |
| Basic Pitch | `notes(x, 0, 172)`, whole 2 s window | 182-200 ms | |
| CREPE tiny (Voices, every 4 hops of 512 at 44.1 kHz = 21.5/s) | `process` on a voiced frame | 3.5 ms | **76 ms/s** |

So on the same core, Beat This! small0 in plain Kotlin costs about 3x what Basic Pitch costs
the app today, final0 about 7-8x; through LiteRT (1 thread) small0 is 0.8x and final0 1.7x.

## 2. Accuracy against the official PyTorch FP32 model (M)

11 windows (GTZAN rock.00000, one GTZAN clip of each other genre, one GuitarSet solo mic take;
8 s from second 5, 6 zero frames each side), 824 logits each. "Other beats" = the official
minimal postprocessing on the 400 inner frames picks different beat (or downbeat) times.

| model | variant | max logit diff | mean | sign changes | windows with other beats | other downbeats | beats ref -> variant |
|---|---|---|---|---|---|---|---|
| small0 | Kotlin (FP32) | 0.00012 | 6e-06 | 0 / 9064 | 0 / 11 | 0 / 11 | 171 -> 171 |
| small0 | LiteRT FP32 (bt_export graph) | 4.1e-05 | 3e-06 | 0 / 9064 | 0 / 11 | 0 / 11 | 171 -> 171 |
| small0 | LiteRT FP16 weights | 0.052 | 0.003 | 0 / 9064 | 0 / 11 | 0 / 11 | 171 -> 171 |
| small0 | LiteRT INT8 dynamic | 1.9 | 0.1 | 35 / 9064 | 1 / 11 | 2 / 11 | 171 -> 171 |
| small0 | LiteRT FP32, GPU-friendly graph | 0.00017 | 2e-05 | 0 / 9064 | 0 / 11 | 0 / 11 | 171 -> 171 |
| small0 | LiteRT FP16, GPU-friendly graph | 0.052 | 0.003 | 0 / 9064 | 0 / 11 | 0 / 11 | 171 -> 171 |
| small0 | GPU accelerator on lavapipe, FP32 file | 0.59 | 0.04 | 6 / 9064 | 1 / 11 | 1 / 11 | 171 -> 171 |
| small0 | GPU accelerator on lavapipe, FP16 file | 0.57 | 0.03 | 8 / 9064 | 3 / 11 | 1 / 11 | 171 -> 172 |
| final0 | Kotlin (FP32) | 5.3e-05 | 6e-06 | 0 / 9064 | 0 / 11 | 0 / 11 | 178 -> 178 |
| final0 | LiteRT FP32 (bt_export graph) | 5.3e-05 | 4e-06 | 0 / 9064 | 0 / 11 | 0 / 11 | 178 -> 178 |
| final0 | LiteRT FP16 weights | 0.025 | 0.002 | 1 / 9064 | 0 / 11 | 0 / 11 | 178 -> 178 |
| final0 | LiteRT INT8 dynamic | 1.9 | 0.1 | 29 / 9064 | 4 / 11 | 1 / 11 | 178 -> 178 |
| final0 | LiteRT FP32, GPU-friendly graph | 0.00056 | 0.0001 | 0 / 9064 | 0 / 11 | 0 / 11 | 178 -> 178 |
| final0 | LiteRT FP16, GPU-friendly graph | 0.025 | 0.002 | 1 / 9064 | 0 / 11 | 0 / 11 | 178 -> 178 |
| final0 | GPU accelerator on lavapipe, FP32 file | 0.25 | 0.04 | 8 / 9064 | 1 / 11 | 0 / 11 | 178 -> 178 |
| final0 | GPU accelerator on lavapipe, FP16 file | 0.35 | 0.04 | 10 / 9064 | 1 / 11 | 0 / 11 | 178 -> 178 |

The Kotlin and LiteRT FP32/FP16 CPU paths are exact for practical purposes. INT8 dynamic-range
moves logits by up to 1.9 and changes picks: not recommended. The GPU path (Mesa's software Vulkan; the
size of the errors suggests the accelerator computes at FP16 precision; a phone GPU may round differently) moves logits by up to
0.6 and shifts borderline peaks in 1-3 of 11 windows, which is looser than the iPhone's Core ML FP16
(0.06-0.13, picks identical, README section 7).

## 3. GPU delegate op check (M, functional, on a software GPU)

* The offline analyzer (`ai_edge_litert._pywrap_analyzer_wrapper.ModelAnalyzer(path, True, True)`,
  i.e. `tf.lite.experimental.Analyzer` with `gpu_compatibility=True`) says all six bt_export
  graphs "look compatible with GPU delegate". **That is wrong:** it does not check tensor ranks.
* Running LiteRT's own GPU accelerator (`CompiledModel`, WebGPU backend, shipped in the
  `ai-edge-litert` wheel) on Mesa lavapipe (`apt install mesa-vulkan-drivers`, installed on this
  machine, outside the repo) gives the real partition: **bt_export graph: 24 of 895 ops on the
  GPU**. Rejected: 240 RESHAPE and 48 CONCATENATION ("tensor dimensions must be less than 5"),
  SLICE (rank > 4), TRANSPOSE (invalid permutation, 5-D), L2_NORMALIZATION ("not supported";
  the converter fused the RMSNorm's `y / sqrt(sum(y^2))` into it). With CPU fallback the mixed
  graph also gave **wrong logits** (max diff 13-15, picks changed), so partial delegation is not
  an option.
* `scripts/bt_export_gpu.py` rewrites the same maths with rank <= 4: q/k/v sliced from the 3-D
  projection; the interleaved rotary swap as a constant 32 x 32 `F.linear` (a `torch.matmul`
  against a 2-D constant became BROADCAST_TO, also unsupported); RMSNorm as
  `y * rsqrt(mean(y^2) + 1e-7)` after the FP16-safe max scaling. Result: **fully accelerated,
  all ops on the GPU, both checkpoints, FP32 and FP16 files**; logits in section 2.
* Op histogram of the converted graphs (bt_export, both models): RESHAPE 280, MUL 147, SLICE 86,
  FULLY_CONNECTED 62, TRANSPOSE 52, ADD 50, REDUCE_MAX/MAXIMUM/L2_NORMALIZATION/DIV/
  CONCATENATION/ABS 25 each, BATCH_MATMUL 24, GELU 16, SOFTMAX 12, LOGISTIC 12, CONV_2D 3,
  DEPTHWISE_CONV_2D 1 (+ 66 DEQUANTIZE in FP16 files).
* Caveats: lavapipe is not a phone GPU (Mali/Adreno drivers, OpenCL vs WebGPU backends, FP16
  rounding); "compiles fully" here is strong evidence, not proof. Small test modules
  (`scripts/gpu_toys.py`) confirm the GPU path computes linear+GELU, the front-end permutes,
  the 412 x (32 x 32) frequency attention and the 32 x (412 x 412) time attention correctly
  (errors at FP16 level). No GPU speed can be measured here.

## 4. Phone estimates (E)

Per-core speed ratios against this VM core, from published Geekbench 6 single-core scores:

| core | phone | GB6 single-core (source) | ratio to this VM |
|---|---|---|---|
| this VM (Emerald Rapids, ~2.6-3.0 GHz measured) | | 2000-2300: Google Cloud C4 (Xeon Platinum 8581C, Emerald Rapids) runs on vpsbenchmarks.com (2293, 2001) and sparecores.com (2319 c4-highcpu-4, 2196 c4-highcpu-32). Assumption: this guest is that class; not measured with Geekbench here | 1 |
| Cortex-A78 @ 2.4 GHz | Galaxy A54 (Exynos 1380) | ~1000-1110: Notebookcheck Exynos 1380 page (GB 6.5 avg 1015, range 1008-1023), 91mobiles A54 (1007), SamMobile A54 (1108) | **1.8-2.3x slower** |
| Cortex-X1 @ 2.8 GHz | Pixel 6a (Tensor G1; note: X1, faster than A78) | 1190-1496: Geekbench Browser Pixel 6a results | 1.3-1.9x slower |
| Cortex-A55 @ 2.0 GHz | Galaxy A14 4G (Exynos 850, 8 x A55) | ~215-222: CPU-Monkey (219), duelvs (222), Geekbench Browser Exynos 850 results (~221) | **9-11x slower** |

GB6 is a broad mix and understates the gap for SIMD-bound kernels. So for LiteRT/XNNPACK a second
bound uses FP32 throughput. This core reaches 70 (small0) / 98 (final0) GFLOPS in XNNPACK (M). The
phone figures assume XNNPACK reaches 50-75 % of NEON peak: A78 2 x 128-bit FMA/cycle x 2.4 GHz =
38 GFLOPS peak, so about 19-29 achieved; A55 1 x 128-bit FMA/cycle x 2.0 GHz = 16 peak, so about 8-12
achieved (assumption). LiteRT factor ranges: A78 1.8x (GB6) to 3.7x (small0) / 5.2x (final0);
A55 5.8-10.7x (small0) / 8.2-12.3x (final0).

For plain Kotlin the base is the HotSpot time with C2 held to 128-bit vectors (NEON width; M:
507 / 1285 ms), times the GB6 ratio, times an **ART factor**. I found no published ART-vs-C2
figure. ART has had an auto-vectoriser for NEON since Android 8 (Bik et al., "Automatic
Vectorization in ART", Google Research, 2017), but it is a different compiler with its own
limits. Assumption: **1.0-1.5x** if ART vectorises these loops as C2 does. If it does not, the
measured no-vectoriser time applies (C2 without SuperWord: 1570 / 3964 ms, i.e. 3.1x more). That
worst case is listed separately and must be checked on a phone.

Estimated time per window, one thread, and share of one core at one window per second:

| route | model | A78 (Galaxy A54) | A55 only (Galaxy A14 4G) |
|---|---|---|---|
| LiteRT XNNPACK, GPU-friendly FP32/FP16 | small0 | 0.22-0.45 s = **22-45 % of a big core** | 0.7-1.3 s = **70-130 % of a core** |
| LiteRT XNNPACK, GPU-friendly FP32/FP16 | final0 | 0.45-1.3 s = **45-130 %** | 2.0-3.1 s = **200-310 %** |
| plain Kotlin on ART, loops vectorised | small0 | 0.9-1.75 s = **90-175 %** | 4.6-8.1 s = 460-810 % |
| plain Kotlin on ART, loops vectorised | final0 | 2.3-4.4 s = **230-440 %** | 11.6-20.6 s |
| plain Kotlin on ART, loops not vectorised (worst) | small0 / final0 | 2.8-3.6 s / 7.1-9.1 s | 14-17 s / 36-42 s |

Threads (E, from the measured scaling here: Kotlin 2.3-2.8x on 4 threads, XNNPACK 3.1-3.8x): they
cut latency, not total CPU. E.g. small0 LiteRT on 2 A78 cores is about 0.15-0.3 s per window.
Running a window every 2 s instead of every 1 s halves every share. A phone core that is busy
all the time also throttles and drains the battery, so shares above ~30-50 % of a big core are
not acceptable for a continuously listening app. GPU speed is not estimated: there is no
measurement to scale from. The A54 has a Mali-G68 MP5; the A14 4G has a single-core Mali-G52 MP1.

## 5. Sizes

| item | small0 | final0 |
|---|---|---|
| Kotlin "BTHS" .bin, FP32 (M) | 8.40 MB | 81.0 MB |
| same with FP16 storage, decoded at load like crepe-tiny.bin (E: half; same rounding as the LiteRT FP16 files, which keep all picks) | 4.2 MB | 40.5 MB |
| .tflite GPU-friendly FP32 / FP16 / INT8 (M) | 8.71 / 4.53 / 2.57 MB | 81.3 / 40.9 / 21.0 MB |
| .tflite bt_export graph FP32 / FP16 / INT8 (M) | 8.71 / 4.54 / 2.57 MB | 81.3 / 40.9 / 21.0 MB |
| Kotlin weights in the Java heap at run time (E) | 8.4 MB | 81 MB (risky on low-end heaps) |

LiteRT runtime, `com.google.ai.edge.litert` 2.3.2 on Google Maven (M, from the AARs):

| artifact | arm64-v8a native lib | deflated (download) | other ABIs in the AAR |
|---|---|---|---|
| `litert` | libLiteRt.so 5.63 MB | 2.33 MB | armeabi-v7a 3.65 MB, x86_64 7.78 MB |
| `litert-api` (pulled in by `litert`) | liblitert_jni.so 0.53 MB + 133 KB classes | 0.21 MB | 0.32 / 0.51 MB |
| `litert-gpu` (GPU accelerator, separate) | libLiteRtClGlAccelerator.so 3.25 MB | 1.50 MB | 2.30 / 3.92 MB |

So for arm64: **+6.2 MB installed / ~2.5 MB download for the CPU runtime, +3.3 MB / 1.5 MB more
for GPU**, plus the model. AGP stores native libraries uncompressed in the APK by default
(minSdk >= 23), so the APK grows by the installed size. The app sets no `abiFilters`, so a
universal APK would carry all three ABIs: 18.4 MB CPU runtime, plus 9.5 MB GPU. An App Bundle
delivers only one. `litert-api` also depends on Guava, kotlinx-coroutines-guava and
`ai-delivery` 0.1.1-alpha01; R8 will shrink these, but an APK was not built here. minSdk 24 (the
app has 26). For comparison the app's current model assets are 0.97 MB (CREPE) + 0.12 MB (Basic
Pitch). The plain-Kotlin route adds no runtime, only about 30 KB of code.

## 6. What each route would take to ship

Common to both (not done here): port the front end and the reader from
`ios/.../BeatThis.swift` to Kotlin (written to port line by line), plus its fixtures and tests.
Also wire it into `MusicAnalyzer` on a worker: one window per second, skip silent windows, date
the readings in audio time, keep the standard tracker as fallback, add a Settings switch as on
iPhone. Then check on a real phone.

**Plain Kotlin.** Done: forward pass (`kotlin/src/BeatThis.kt`, ~450 lines incl. weight
reader and thread pool), exporter (`scripts/export_bin.py`), reference check (0.00012 max,
identical picks), timings. Left: move into `app/` with a unit test against reference logits
(runs in plain JVM tests like CrepePitch); FP16 storage for the asset; and above all **measure
on a phone**. The estimate depends on ART vectorising the inner loops. If it does not, small0
needs about 3 s of A78 time per second of audio. Even in the good case small0 takes about 1-1.75
big-core-seconds per second, and final0 is out of reach.

**LiteRT.** Done: conversion of both checkpoints with litert-torch (worked as-is on the
`bt_export` module), FP32/FP16/INT8 files, accuracy, XNNPACK timings, and the GPU-friendly graph
that runs fully on LiteRT's GPU accelerator. Left: first ML library in the app (dependency, +6 MB
arm64, ABI splits / App Bundle, R8 rules); a Kotlin wrapper (`CompiledModel` or `Interpreter`,
assets stored uncompressed so the model can be memory-mapped); host JVM unit tests cannot load
the native runtime, so the network check moves to an instrumented test or stays in Python;
measure CPU and GPU speed and GPU logits on at least one A78-class and one A55-only phone; decide
whether to enable XNNPACK FP16 arithmetic on ARMv8.2 (likely faster; must be checked against the
references like the GPU path).

## 7. Recommendation

LiteRT, with the GPU-friendly graph (`bt_export_gpu.py`), FP16 weights (small0 4.5 MB),
on the CPU through XNNPACK with 1-2 threads. Estimated 22-45 % of one A78 core per second of audio
for small0, about 0.8x the CPU the app's Basic Pitch already uses on the same JVM core. Use it for
Band, and for Guitar on phones with A78-class or better big cores; final0 (41 MB, an estimated
45-130 % of a big core) only if a phone measurement lands at the low end, or later through the
GPU accelerator once its logits have been checked on a real Mali/Adreno. On A55-only phones
(Galaxy A14 4G class), even small0 needs about one whole core at one window per second: run it
every 2 s, or keep the standard tracker there. Plain Kotlin is
exact and dependency-free, but 3.5x slower than XNNPACK on the same core here (428 vs 122 ms),
and the gap is likely larger on ARM. It is acceptable only for small0, only on big cores, and
only if ART's code proves as good as C2's. INT8 is not worth its accuracy loss.

## 8. In the app

What ships: `app/src/main/assets/beat-this-small.tflite` (4,559,576 bytes, SHA-256
`28a89c6e9660fcf5f0cfd1f48035c73eaff4848d3955d7d86362024b82f40c9a`), small0 for Band and Guitar
alike, stored uncompressed in the APK and memory-mapped. Made in three steps: the GPU-friendly
graph of `scripts/bt_export_gpu.py` converted by `scripts/convert_tflite.py --gpu-friendly` with
FP16 weights (float casting), then `scripts/fp16_biases.py`, which stores the 42 biases as FP16
too (below). The Kotlin side is `analysis/.../dsp/BeatThis.kt` (the port of `BeatThis.swift`, tested
against the iPhone's fixtures) and `app/.../LiteRtBeatThis.kt`.

**Runtime.** `com.google.ai.edge.litert:litert` **1.4.2**, the plain `org.tensorflow.lite.Interpreter`,
one thread, XNNPACK, FP32 arithmetic (`setAllowFp16PrecisionForFp32` off; no GPU delegate). Its
only dependency is `litert-api` 1.4.2, which has none. The 2.x line (2.3.2) has the same
Interpreter, but its `litert-api` pulls in Guava 33.5.0-android, kotlinx-coroutines-guava,
androidx lifecycle-runtime 2.10.0 and `com.google.android.play:ai-delivery` 0.1.1-alpha01. The
1.4.2 native libraries are 16 KB page-aligned (LOAD segments aligned 0x4000). Its JNI library
per ABI: arm64-v8a 4.48 MB, armeabi-v7a 2.64 MB, x86 6.59 MB, x86_64 6.27 MB.

**Why the biases are FP16 too (M).** LiteRT 1.4's XNNPACK delegate declines a FULLY_CONNECTED,
CONV_2D or DEPTHWISE_CONV_2D whose weights come from an FP16 DEQUANTIZE but whose bias is a plain
FP32 constant, which is how ai-edge-quantizer leaves them. In this graph that is 38 of 86
FULLY_CONNECTED (all those with a bias) and the 4 convolutions, so the graph ran in 43 pieces.
LiteRT 2.3 takes the same file in one piece. Python LiteRT 1.4.0 (the nearest release to the
1.4.2 AAR on PyPI) vs 2.3.0, one 412-frame window, median of 21 runs, this machine:

| file | LiteRT 1.4.0, 1 thread | 2 threads | LiteRT 2.3.0, 1 thread | 2 threads | XNNPACK pieces (1.4.0) |
|---|---|---|---|---|---|
| FP16 weights, FP32 biases (`convert_tflite.py` output) | 288 ms | 226 ms | 115 ms | 61 ms | 43 |
| FP32 weights | 118 ms | | | | 1 |
| FP16 weights and biases (shipped) | 130 ms | 63 ms | 116 ms | 56 ms | 1 |

**Check of the shipped file against PyTorch (M).** `scripts/run_tflite.py logits` on the 11
windows of section 2 and the two network inputs of the iPhone's fixture
(`ios/KeyCatchCore/Tests/KeyCatchCoreTests/Resources/beat-this-coreml*`, with the official FP32
small0's logits), then `scripts/compare.py` against the official PyTorch FP32 model (picks on the
400 inner frames):

| window | max logit diff (LiteRT 1.4.0 / 2.3.0) | sign changes | beats, downbeats ref -> LiteRT |
|---|---|---|---|
| GTZAN rock.00000 | 0.0239 / 0.0239 | 0 / 824 | 15 -> 15, 4 -> 4, same |
| GTZAN blues.00010 | 0.0576 / 0.0577 | 0 / 824 | 21 -> 21, 9 -> 9, same |
| GTZAN classical.00010 | 0.0224 / 0.0224 | 0 / 824 | 14 -> 14, 10 -> 10, same |
| GTZAN country.00010 | 0.0170 / 0.0170 | 0 / 824 | 14 -> 14, 5 -> 5, same |
| GTZAN disco.00010 | 0.0249 / 0.0249 | 0 / 824 | 14 -> 14, 3 -> 3, same |
| GTZAN hiphop.00010 | 0.0065 / 0.0065 | 0 / 824 | 12 -> 12, 3 -> 3, same |
| GTZAN jazz.00010 | 0.0053 / 0.0053 | 0 / 824 | 14 -> 14, 5 -> 5, same |
| GTZAN metal.00010 | 0.0120 / 0.0120 | 0 / 824 | 33 -> 33, 7 -> 7, same |
| GTZAN pop.00010 | 0.0131 / 0.0131 | 0 / 824 | 14 -> 14, 3 -> 3, same |
| GTZAN reggae.00010 | 0.0063 / 0.0063 | 0 / 824 | 11 -> 11, 3 -> 3, same |
| GuitarSet solo mic 00_BN1-129-Eb | 0.0062 / 0.0062 | 0 / 824 | 9 -> 9, 5 -> 5, same |
| fixture: GuitarSet comp take at 12 s | 0.0115 / 0.0115 | 0 / 824 | 11 -> 11, 3 -> 3, same |
| fixture: synthetic band at 10 s | 0.0124 / 0.0123 | 0 / 824 | 13 -> 13, 6 -> 6, same |

Largest difference 0.058, no logit changes sign, and every window picks the same beats and
downbeats as PyTorch, on both runtimes. (The file before `fp16_biases.py`: 0.052, likewise the
same picks.) Not run: the 1.4.2 AAR itself (an Android library; the JVM unit tests cannot load
it), and anything on a phone.

**Pace on a slow phone.** `BeatTracker` times its first three windows (the median, so the first
run's start-up does not count; silent windows skip the network and are not timed). Above
**500 ms** it runs one window every 2 s: half of a core all the time is more than a listening
app should take (section 4), and Basic Pitch and the rest of the analysis need the phone too.
At 2 s, above **1500 ms** (3/4 of the time, where Details says "Falling behind") it gives up and
the standard tracker takes over; Details says so. Learnt once per launch and never stepped back
up; choosing Accurate again in Settings starts over. By section 4's estimates an A78-class phone
stays at 1 s (0.22-0.45 s) and an A55-only phone goes to 2 s (0.7-1.3 s). Readings are dated in
audio time, and the reading's pooling is per second of music (`tools/beatthis/README.md`
section 8), so the 2 s pace only makes the screen update less often.

**APK (M).** `assembleRelease` (R8, no ABI filter, so all four ABIs): 2,488,804 -> 27,114,927
bytes (+24.6 MB: native libraries 20.0 MB, model 4.56 MB, code 26 KB). For one ABI, as an App
Bundle or a split APK delivers it, arm64-v8a adds 4.48 + 4.56 = 9.0 MB. `assembleDebug`:
12,734,719 -> 37,377,834 bytes.

## Files

| path | what |
|---|---|
| `kotlin/src/BeatThis.kt` | plain-Kotlin forward pass (v2: row-array weights, blocked kernel, transposed attention, polynomial exp/GELU, thread pool) |
| `kotlin/src/Bench.kt` | driver: `check` (write logits), `bench` (timing; `BT_PROFILE=1` per stage), `yard` (BasicPitch/CREPE) |
| `kotlin/v1/BeatThisV1.kt.txt` | first version, kept for the before/after timing |
| `kotlin/micro/Micro.kt` | loop-shape microbenchmark that found what C2 vectorises |
| `kotlin/build.sh`, `kotlin/bench_all.sh` | build `bt.jar` (with the repo's BasicPitch/CrepePitch/Fft), run all Kotlin timings |
| `scripts/export_bin.py` | checkpoint -> "BTHS" .bin; test windows and PyTorch reference logits |
| `scripts/convert_tflite.py` | litert-torch conversion, FP16 (float casting) and INT8 (dynamic) with ai-edge-quantizer; `--gpu-friendly` |
| `scripts/bt_export_gpu.py` | rank <= 4, no-L2_NORMALIZATION export module |
| `scripts/run_tflite.py` | LiteRT interpreter: logits, timing (XNNPACK on/off) |
| `scripts/fp16_biases.py` | stores an FP16 file's biases as FP16 too, so LiteRT 1.4's XNNPACK takes the whole graph (section 8) |
| `scripts/gpu_try.py`, `scripts/gpu_toys.py` | LiteRT GPU accelerator on lavapipe: partition, logits; small modules |
| `scripts/compare.py`, `scripts/summarise_accuracy.py`, `scripts/accuracy_all.sh` | accuracy vs PyTorch |
| `scripts/clock.c` | effective clock check |
| `out/` | raw outputs: `kotlin_bench.txt`, `kotlin_vector_width.txt`, `yardstick.txt`, `tflite_bench*.txt`, `accuracy.md`, `gpu/` (analyzer dumps, GPU logs) |
| `data/`, `data2/`, `tflite/` | .bin weights, test windows, references, .tflite models |

Environment: `venv-lrt/` (Python 3.12: torch 2.14.1+cpu, litert-torch 0.10.0, ai-edge-litert 2.3.0,
ai-edge-quantizer 0.10.1); `dl/kotlinc` (Kotlin 2.3.21 compiler from GitHub releases);
`dl/maven` (LiteRT AARs). Reference/export use the existing `../venv` (torch 2.7.1).
