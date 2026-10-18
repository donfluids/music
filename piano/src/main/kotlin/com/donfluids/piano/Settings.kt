package com.donfluids.piano

import android.content.Context

/** Remembered between launches. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("minipiano", Context.MODE_PRIVATE)

    /** Keyboard window position in white keys from C1; the old whole-octave setting is honoured once. */
    var scroll: Float
        get() = if (prefs.contains("scroll")) prefs.getFloat("scroll", 21f) else KeyboardLayout.scrollForOctave(prefs.getInt("octave", 4))
        set(v) = prefs.edit().putFloat("scroll", v).apply()
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
    var volume: Float
        get() = prefs.getFloat("volume", 0.8f)
        set(v) = prefs.edit().putFloat("volume", v).apply()
    var reverb: Boolean
        get() = prefs.getBoolean("reverb", true)
        set(v) = prefs.edit().putBoolean("reverb", v).apply()
    /** Extra loudness through Android's loudness enhancer; may distort on some phones. */
    var boost: Boolean
        get() = prefs.getBoolean("boost", false)
        set(v) = prefs.edit().putBoolean("boost", v).apply()
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()
    var bandStyle: Style
        get() = Style.byId(prefs.getString("bandStyle", null))
        set(v) = prefs.edit().putString("bandStyle", v.id).apply()
    var bandOn: Boolean
        get() = prefs.getBoolean("bandOn", false)
        set(v) = prefs.edit().putBoolean("bandOn", v).apply()
    var bandVolume: Float
        get() = prefs.getFloat("bandVolume", 0.8f)
        set(v) = prefs.edit().putFloat("bandVolume", v).apply()
    /** How much better a chord must fit the sung notes before the band leaves the current one, 0.05..0.6. */
    var switchMargin: Float
        get() = prefs.getFloat("switchMargin", 0.2f)
        set(v) = prefs.edit().putFloat("switchMargin", v).apply()
    var fills: Boolean
        get() = prefs.getBoolean("fills", true)
        set(v) = prefs.edit().putBoolean("fills", v).apply()
    var followChords: Boolean
        get() = prefs.getBoolean("followChords", true)
        set(v) = prefs.edit().putBoolean("followChords", v).apply()
    var bpm: Int
        get() = prefs.getInt("bpm", 100)
        set(v) = prefs.edit().putInt("bpm", v).apply()
    var beatsPerBar: Int
        get() = prefs.getInt("beats", 4)
        set(v) = prefs.edit().putInt("beats", v).apply()
}
