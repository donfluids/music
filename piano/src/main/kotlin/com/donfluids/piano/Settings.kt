package com.donfluids.piano

import android.content.Context

/** Remembered between launches. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("minipiano", Context.MODE_PRIVATE)

    var baseOctave: Int
        get() = prefs.getInt("octave", 4)
        set(v) = prefs.edit().putInt("octave", v).apply()
    var sustain: Boolean
        get() = prefs.getBoolean("sustain", false)
        set(v) = prefs.edit().putBoolean("sustain", v).apply()
    var showNames: Boolean
        get() = prefs.getBoolean("names", true)
        set(v) = prefs.edit().putBoolean("names", v).apply()
    var tone: PianoSynth.Tone
        get() = runCatching { PianoSynth.Tone.valueOf(prefs.getString("tone", null) ?: "") }.getOrDefault(PianoSynth.Tone.PIANO)
        set(v) = prefs.edit().putString("tone", v.name).apply()
    /** Null means no scale highlighting. */
    var scale: Theory.Scale?
        get() = if (prefs.contains("scaleTonic")) Theory.Scale(prefs.getInt("scaleTonic", 0), prefs.getBoolean("scaleMinor", false)) else null
        set(v) = prefs.edit().apply {
            if (v == null) { remove("scaleTonic"); remove("scaleMinor") } else { putInt("scaleTonic", v.tonic); putBoolean("scaleMinor", v.minor) }
        }.apply()
    var bpm: Int
        get() = prefs.getInt("bpm", 100)
        set(v) = prefs.edit().putInt("bpm", v).apply()
    var beatsPerBar: Int
        get() = prefs.getInt("beats", 4)
        set(v) = prefs.edit().putInt("beats", v).apply()
}
