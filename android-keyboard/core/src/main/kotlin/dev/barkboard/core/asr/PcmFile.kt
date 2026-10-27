package dev.barkboard.core.asr

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Moves audio between the keyboard process and the `:asr` process as raw 16-bit PCM.
 *
 * A Binder transaction is capped at about 1 MB, so a long utterance cannot travel in a
 * Bundle; a file has no such limit and keeps the interface trivial.
 */
object PcmFile {

    fun write(file: File, samples: FloatArray, from: Int = 0, count: Int = samples.size - from) {
        val start = from.coerceAtLeast(0)
        val end = (start + count).coerceAtMost(samples.size)
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(file.outputStream(), 1 shl 16)).use { out ->
            for (i in start until end) {
                val clamped = samples[i].coerceIn(-1f, 1f)
                out.writeShort((clamped * 32767f).toInt())
            }
        }
    }

    fun read(file: File): FloatArray {
        if (!file.isFile) return FloatArray(0)
        val bytes = file.length()
        if (bytes < 2) return FloatArray(0)
        val out = FloatArray((bytes / 2).toInt())
        var index = 0
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            while (index < out.size) {
                out[index] = input.readShort() / 32768f
                index++
            }
        }
        return out
    }

    fun durationSeconds(file: File, sampleRate: Int): Float =
        if (!file.isFile || sampleRate <= 0) 0f else (file.length() / 2f) / sampleRate
}
