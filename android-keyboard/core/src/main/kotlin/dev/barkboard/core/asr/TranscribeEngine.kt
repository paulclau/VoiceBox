package dev.barkboard.core.asr

/**
 * One utterance in, text out. Implemented by the native Bark 3.0 engine and by the stub the
 * app falls back to when the model or the native library is missing.
 */
interface TranscribeEngine {

    val name: String

    /** True when the engine can transcribe; false keeps the mic key in its "not ready" state. */
    fun load(): Boolean

    /** 16 kHz mono floats in -1..1, already padded with the leading silence. */
    fun transcribe(samples: FloatArray): String

    fun close()
}
