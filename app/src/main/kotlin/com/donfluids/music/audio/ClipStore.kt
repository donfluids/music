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
    /** Internal, app-private storage: other apps cannot read the clips; Share is the only way out. */
    val dir: File = File(context.filesDir, "clips")
    private val shareDir = File(context.cacheDir, "share")
    /** Where an earlier version kept clips; moved into [dir] once by [cleanUp]. */
    private val legacyDir: File? = context.getExternalFilesDir(null)?.let { File(it, "clips") }

    fun clipNames(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".wav") }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    /**
     * Removes leftovers of clips interrupted by a crash. Call once at startup, off the main
     * thread. Files still being written in the last two minutes are left alone.
     */
    fun cleanUp() {
        dir.mkdirs()
        val cutoff = System.currentTimeMillis() - 2 * 60 * 1000
        dir.listFiles { f -> f.name.endsWith(".part") && f.lastModified() < cutoff }?.forEach { it.delete() }
        // Bring clips recorded by an earlier version in from shared storage, then remove it.
        val legacy = legacyDir ?: return
        legacy.listFiles()?.forEach { f ->
            val target = File(dir, f.name)
            if (!f.renameTo(target)) runCatching { f.copyTo(target, overwrite = true); f.delete() }
        }
        legacy.delete()
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
