package com.donfluids.music.dsp

import java.io.File

/** The Basic Pitch weights the app ships, for tests; they may run from the app module or a sibling one. */
object TestNotes {
    val weights: BasicPitch.Weights by lazy {
        val file = listOf("src/main/assets/basic-pitch.bin", "../app/src/main/assets/basic-pitch.bin").map(::File).first { it.exists() }
        BasicPitch.Weights.read(file.readBytes())
    }
}
