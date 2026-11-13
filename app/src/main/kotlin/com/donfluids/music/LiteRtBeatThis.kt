package com.donfluids.music

import android.content.res.AssetManager
import com.donfluids.music.dsp.BeatLogits
import com.donfluids.music.dsp.BeatNetwork
import com.donfluids.music.dsp.BeatThis
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Beat This! through LiteRT, on the CPU with XNNPACK and one thread, so the time a window takes
 * is the CPU it costs (what Details shows, and what the slow-phone check reads). The model is the
 * graph of tools/beatthis/android/scripts/bt_export_gpu.py with FP16 weights (converted by
 * convert_tflite.py): input (1, 412, 128) log-mel frames, output (1, 2, 412) beat then downbeat
 * logits. It is memory-mapped from the APK, where it is stored uncompressed.
 *
 * Nothing is loaded until the first window: then, on the worker that runs the windows, the model
 * is mapped and the interpreter built. If that fails, [logits] answers null and the analyser goes
 * back to the standard tracker without a word, as when the network fails. Only ever called from
 * one thread at a time (the beat worker).
 */
class LiteRtBeatThis(private val assets: AssetManager, private val asset: String) : BeatNetwork {
    private var interpreter: Interpreter? = null
    private var broken = false
    private val input = ByteBuffer.allocateDirect(BeatThis.INPUT_FRAMES * BeatThis.MELS * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(2 * BeatThis.INPUT_FRAMES * 4).order(ByteOrder.nativeOrder())

    override fun logits(spect: FloatArray): BeatLogits? {
        if (spect.size != BeatThis.INPUT_FRAMES * BeatThis.MELS) return null
        val net = interpreter ?: load() ?: return null
        return try {
            input.rewind()
            input.asFloatBuffer().put(spect)
            output.rewind()
            net.run(input, output)
            val out = output.asFloatBuffer()
            out.rewind()
            val beat = FloatArray(BeatThis.INPUT_FRAMES)
            val downbeat = FloatArray(BeatThis.INPUT_FRAMES)
            out.get(beat)
            out.get(downbeat)
            BeatLogits(beat, downbeat)
        } catch (e: Exception) {
            null
        }
    }

    private fun load(): Interpreter? {
        if (broken) return null
        return try {
            val net = Interpreter(map(), Interpreter.Options().setNumThreads(1).setUseXNNPACK(true))
            val inShape = net.getInputTensor(0).shape()
            val outShape = net.getOutputTensor(0).shape()
            if (!inShape.contentEquals(intArrayOf(1, BeatThis.INPUT_FRAMES, BeatThis.MELS)) ||
                !outShape.contentEquals(intArrayOf(1, 2, BeatThis.INPUT_FRAMES))
            ) {
                net.close()
                broken = true
                return null
            }
            interpreter = net
            net
        } catch (e: Throwable) {
            // A missing asset, a phone without the native library, a model the runtime rejects.
            broken = true
            null
        }
    }

    private fun map(): MappedByteBuffer = assets.openFd(asset).use { fd ->
        FileInputStream(fd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /** Frees the interpreter; call on the thread that runs the windows. */
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
