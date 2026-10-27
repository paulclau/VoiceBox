package dev.barkboard.core.model

import java.io.File
import java.security.MessageDigest

data class ModelFile(
    val name: String,
    val sha256: String,
    /** Size on disk as published, used only for progress estimates. */
    val approxBytes: Long,
)

/**
 * Pinned Underdog Bark 3.0 **Clean** (int8, sherpa-onnx layout).
 *
 * Clean is Bark 3.0 Plus with the cleanup trained in: it drops "um" and "uh", filler phrases,
 * false starts and repeated words, so dictation lands as readable text and the app runs no filler
 * filter of its own — a second filter could only start deleting words that were meant. The 4.08 %
 * WER on DisfluencySpeech is scored against cleaned references, which is why this build is the
 * right one for a keyboard and the verbatim one is not.
 *
 * The ONNX export is an unofficial third-party conversion (ConwayResearch/Underdog-Bark-3.0-Clean
 * at revision 6d34ed86, exported with transformers' ParakeetForTDT and quantized weight-only
 * int8), so every file is pinned by the SHA-256 published in its model card and verified after
 * download. An unverified model file is never loaded.
 *
 * Weights: CC-BY-4.0 — Conway Research (Underdog), derived from nvidia/parakeet-tdt-0.6b-v3
 * (c) NVIDIA Corporation and moondream/parakeet-ultra (c) Moondream. Attribution is a shipping
 * requirement, not a nicety.
 */
object BarkModel {

    const val ID = "bark-3.0-clean-int8"
    const val DISPLAY_NAME = "Underdog Bark 3.0 Clean (int8)"
    const val REPO = "velddev/Underdog-Bark-3.0-Clean-sherpa-onnx"
    val SOURCE_URL = "https://huggingface.co/$REPO/resolve/main"

    /** Superseded model directories, removed on launch so an upgrade frees ~685 MB. */
    val LEGACY_IDS = listOf("bark-3.0-int8")

    const val SAMPLE_RATE = 16_000

    /** What the model is good at, shown in Settings so the English-only limit is explicit. */
    const val LANGUAGE_NOTE = "English only"

    /**
     * Clean is fed the utterance as recorded: it was evaluated with no padding, unlike the
     * verbatim Bark 3.0 build, which wanted 0.25 s of silence in front of every clip.
     */
    val FILES: List<ModelFile> = listOf(
        ModelFile(
            name = "encoder.int8.onnx",
            sha256 = "dd8b0275d775ea664f0306764f80a6d9b357ed7d7a1fc15e8269b08b14e89c47",
            approxBytes = 630_700_000,
        ),
        ModelFile(
            name = "decoder.int8.onnx",
            sha256 = "8fb0c48ed66cbc43855912e2e1b75c50037e9d8d628eef11bcb5712b7eab7681",
            approxBytes = 47_700_000,
        ),
        ModelFile(
            name = "joiner.int8.onnx",
            sha256 = "2d9a8a895f38a2e132c5c4edf9de4af638c121664329f7a86c473190700c6378",
            approxBytes = 6_100_000,
        ),
        ModelFile(
            name = "tokens.txt",
            sha256 = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            approxBytes = 94_000,
        ),
    )

    val totalBytes: Long = FILES.fold(0L) { acc, file -> acc + file.approxBytes }

    fun urlFor(file: ModelFile): String = "$SOURCE_URL/${file.name}"

    fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (e: java.io.IOException) {
        null
    }

    fun verify(file: File, expected: String): Boolean =
        file.isFile && sha256(file)?.equals(expected, ignoreCase = true) == true

    /** Files that are missing or do not match their pin. Empty means the model is loadable. */
    fun unverifiedFiles(directory: File): List<ModelFile> =
        FILES.filter { !verify(File(directory, it.name), it.sha256) }

    /**
     * Directories under [modelsRoot] that a build pinning another model left behind. Removing one
     * is a handful of `unlink` calls even at 685 MB, so the caller may do it while showing status.
     */
    fun legacyDirectories(modelsRoot: File): List<File> =
        LEGACY_IDS.filter { it != ID }.map { File(modelsRoot, it) }
}
