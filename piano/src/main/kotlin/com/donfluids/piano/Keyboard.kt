package com.donfluids.piano

/**
 * Geometry and note mapping for the full keyboard, C1 to B7, laid out at one white-key width.
 * The view shows a window of [visibleWhite] keys starting [scroll] white keys from the left.
 */
class KeyboardLayout(val visibleWhite: Int, val viewWidth: Float, val height: Float) {
    val whiteWidth = viewWidth / visibleWhite
    val blackWidth = whiteWidth * 0.62f
    val blackHeight = height * 0.6f

    /** Furthest the window can scroll, in white keys. */
    val maxScroll: Float get() = (WHITE_COUNT - visibleWhite).toFloat()

    class Key(val midi: Int, val black: Boolean, val left: Float, val width: Float, val height: Float) {
        val right get() = left + width
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= 0f && y < height
    }

    /** Every key on the full keyboard, in absolute pixels from C1. */
    val whiteKeys: List<Key> = (0 until WHITE_COUNT).map { i ->
        Key(FIRST_MIDI + 12 * (i / 7) + WHITE_SEMITONES[i % 7], false, i * whiteWidth, whiteWidth, height)
    }
    val blackKeys: List<Key> = (0 until WHITE_COUNT).mapNotNull { i ->
        val degree = i % 7
        if (degree !in BLACK_AFTER) return@mapNotNull null
        Key(FIRST_MIDI + 12 * (i / 7) + WHITE_SEMITONES[degree] + 1, true, (i + 1) * whiteWidth - blackWidth / 2, blackWidth, blackHeight)
    }

    /** Pixel offset of the window for a scroll position in white keys. */
    fun scrollPx(scroll: Float) = scroll * whiteWidth

    /** Keys that intersect the window at [scroll], for drawing. */
    fun visible(scroll: Float, keys: List<Key>): List<Key> {
        val left = scrollPx(scroll) - whiteWidth
        val right = scrollPx(scroll) + viewWidth + whiteWidth
        return keys.filter { it.right > left && it.left < right }
    }

    /** The key under a view-relative point. Black keys sit on top, so they are tested first. */
    fun keyAt(viewX: Float, y: Float, scroll: Float): Key? {
        val x = viewX + scrollPx(scroll)
        return blackKeys.firstOrNull { it.contains(x, y) } ?: whiteKeys.firstOrNull { it.contains(x, y) }
    }

    /** MIDI note of the C at or below the leftmost fully visible white key. */
    fun leftmostOctaveC(scroll: Float): Int {
        val i = kotlin.math.ceil(scroll - 1e-3).toInt().coerceIn(0, WHITE_COUNT - 1)
        return FIRST_MIDI + 12 * (i / 7)
    }

    companion object {
        const val FIRST_MIDI = 24 // C1
        const val OCTAVES = 7
        const val WHITE_COUNT = OCTAVES * 7
        private val WHITE_SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        private val BLACK_AFTER = setOf(0, 1, 3, 4, 5) // C D F G A carry a sharp to their right
        private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun noteName(midi: Int): String = NAMES[Math.floorMod(midi, 12)] + (midi / 12 - 1)
        /** Scroll position that puts the C of [octave] at the left edge. */
        fun scrollForOctave(octave: Int) = ((octave - 1) * 7).toFloat()
    }
}
