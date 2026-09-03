package com.donfluids.music.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class WavWriterTest {
    @Test
    fun roundTrip() {
        val file = File.createTempFile("livekey", ".wav")
        try {
            val samples = FloatArray(3000) { (0.5 * sin(2 * PI * 440 * it / 44100.0)).toFloat() }
            WavWriter(file, 44100).use { w ->
                w.write(samples, 1000)
                w.write(samples.copyOfRange(1000, 3000), 2000)
                assertEquals(3000L, w.sampleCount)
            }
            assertEquals(WavWriter.HEADER_SIZE + 6000L, file.length())
            val (rate, back) = WavWriter.read(file)
            assertEquals(44100, rate)
            assertEquals(3000, back.size)
            for (i in samples.indices) assertEquals(samples[i], back[i], 1f / 32768f)
        } finally {
            file.delete()
        }
    }
}
