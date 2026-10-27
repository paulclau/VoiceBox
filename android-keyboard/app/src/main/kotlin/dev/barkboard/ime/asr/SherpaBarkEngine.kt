package dev.barkboard.ime.asr

import android.util.Log
import dev.barkboard.core.asr.TranscribeEngine
import dev.barkboard.core.model.BarkModel
import java.io.File

/**
 * Underdog Bark 3.0 Clean (int8) through sherpa-onnx: encoder, decoder and joiner graphs plus the
 * token table, all pinned by SHA-256. Loading is refused unless every file verifies, because a
 * half-downloaded encoder produces plausible-looking garbage rather than an error.
 */
class SherpaBarkEngine(private val modelDir: File) : TranscribeEngine {

    override val name: String = BarkModel.DISPLAY_NAME

    private var handle: Long = 0L

    override fun load(): Boolean {
        if (BarkModel.unverifiedFiles(modelDir).isNotEmpty()) {
            Log.w(TAG, "refusing to load: model files missing or unverified in $modelDir")
            return false
        }
        if (!BarkAsrNative.load()) {
            Log.w(TAG, "libbark_asr.so not available in this build")
            return false
        }

        // 0.6B int8 on a phone: more threads than this buys thermal problems, not speed.
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        handle = runCatching { barkAsrCreate(modelDir.absolutePath, threads) }.getOrDefault(0L)
        if (handle == 0L) Log.w(TAG, "barkAsrCreate failed")
        return handle != 0L
    }

    override fun transcribe(samples: FloatArray): String {
        if (handle == 0L) return ""
        return runCatching { barkAsrTranscribe(handle, samples) }
            .getOrElse {
                Log.w(TAG, "transcribe failed: ${it.message}")
                ""
            }
            .replace("<unk>", "")
            .trim()
    }

    override fun close() {
        if (handle != 0L) {
            runCatching { barkAsrDestroy(handle) }
            handle = 0L
        }
    }

    private companion object {
        const val TAG = "SherpaBarkEngine"
    }
}
