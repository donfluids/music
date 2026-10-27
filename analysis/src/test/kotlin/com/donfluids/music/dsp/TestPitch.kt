package com.donfluids.music.dsp

import java.io.File

/** The CREPE estimator the app offers for melody presets, for tests that run the whole analyser. */
object TestPitch {
    /** The weights ship as an app asset; tests may run from the app module or a sibling one. */
    val weights: CrepePitch.Weights by lazy {
        val file = listOf("src/main/assets/crepe-tiny.bin", "../app/src/main/assets/crepe-tiny.bin").map(::File).first { it.exists() }
        CrepePitch.Weights.read(file.readBytes())
    }
    val crepe: (Int) -> PitchEstimator = { rate -> CrepePitch(weights, rate) }
}
