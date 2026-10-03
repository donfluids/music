package com.donfluids.piano

/**
 * Counts the player in: a tap per beat, and after [beatsNeeded] taps (one bar, or two bars of
 * 6/8 felt in two) the tempo is the mean gap and the band's downbeat is one beat after the last
 * tap, in the player's own time.
 * A pause longer than [resetNanos] starts the count again.
 */
class TapEntry(var beatsNeeded: Int = 4, private val resetNanos: Long = 2_500_000_000L) {
    private val taps = ArrayList<Long>()
    val count: Int get() = taps.size

    data class Entry(val bpm: Int, val periodNanos: Long, val downbeatNanos: Long)

    /** Records a tap at [nowNanos]; returns the entry once enough taps are in, else null. */
    fun tap(nowNanos: Long): Entry? {
        if (taps.isNotEmpty() && nowNanos - taps.last() > resetNanos) taps.clear()
        taps += nowNanos
        if (taps.size < beatsNeeded) return null
        val recent = taps.takeLast(beatsNeeded)
        val period = (recent.last() - recent.first()) / (recent.size - 1)
        val bpm = Math.round(60e9 / period).toInt().coerceIn(30, 240)
        taps.clear()
        return Entry(bpm, period, recent.last() + period)
    }

    fun reset() = taps.clear()
}
