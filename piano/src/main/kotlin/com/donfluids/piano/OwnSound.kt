package com.donfluids.piano

import java.util.concurrent.atomic.AtomicLongArray

/**
 * Which pitch classes the phone's own speaker is sounding, timed as the microphone hears them,
 * so Listen can leave the app's own piano and band out of what it reads.
 *
 * The audio thread reports each block's sounding notes with the time the block leaves the
 * speaker. A pitch class stays marked for [holdNanos] after its last report, which covers the
 * analysis window (about a tenth of a second), the speaker's actual delay, and room echo.
 */
class OwnSound(private val holdNanos: Long = 350_000_000L) {
    private val lastSounded = AtomicLongArray(12).also { for (i in 0 until 12) it.set(i, Long.MIN_VALUE) }

    /** From the audio thread: [mask] (bit n = pitch class n) plays from the speaker at [atNanos]. */
    fun sounded(mask: Int, atNanos: Long) {
        if (mask == 0) return
        for (pc in 0 until 12) if (mask and (1 shl pc) != 0) lastSounded.set(pc, maxOf(lastSounded.get(pc), atNanos))
    }

    /** From the analysis thread: the pitch classes of the app's own sound in audio captured at [nowNanos]. */
    fun mask(nowNanos: Long): Int {
        var m = 0
        for (pc in 0 until 12) {
            val t = lastSounded.get(pc)
            if (t != Long.MIN_VALUE && nowNanos - t <= holdNanos) m = m or (1 shl pc)
        }
        return m
    }
}
