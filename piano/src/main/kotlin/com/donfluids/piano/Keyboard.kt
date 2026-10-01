package com.donfluids.piano

/** Geometry and note mapping for a run of whole octaves starting at C. */
class KeyboardLayout(val baseOctave: Int, val octaves: Int, val width: Float, val height: Float) {
    val whiteCount = octaves * 7
    val whiteWidth = width / whiteCount
    val blackWidth = whiteWidth * 0.62f
    val blackHeight = height * 0.6f

    /** MIDI note of the C that begins the keyboard (C4 = 60). */
    val baseMidi = 12 * (baseOctave + 1)

    class Key(val midi: Int, val black: Boolean, val left: Float, val width: Float, val height: Float) {
        val right get() = left + width
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= 0f && y < height
    }

    val whiteKeys: List<Key> = (0 until whiteCount).map { i ->
        Key(baseMidi + 12 * (i / 7) + WHITE_SEMITONES[i % 7], false, i * whiteWidth, whiteWidth, height)
    }
    val blackKeys: List<Key> = (0 until whiteCount).mapNotNull { i ->
        val degree = i % 7
        if (degree !in BLACK_AFTER) return@mapNotNull null
        Key(baseMidi + 12 * (i / 7) + WHITE_SEMITONES[degree] + 1, true, (i + 1) * whiteWidth - blackWidth / 2, blackWidth, blackHeight)
    }

    /** Black keys sit on top, so they are tested first. */
    fun keyAt(x: Float, y: Float): Key? = blackKeys.firstOrNull { it.contains(x, y) } ?: whiteKeys.firstOrNull { it.contains(x, y) }

    companion object {
        private val WHITE_SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        private val BLACK_AFTER = setOf(0, 1, 3, 4, 5) // C D F G A carry a sharp to their right
        private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun noteName(midi: Int): String = NAMES[Math.floorMod(midi, 12)] + (midi / 12 - 1)
    }
}
