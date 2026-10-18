package com.donfluids.music

/**
 * Tempo from taps. The tempo is the median gap between the last few taps, so one tap that
 * comes early or late does not throw it; a pause starts a new count, and a tap that follows the
 * last one almost at once (a finger bouncing) is ignored.
 */
class TapTempo(
    private val maxTaps: Int = 8,
    private val resetNanos: Long = 2_500_000_000L,
    private val bounceNanos: Long = 150_000_000L,
) {
    private val taps = ArrayDeque<Long>()

    data class Reading(val bpm: Float, val lastTapNanos: Long, val taps: Int)

    /** Records a tap at [nowNanos]; the reading once there are two taps, else null. */
    fun tap(nowNanos: Long): Reading? {
        if (taps.isNotEmpty()) {
            val gap = nowNanos - taps.last()
            if (gap in 0 until bounceNanos) return reading()
            if (gap > resetNanos || gap < 0) taps.clear()
        }
        taps.addLast(nowNanos)
        while (taps.size > maxTaps) taps.removeFirst()
        return reading()
    }

    private fun reading(): Reading? {
        if (taps.size < 2) return null
        val gaps = taps.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        val median = if (gaps.size % 2 == 1) gaps[gaps.size / 2] else (gaps[gaps.size / 2 - 1] + gaps[gaps.size / 2]) / 2
        return Reading((60e9 / median).toFloat().coerceIn(30f, 300f), taps.last(), taps.size)
    }

    fun clear() = taps.clear()
}
