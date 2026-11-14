"""Prepares a SCRATCH COPY of the repo (never the real checkout) for the detune tracer:
  * MusicAnalyzer: the singer model, key level, segmenter and key notes made `internal`, so the
    tracer can read them (no behaviour change)
  * DetuneFix.kt: a per-thread set of flags the prototype fixes test (empty = the app as it is)
  * app/build.gradle.kts: forwards dt.list, dt.threads, dt.variants to the test JVM
  * copies DetuneTrace.kt into the unit tests.

usage: python -I scratch_trace.py SCRATCH_REPO
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
    m = f"{d}/MusicAnalyzer.kt"
    for old in ("private val singer = SingerModel()", "private var keyLevel: KeyLevelTracker.Level? = null",
                "private var nowShiftShown = 0", "private fun keyNotes(): List<SungNote>",
                "private fun keySigma(): Double", "private val segmenter = NoteSegmenter()"):
        sub(m, old, old.replace("private ", "internal ", 1))
    open(f"{d}/DetuneFix.kt", "w").write(
        "package com.donfluids.music.dsp\n\n"
        "/** tools/keyeval/detune scratch only: flags for the prototype fixes, per thread. Empty = the app. */\n"
        "object DetuneFix {\n"
        "    val flags: ThreadLocal<Set<String>> = ThreadLocal.withInitial { emptySet() }\n"
        "    fun on(name: String): Boolean = name in flags.get()\n"
        "}\n")
    g = os.path.join(repo, "app/build.gradle.kts")
    s = open(g).read()
    if "dt.variants" not in s:
        open(g, "a").write(
            "\n// tools/keyeval/detune (scratch copy only)\ntasks.withType<Test>().configureEach {\n"
            '    providers.gradleProperty("dt.list").orNull?.let { systemProperty("dt.list", it); maxHeapSize = "6g" }\n'
            '    providers.gradleProperty("dt.threads").orNull?.let { systemProperty("dt.threads", it) }\n'
            '    providers.gradleProperty("dt.variants").orNull?.let { systemProperty("dt.variants", it) }\n'
            "}\n")
    shutil.copy(os.path.join(os.path.dirname(os.path.abspath(__file__)), "DetuneTrace.kt"),
                os.path.join(repo, "app/src/test/kotlin/com/donfluids/music/dsp/DetuneTrace.kt"))
    print("scratch copy prepared:", repo)


if __name__ == "__main__":
    main()
