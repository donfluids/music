package com.donfluids.music.dsp

/**
 * A bundle of analysis settings for one kind of sound source. The pipeline is the same for
 * every preset; only the bands, weights and memory change.
 */
data class AnalysisPreset(
    val id: String,
    val title: String,
    val description: String,
    /** Frequency band the onset detector listens to. */
    val onsetMinHz: Double,
    val onsetMaxHz: Double,
    /** Log-compression strength of the onset detector; lower keeps loud/soft contrast. */
    val onsetGamma: Float,
    /** Extra weight given to onsets below this frequency (kick drum, bass). 0 disables. */
    val onsetLowBandMaxHz: Double,
    val onsetLowBandWeight: Float,
    /** Frequency band the key finder reads. */
    val chromaMinHz: Double,
    val chromaMaxHz: Double,
    /** Peaks above this frequency count less, to keep overtones from blurring the key. */
    val chromaRollOffHz: Double,
    /** How many seconds of sound the key estimate remembers. */
    val keyMemorySeconds: Double,
    val tempoPriorCenterBpm: Double,
    val tempoPriorWidthOctaves: Double,
    /** Beats needed before a time signature is shown. */
    val meterMinBeats: Int,
    /** Find the key from the sung melody (pitch-tracked notes) rather than from chroma alone. */
    val melodicKey: Boolean = false,
    /**
     * Whether the screen shows a tempo and time signature read from the sound. Drums and strums
     * give a pulse the app can follow; singing alone rarely does, so with this off the player
     * taps the tempo in instead.
     */
    val readsBeat: Boolean = true,
    /**
     * The Beat This! size that follows the beat when the beat is set to Accurate; null for a
     * preset that does not read the beat. Android uses the small one for Guitar too (the iPhone
     * uses the full one there): the full one costs a phone CPU two to three times as much.
     */
    val beatModel: BeatThisModel? = null,
) {
    companion object {
        /** Drums, guitar, keys and voices through a monitor. Kick and snare drive the beat. */
        val BAND = AnalysisPreset(
            id = "band",
            title = "Band",
            description = "Drums, guitar, keys and voices. The kick and snare set the beat.",
            onsetMinHz = 30.0, onsetMaxHz = 8000.0, onsetGamma = 20f,
            onsetLowBandMaxHz = 250.0, onsetLowBandWeight = 1.5f,
            chromaMinHz = 55.0, chromaMaxHz = 3000.0, chromaRollOffHz = 1000.0,
            keyMemorySeconds = 8.0,
            tempoPriorCenterBpm = 120.0, tempoPriorWidthOctaves = 0.55,
            meterMinBeats = 8,
            beatModel = BeatThisModel.SMALL,
        )

        /** Guitar-led with no drums: strums are the beat, string overtones are ignored. */
        val GUITAR = AnalysisPreset(
            id = "guitar",
            title = "Guitar",
            description = "Guitar and voices, no drums. Strums set the beat.",
            onsetMinHz = 60.0, onsetMaxHz = 6000.0, onsetGamma = 10f,
            onsetLowBandMaxHz = 0.0, onsetLowBandWeight = 0f,
            chromaMinHz = 70.0, chromaMaxHz = 2500.0, chromaRollOffHz = 800.0,
            keyMemorySeconds = 8.0,
            tempoPriorCenterBpm = 120.0, tempoPriorWidthOctaves = 0.55,
            meterMinBeats = 8,
            beatModel = BeatThisModel.SMALL,
        )

        /** Singing with little or no rhythm section: syllables are the only onsets. */
        val VOICES = AnalysisPreset(
            id = "voices",
            title = "Voices",
            description = "Mostly singing. The key comes from the melody, and you tap the tempo.",
            onsetMinHz = 150.0, onsetMaxHz = 5000.0, onsetGamma = 10f,
            onsetLowBandMaxHz = 0.0, onsetLowBandWeight = 0f,
            chromaMinHz = 80.0, chromaMaxHz = 2000.0, chromaRollOffHz = 1000.0,
            keyMemorySeconds = 10.0,
            tempoPriorCenterBpm = 120.0, tempoPriorWidthOctaves = 0.55,
            meterMinBeats = 12,
            melodicKey = true,
            readsBeat = false,
        )

        val ALL = listOf(BAND, GUITAR, VOICES)

        fun byId(id: String?): AnalysisPreset = ALL.firstOrNull { it.id == id } ?: BAND
    }
}
