package dev.barkboard.ime.model

import android.content.Context
import dev.barkboard.core.model.BarkModel
import dev.barkboard.core.model.ModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the pinned model files, resuming partial transfers and verifying each one against
 * its published SHA-256. An unverified file is deleted rather than kept, so the engine never
 * loads a corrupt encoder.
 *
 * Play Asset Delivery is the intended shipping path (685 MB does not belong in an APK); this
 * downloader covers sideload builds and development.
 */
class ModelRepository(private val context: Context) {

    class Status(val total: Int, val verified: Int) {
        val ready: Boolean get() = total in 1..verified
    }

    val directory: File = directory(context)

    fun status(): Status {
        pruneSuperseded()
        val missing = BarkModel.unverifiedFiles(directory)
        return Status(total = BarkModel.FILES.size, verified = BarkModel.FILES.size - missing.size)
    }

    /** Frees the model directory of a build that pinned a different (e.g. verbatim) export. */
    private fun pruneSuperseded() {
        val modelsRoot = directory.parentFile ?: return
        BarkModel.legacyDirectories(modelsRoot).forEach { stale ->
            if (stale.isDirectory) runCatching { stale.deleteRecursively() }
        }
    }

    suspend fun download(
        onProgress: (fileName: String, writtenBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("could not create ${directory.absolutePath}")
            }

            for (file in BarkModel.FILES) {
                val target = File(directory, file.name)
                if (BarkModel.verify(target, file.sha256)) {
                    onProgress(file.name, file.approxBytes, file.approxBytes)
                    continue
                }
                fetchWithRetries(file, target, onProgress)
                if (!BarkModel.verify(target, file.sha256)) {
                    target.delete()
                    throw IOException("checksum mismatch for ${file.name}")
                }
            }
        }
    }

    private fun fetchWithRetries(
        file: ModelFile,
        target: File,
        onProgress: (String, Long, Long) -> Unit,
    ) {
        var lastError: IOException? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                fetch(file, target, onProgress)
                return
            } catch (e: IOException) {
                lastError = e
                if (attempt < ATTEMPTS - 1) {
                    target.delete() // restart this file rather than resume a bad prefix
                }
            }
        }
        throw lastError ?: IOException("could not download ${file.name}")
    }

    private fun fetch(
        file: ModelFile,
        target: File,
        onProgress: (String, Long, Long) -> Unit,
    ) {
        val existing = if (target.isFile) target.length() else 0L
        val connection = (URL(BarkModel.urlFor(file)).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for ${file.name}")

            val resume = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (existing > 0L && !resume) target.delete()

            var written = if (resume) existing else 0L
            connection.inputStream.use { input ->
                FileOutputStream(target, resume).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(file.name, written, file.approxBytes)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ATTEMPTS = 3
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000

        fun directory(context: Context): File = File(context.filesDir, "models/${BarkModel.ID}")
    }
}
