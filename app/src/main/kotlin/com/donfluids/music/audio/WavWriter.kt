package com.donfluids.music.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Streams 16-bit mono PCM to a WAV file through a buffered stream; the header is patched on
 * close. Plain JVM so it can be unit-tested. Not thread-safe: use from one thread.
 */
class WavWriter(val file: File, private val sampleRate: Int) : Closeable {
    private val out: BufferedOutputStream
    private val buffer = ByteArray(8192)
    private var bytesWritten = 0L
    private var closed = false

    /** Samples written so far. */
    val sampleCount: Long get() = bytesWritten / 2

    init {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(HEADER_SIZE)) // placeholder, patched on close
        }
        out = BufferedOutputStream(FileOutputStream(file, true), 64 * 1024)
    }

    /** Appends [count] samples in -1..1 from [samples]. */
    fun write(samples: FloatArray, count: Int) {
        var i = 0
        while (i < count) {
            val n = minOf(count - i, buffer.size / 2)
            for (j in 0 until n) {
                val v = (samples[i + j] * 32768f).toInt().coerceIn(-32768, 32767)
                buffer[2 * j] = (v and 0xFF).toByte()
                buffer[2 * j + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(buffer, 0, n * 2)
            bytesWritten += n * 2
            i += n
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        out.close()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(sampleRate, bytesWritten))
        }
    }

    companion object {
        const val HEADER_SIZE = 44

        fun header(sampleRate: Int, dataBytes: Long): ByteArray {
            val h = ByteArray(HEADER_SIZE)
            fun str(offset: Int, s: String) = s.forEachIndexed { i, c -> h[offset + i] = c.code.toByte() }
            fun int32(offset: Int, v: Long) { for (i in 0 until 4) h[offset + i] = ((v shr (8 * i)) and 0xFF).toByte() }
            fun int16(offset: Int, v: Int) { h[offset] = (v and 0xFF).toByte(); h[offset + 1] = ((v shr 8) and 0xFF).toByte() }
            str(0, "RIFF"); int32(4, 36 + dataBytes); str(8, "WAVE")
            str(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1)
            int32(24, sampleRate.toLong()); int32(28, sampleRate.toLong() * 2); int16(32, 2); int16(34, 16)
            str(36, "data"); int32(40, dataBytes)
            return h
        }

        /** Reads a 16-bit PCM WAV back into floats (first channel only). */
        fun read(file: File): Pair<Int, FloatArray> {
            val bytes = file.readBytes()
            require(bytes.size >= HEADER_SIZE && String(bytes, 0, 4) == "RIFF") { "Not a WAV file: $file" }
            var pos = 12
            var sampleRate = 0
            var channels = 1
            var bits = 16
            var format = 1
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = le32(bytes, pos + 4)
                if (id == "fmt ") {
                    format = le16(bytes, pos + 8)
                    channels = le16(bytes, pos + 10)
                    sampleRate = le32(bytes, pos + 12)
                    bits = le16(bytes, pos + 22)
                } else if (id == "data") {
                    require(format == 1 && bits == 16) { "Only 16-bit PCM WAV is supported: $file" }
                    val count = size / 2 / channels
                    val out = FloatArray(count)
                    for (i in 0 until count) {
                        val p = pos + 8 + i * 2 * channels
                        out[i] = le16(bytes, p).toShort() / 32768f
                    }
                    return sampleRate to out
                }
                pos += 8 + size + (size and 1)
            }
            error("No data chunk in $file")
        }

        private fun le16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, p: Int) = le16(b, p) or (le16(b, p + 2) shl 16)
    }
}
