"""Applies the keyeval instrumentation to a SCRATCH COPY of the repo (never the real checkout):
  * KeyDetector / MelodicKeyFinder: a per-thread (major, minor) profile override
  * HeardNotes.source: a follower instance reads a leader's Basic Pitch frames (no model run)
  * MusicAnalyzer.evidenceHook: the mode evidence at every key update
  * app/build.gradle.kts: forwards kc.list, kc.threads, kc.variants, kc.evidence to the test JVM
and copies KeyVariantsDump.kt into the unit tests. With no override, no source and no hook the
app behaves exactly as before (checked: the "app:app" variant reproduces tools/skey's readings).

usage: python -I scratch_edits.py SCRATCH_REPO
"""
import os
import shutil
import sys


def sub(path, old, new):
    s = open(path).read()
    if new in s:
        return
    assert s.count(old) == 1, (path, old[:60])
    open(path, "w").write(s.replace(old, new))


def main():
    repo = sys.argv[1]
    if os.path.exists(os.path.join(repo, ".git")):
        raise SystemExit("refusing: this looks like a real checkout; use a scratch copy")
    d = os.path.join(repo, "app/src/main/kotlin/com/donfluids/music/dsp")
    ov = "    /** keyeval scratch: per-thread (major, minor) profile override. */\n    val override = ThreadLocal<Array<DoubleArray>?>()\n\n"
    sub(f"{d}/KeyDetector.kt", "    data class Candidate(val key: Key, val correlation: Double)",
        ov + "    data class Candidate(val key: Key, val correlation: Double)")
    sub(f"{d}/KeyDetector.kt", "            val profile = if (mode == Mode.MAJOR) MAJOR else MINOR",
        "            val ov = override.get()\n            val profile = if (mode == Mode.MAJOR) (ov?.get(0) ?: MAJOR) else (ov?.get(1) ?: MINOR)")
    sub(f"{d}/PitchTracker.kt", "    data class Candidate(val key: com.donfluids.music.music.Key, val score: Double)",
        ov + "    data class Candidate(val key: com.donfluids.music.music.Key, val score: Double)")
    sub(f"{d}/PitchTracker.kt", "            val p = if (mode == com.donfluids.music.music.Mode.MAJOR) MAJOR else MINOR",
        "            val ov = override.get()\n            val p = if (mode == com.donfluids.music.music.Mode.MAJOR) (ov?.get(0) ?: MAJOR) else (ov?.get(1) ?: MINOR)")
    sub(f"{d}/HeardNotes.kt", "    fun push(samples: FloatArray, offset: Int, count: Int) {\n",
        "    /** keyeval scratch: when set, this instance hears nothing itself and reads [source]'s frames. */\n"
        "    var source: HeardNotes? = null\n\n    fun push(samples: FloatArray, offset: Int, count: Int) {\n        if (source != null) return\n")
    sub(f"{d}/HeardNotes.kt", "    fun pitchClasses(fromSeconds: Double, toSeconds: Double, out: FloatArray): Int {\n",
        "    fun pitchClasses(fromSeconds: Double, toSeconds: Double, out: FloatArray): Int {\n"
        "        source?.let { return it.pitchClasses(fromSeconds, toSeconds, out) }\n")
    upd = "            pendingKey = modeTracker.update(found, evidence.total, RelativeKeyResolver.THRESHOLD * evidenceNeeded())\n"
    sub(f"{d}/MusicAnalyzer.kt", upd,
        upd + "            evidenceHook?.invoke(now, found, evidence, evidenceNeeded(), displayFromChords, songKey)\n")
    sub(f"{d}/MusicAnalyzer.kt", "    private var pendingKey: Key? = null\n",
        "    /** keyeval scratch: called after each mode decision with (now, found key, evidence, threshold scale, chords shown, song key). */\n"
        "    var evidenceHook: ((Double, Key, RelativeKeyResolver.Evidence, Double, Boolean, Key?) -> Unit)? = null\n"
        "    private var pendingKey: Key? = null\n")
    g = os.path.join(repo, "app/build.gradle.kts")
    block = ("\n// keyeval (scratch copy only): forward the JVM driver's settings.\ntasks.withType<Test>().configureEach {\n"
             + "".join(f'    providers.gradleProperty("{p}").orNull?.let {{ systemProperty("{p}", it){"; maxHeapSize = \"6g\"" if p == "kc.list" else ""} }}\n'
                       for p in ("kc.list", "kc.threads", "kc.variants", "kc.evidence")) + "}\n")
    s = open(g).read()
    if "kc.variants" not in s:
        open(g, "a").write(block)
    shutil.copy(os.path.join(os.path.dirname(os.path.abspath(__file__)), "KeyVariantsDump.kt"),
                os.path.join(repo, "app/src/test/kotlin/com/donfluids/music/dsp/KeyVariantsDump.kt"))
    print("scratch copy instrumented:", repo)


if __name__ == "__main__":
    main()
