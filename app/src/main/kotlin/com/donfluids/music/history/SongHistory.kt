package com.donfluids.music.history

import com.donfluids.music.UiState
import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/** One song as LiveKey heard it. [key] is the concert key; [transpose] is what the player had set. */
data class SongRecord(
    val atMillis: Long,
    val key: Key,
    val transpose: Int = 0,
    val bpm: Int? = null,
    /** The tempo was tapped in by the player rather than heard. */
    val bpmTapped: Boolean = false,
    val meter: String? = null,
    val seconds: Int = 0,
    val presetId: String = "",
) {
    companion object {
        /** Shorter than this is a false start, not a song. */
        const val MIN_SECONDS = 15

        /**
         * What to save for the song in [state], or null when nothing worth keeping was heard:
         * no key, or too short. The key is the one on screen: held, or else the centre.
         */
        fun from(state: UiState, nowMillis: Long): SongRecord? {
            val a = state.analysis
            val key = state.heldKey ?: a.displayKey ?: return null
            if (a.secondsHeard < MIN_SECONDS) return null
            val tapped = !state.preset.readsBeat
            val bpm = if (tapped) state.tapBpm else a.bpm?.takeIf { a.tempoLocked }?.times(state.tempoMultiplier)
            return SongRecord(
                atMillis = nowMillis,
                key = key,
                transpose = state.transpose,
                bpm = bpm?.roundToInt(),
                bpmTapped = tapped && bpm != null,
                meter = if (state.preset.readsBeat) a.meterLabel else null,
                seconds = a.secondsHeard.toInt(),
                presetId = state.preset.id,
            )
        }
    }
}

/**
 * The saved song list, newest first, kept in one small JSON file in the app's private storage.
 * Not thread-safe: the caller keeps every call on one thread.
 */
class SongHistory(private val file: File, private val limit: Int = 500) {

    private var songs: List<SongRecord>? = null

    fun all(): List<SongRecord> = songs ?: read().also { songs = it }

    /** Adds [record] in time order, so an undone delete goes back where it was. */
    fun add(record: SongRecord): List<SongRecord> =
        write((all().filterNot { it.atMillis == record.atMillis } + record).sortedByDescending { it.atMillis }.take(limit))

    fun remove(atMillis: Long): List<SongRecord> = write(all().filterNot { it.atMillis == atMillis })

    fun clear(): List<SongRecord> = write(emptyList())

    private fun read(): List<SongRecord> {
        if (!file.exists()) return emptyList()
        // A damaged file loses the history rather than stopping the app.
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i -> runCatching { decode(array.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<SongRecord>): List<SongRecord> {
        songs = list
        val array = JSONArray()
        list.forEach { array.put(encode(it)) }
        // Write beside the file, then swap, so a crash mid-write never leaves half a file.
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        return list
    }

    private fun encode(r: SongRecord) = JSONObject().apply {
        put("at", r.atMillis)
        put("tonic", r.key.tonic)
        put("mode", r.key.mode.name)
        put("transpose", r.transpose)
        r.bpm?.let { put("bpm", it) }
        put("tapped", r.bpmTapped)
        r.meter?.let { put("meter", it) }
        put("seconds", r.seconds)
        put("preset", r.presetId)
    }

    private fun decode(o: JSONObject) = SongRecord(
        atMillis = o.getLong("at"),
        key = Key(Math.floorMod(o.getInt("tonic"), 12), Mode.valueOf(o.getString("mode"))),
        transpose = o.optInt("transpose", 0),
        bpm = if (o.has("bpm")) o.getInt("bpm") else null,
        bpmTapped = o.optBoolean("tapped", false),
        meter = if (o.has("meter")) o.getString("meter") else null,
        seconds = o.optInt("seconds", 0),
        presetId = o.optString("preset", ""),
    )
}
