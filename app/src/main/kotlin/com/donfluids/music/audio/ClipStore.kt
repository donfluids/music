package com.donfluids.music.audio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Where clips live, plus labelling, zipping and deleting. */
class ClipStore(context: Context) {
    /** App-specific external storage: visible in a file manager under Android/data, no permission needed. */
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "clips")
    private val shareDir = File(context.cacheDir, "share")

    fun clipNames(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".wav") }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    /** Removes leftovers of clips interrupted by a crash. Call once at startup, off the main thread. */
    fun cleanUp() {
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun count(): Int = clipNames().size

    /** Writes the user's answer for one clip into its sidecar. Empty fields are left out. */
    fun saveLabels(clipName: String, key: String, tempo: String, meter: String, notes: String) {
        val file = File(dir, "$clipName.json")
        if (!file.exists()) return
        val json = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        val labels = JSONObject()
        if (key.isNotBlank()) labels.put("key", key.trim())
        if (tempo.isNotBlank()) labels.put("tempo", tempo.trim())
        if (meter.isNotBlank()) labels.put("meter", meter.trim())
        if (notes.isNotBlank()) labels.put("notes", notes.trim())
        json.put("labels", labels)
        file.writeText(json.toString(2))
    }

    /** Packs every clip into one zip for sharing. Slow: call off the main thread. */
    fun zipAll(): File {
        shareDir.mkdirs()
        val zip = File(shareDir, "livekey-clips.zip")
        ZipOutputStream(FileOutputStream(zip).buffered()).use { out ->
            dir.listFiles { f -> f.name.endsWith(".wav") || f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
                out.putNextEntry(ZipEntry(f.name))
                FileInputStream(f).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        shareDir.listFiles()?.forEach { it.delete() }
    }
}
