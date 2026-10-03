package com.donfluids.piano

/** A chord the band can play: root pitch class and quality. */
data class BandChord(val root: Int, val quality: Quality) {
    enum class Quality(val suffix: String, val intervals: IntArray) {
        MAJOR("", intArrayOf(0, 4, 7)),
        MINOR("m", intArrayOf(0, 3, 7)),
        DIMINISHED("dim", intArrayOf(0, 3, 6)),
        DOMINANT7("7", intArrayOf(0, 4, 7, 10)),
        MINOR7("m7", intArrayOf(0, 3, 7, 10)),
        MAJOR7("maj7", intArrayOf(0, 4, 7, 11)),
        SUS4("sus4", intArrayOf(0, 5, 7)),
    }

    val pitchClasses: List<Int> get() = quality.intervals.map { Math.floorMod(root + it, 12) }
    val third: Int get() = quality.intervals[1]
    val fifth: Int get() = quality.intervals[2]
    val seventh: Int get() = if (quality.intervals.size > 3) quality.intervals[3] else 12 // octave when the chord has no seventh
    fun name(flats: Boolean = false) = (if (flats) FLAT else SHARP)[Math.floorMod(root, 12)] + quality.suffix

    companion object {
        private val SHARP = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val FLAT = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
        private val BLACK = setOf(1, 3, 6, 8, 10)

        /**
         * Reads a chord from the keys held in the chord zone, the way an arranger keyboard does.
         * Three or more keys: the chord they spell. One key: a major chord. Two keys: the higher
         * is the root; a white key just below it makes a seventh, a black key a minor chord
         * (single-finger rules), while a third or fifth below spells the triad directly.
         */
        fun recognise(midis: Collection<Int>): BandChord? {
            if (midis.isEmpty()) return null
            val sorted = midis.distinct().sorted()
            if (sorted.size == 1) return BandChord(Math.floorMod(sorted[0], 12), Quality.MAJOR)
            if (sorted.size == 2) {
                val (lo, hi) = sorted
                val gap = hi - lo
                return when {
                    gap in 1..2 -> BandChord(Math.floorMod(hi, 12), if (Math.floorMod(lo, 12) in BLACK) Quality.MINOR else Quality.DOMINANT7)
                    gap % 12 == 3 -> BandChord(Math.floorMod(lo, 12), Quality.MINOR)
                    gap % 12 == 4 -> BandChord(Math.floorMod(lo, 12), Quality.MAJOR)
                    gap % 12 == 7 || gap % 12 == 5 -> BandChord(Math.floorMod(lo, 12), Quality.MAJOR)
                    else -> BandChord(Math.floorMod(lo, 12), Quality.MAJOR)
                }
            }
            val pcs = sorted.map { Math.floorMod(it, 12) }.toSet()
            // Try every held note as the root, preferring the lowest key, and the fullest match.
            var best: BandChord? = null
            var bestScore = -1
            for ((rank, m) in sorted.withIndex()) {
                val root = Math.floorMod(m, 12)
                val rel = pcs.map { Math.floorMod(it - root, 12) }.toSet()
                for (q in Quality.entries) {
                    val chordSet = q.intervals.toSet()
                    if (!chordSet.containsAll(rel)) continue
                    // Full matches beat subsets; a lower root beats a higher one; fewer extensions beat more.
                    val score = (if (rel == chordSet) 100 else 50) - rank * 5 - q.intervals.size
                    if (score > bestScore) { bestScore = score; best = BandChord(root, q) }
                }
            }
            return best ?: BandChord(Math.floorMod(sorted[0], 12), Quality.MAJOR)
        }
    }
}

enum class Drum { KICK, SNARE, HAT, OPEN_HAT, SHAKER, RIM, CRASH }

/** What the bass plays at a step, relative to the current chord. */
enum class BassDegree { ROOT, THIRD, FIFTH, SIXTH, SEVENTH, OCTAVE, FIFTH_BELOW }

/** How the comping instrument voices a hit. */
enum class Voicing { FULL, UPPER, ARP0, ARP1, ARP2, ARP3, POWER }

class DrumHit(val step: Int, val drum: Drum, val velocity: Float)
class BassNote(val step: Int, val degree: BassDegree, val lengthSteps: Int, val velocity: Float = 0.9f)
class CompHit(val step: Int, val lengthSteps: Int, val voicing: Voicing = Voicing.FULL, val velocity: Float = 0.7f)

