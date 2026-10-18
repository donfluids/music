package com.donfluids.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapTempoTest {
    private val ms = 1_000_000L

    @Test
    fun twoTapsGiveATempoAndMoreTapsSteadyIt() {
        val t = TapTempo()
        assertNull(t.tap(0))
        assertEquals(120f, t.tap(500 * ms)!!.bpm, 0.01f)
        // Human taps wobble; the reading stays close.
        var r: TapTempo.Reading? = null
        for ((i, wobble) in listOf(12, -9, 15, -11, 6).withIndex()) r = t.tap((1000L + i * 500 + wobble) * ms)
        assertEquals(120f, r!!.bpm, 3f)
        assertEquals(7, r.taps)
    }

    @Test
    fun oneStrayTapDoesNotThrowTheTempo() {
        val t = TapTempo()
        listOf(0L, 600, 1200, 1800, 2160, 3000, 3600).forEach { t.tap(it * ms) } // the fifth tap is early
        assertEquals(100f, t.tap(4200 * ms)!!.bpm, 2f)
    }

    @Test
    fun aPauseStartsANewCountAndABounceIsIgnored() {
        val t = TapTempo()
        t.tap(0); t.tap(500 * ms); t.tap(1000 * ms)
        // Three seconds later: a fresh count at a new tempo.
        assertNull(t.tap(4000 * ms))
        assertEquals(60f, t.tap(5000 * ms)!!.bpm, 0.01f)
        // A finger bouncing 40 ms later changes nothing.
        assertEquals(60f, t.tap(5040 * ms)!!.bpm, 0.01f)
        assertEquals(2, t.tap(5040 * ms)!!.taps)
    }

    @Test
    fun clearForgetsTheTaps() {
        val t = TapTempo()
        t.tap(0); t.tap(500 * ms)
        t.clear()
        assertNull(t.tap(1000 * ms))
    }
}
