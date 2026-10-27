@file:JvmName("BarkAsr")
package dev.barkboard.ime.asr

/**
 * JNI entry points for the sherpa-onnx speech engine. The native side is not vendored in this
 * repository (it needs the sherpa-onnx prebuilts); see docs/native-engine.md for the contract.
 *
 * These throw UnsatisfiedLinkError if called without the library, so always go through
 * [BarkAsrNative.load] first.
 */
external fun barkAsrCreate(modelDir: String, threads: Int): Long

external fun barkAsrTranscribe(handle: Long, samples: FloatArray): String

external fun barkAsrDestroy(handle: Long)

object BarkAsrNative {

    private var attempted = false
    private var loaded = false

    /** True once `libbark_asr.so` is available. Safe to call on every launch. */
    fun load(): Boolean {
        if (attempted) return loaded
        attempted = true
        loaded = try {
            System.loadLibrary("bark_asr")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        return loaded
    }

    val isLoaded: Boolean get() = loaded
}