/**
 * One bar of accompaniment. [beatsPerBar] and [stepsPerBeat] define a grid of 16th notes
 * (or 8ths in compound time); [swing] delays the off-beat 8ths by that fraction of a beat.
 */
class Style(
    val id: String,
    val name: String,
    val beatsPerBar: Int,
    val stepsPerBeat: Int,
    val defaultBpm: Int,
    val label: String,
    val swing: Float = 0f,
    /** Instrument for the comping part. */
    val compTone: PianoSynth.Tone = PianoSynth.Tone.ELECTRIC,
    val drums: List<DrumHit>,
    val bass: List<BassNote>,
    val comp: List<CompHit>,
) {
    val stepsPerBar get() = beatsPerBar * stepsPerBeat

    companion object {
        private fun hat(vararg steps: Int, v: Float = 0.5f) = steps.map { DrumHit(it, Drum.HAT, v) }
        private fun kick(vararg steps: Int, v: Float = 1f) = steps.map { DrumHit(it, Drum.KICK, v) }
        private fun snare(vararg steps: Int, v: Float = 0.9f) = steps.map { DrumHit(it, Drum.SNARE, v) }
        private fun shaker(vararg steps: Int, v: Float = 0.35f) = steps.map { DrumHit(it, Drum.SHAKER, v) }
        private fun rim(vararg steps: Int, v: Float = 0.6f) = steps.map { DrumHit(it, Drum.RIM, v) }
        private fun crash(vararg steps: Int, v: Float = 0.7f) = steps.map { DrumHit(it, Drum.CRASH, v) }

        val WORSHIP_SLOW = Style(
            "worship_slow", "Worship Slow", 4, 4, 72, "4/4",
            drums = kick(0, 8) + rim(4, 12) + hat(0, 2, 4, 6, 8, 10, 12, 14, v = 0.3f) + shaker(1, 3, 5, 7, 9, 11, 13, 15, v = 0.15f),
            bass = listOf(BassNote(0, BassDegree.ROOT, 12), BassNote(12, BassDegree.FIFTH, 4, 0.7f)),
            comp = listOf(CompHit(0, 16, Voicing.FULL, 0.6f), CompHit(8, 8, Voicing.UPPER, 0.4f)),
        )
        val WORSHIP_68 = Style(
            "worship_68", "Worship 6/8", 2, 6, 52, "6/8",
            drums = kick(0, 6) + rim(6, v = 0.5f) + hat(0, 2, 4, 6, 8, 10, v = 0.35f) + DrumHit(10, Drum.OPEN_HAT, 0.3f).let { listOf(it) },
            bass = listOf(BassNote(0, BassDegree.ROOT, 6), BassNote(6, BassDegree.FIFTH, 4, 0.7f), BassNote(10, BassDegree.OCTAVE, 2, 0.6f)),
            comp = listOf(
                CompHit(0, 2, Voicing.ARP0, 0.6f), CompHit(2, 2, Voicing.ARP1, 0.5f), CompHit(4, 2, Voicing.ARP2, 0.5f),
                CompHit(6, 2, Voicing.ARP3, 0.55f), CompHit(8, 2, Voicing.ARP2, 0.5f), CompHit(10, 2, Voicing.ARP1, 0.5f),
            ),
        )
        val ROCK_8 = Style(
            "rock_8", "8-Beat Rock", 4, 4, 120, "4/4",
            drums = kick(0, 8, 10) + snare(4, 12) + hat(0, 2, 4, 6, 8, 10, 12, 14, v = 0.6f) + hat(0, 8, v = 0.8f),
            bass = listOf(
                BassNote(0, BassDegree.ROOT, 2), BassNote(2, BassDegree.ROOT, 2, 0.7f), BassNote(4, BassDegree.ROOT, 2), BassNote(6, BassDegree.FIFTH, 2, 0.8f),
                BassNote(8, BassDegree.ROOT, 2), BassNote(10, BassDegree.ROOT, 2, 0.7f), BassNote(12, BassDegree.OCTAVE, 2, 0.8f), BassNote(14, BassDegree.FIFTH, 2, 0.8f),
            ),
            comp = listOf(CompHit(0, 3, Voicing.FULL, 0.8f), CompHit(4, 3, Voicing.UPPER, 0.6f), CompHit(6, 2, Voicing.UPPER, 0.5f), CompHit(8, 3, Voicing.FULL, 0.8f), CompHit(12, 3, Voicing.UPPER, 0.6f), CompHit(14, 2, Voicing.UPPER, 0.5f)),
        )
        val POWER_ROCK = Style(
            "power_rock", "Power Rock", 4, 4, 152, "4/4", compTone = PianoSynth.Tone.ORGAN,
            drums = crash(0) + kick(0, 3, 6, 8, 11, 14) + snare(4, 12, v = 1f) + hat(0, 2, 4, 6, 8, 10, 12, 14, v = 0.75f) + hat(0, 4, 8, 12, v = 0.9f),
            bass = listOf(
                BassNote(0, BassDegree.ROOT, 2, 1f), BassNote(2, BassDegree.ROOT, 2, 0.85f), BassNote(4, BassDegree.ROOT, 2, 1f), BassNote(6, BassDegree.ROOT, 2, 0.85f),
                BassNote(8, BassDegree.ROOT, 2, 1f), BassNote(10, BassDegree.OCTAVE, 2, 0.85f), BassNote(12, BassDegree.ROOT, 2, 1f), BassNote(14, BassDegree.FIFTH, 2, 0.85f),
            ),
            comp = listOf(
                CompHit(0, 2, Voicing.POWER, 0.9f), CompHit(2, 2, Voicing.POWER, 0.7f), CompHit(4, 2, Voicing.POWER, 0.9f), CompHit(6, 2, Voicing.POWER, 0.7f),
                CompHit(8, 2, Voicing.POWER, 0.9f), CompHit(10, 2, Voicing.POWER, 0.7f), CompHit(12, 2, Voicing.POWER, 0.9f), CompHit(14, 2, Voicing.POWER, 0.7f),
            ),
        )
        val POP_16 = Style(
            "pop_16", "Pop 16-Beat", 4, 4, 100, "4/4",
            drums = kick(0, 6, 8) + snare(4, 12) + (0 until 16).map { DrumHit(it, Drum.HAT, if (it % 4 == 0) 0.6f else if (it % 2 == 0) 0.4f else 0.25f) },
            bass = listOf(BassNote(0, BassDegree.ROOT, 4), BassNote(6, BassDegree.ROOT, 2, 0.7f), BassNote(8, BassDegree.FIFTH, 4, 0.8f), BassNote(14, BassDegree.ROOT, 2, 0.7f)),
            comp = listOf(CompHit(0, 6, Voicing.FULL, 0.7f), CompHit(6, 2, Voicing.UPPER, 0.5f), CompHit(8, 6, Voicing.UPPER, 0.6f), CompHit(14, 2, Voicing.UPPER, 0.5f)),
        )
        val GOSPEL_SHUFFLE = Style(
            "gospel_shuffle", "Gospel Shuffle", 4, 4, 112, "4/4", swing = 0.33f,
            drums = kick(0, 8) + snare(4, 12) + hat(0, 2, 4, 6, 8, 10, 12, 14, v = 0.55f),
            bass = listOf(BassNote(0, BassDegree.ROOT, 4), BassNote(4, BassDegree.THIRD, 4, 0.8f), BassNote(8, BassDegree.FIFTH, 4, 0.8f), BassNote(12, BassDegree.SIXTH, 4, 0.8f)),
            comp = listOf(CompHit(2, 2, Voicing.UPPER, 0.5f), CompHit(4, 3, Voicing.FULL, 0.8f), CompHit(10, 2, Voicing.UPPER, 0.5f), CompHit(12, 3, Voicing.FULL, 0.8f)),
        )
        val WALTZ = Style(
            "waltz", "Waltz", 3, 4, 96, "3/4",
            drums = kick(0) + rim(4, 8, v = 0.5f) + hat(0, 4, 8, v = 0.4f),
            bass = listOf(BassNote(0, BassDegree.ROOT, 4), BassNote(4, BassDegree.FIFTH, 4, 0.6f), BassNote(8, BassDegree.FIFTH, 4, 0.6f)),
            comp = listOf(CompHit(4, 3, Voicing.UPPER, 0.6f), CompHit(8, 3, Voicing.UPPER, 0.6f)),
        )
        val ALL = listOf(WORSHIP_SLOW, WORSHIP_68, ROCK_8, POWER_ROCK, POP_16, GOSPEL_SHUFFLE, WALTZ)
        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: WORSHIP_SLOW
    }
}
