package dev.barkboard.ime.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import dev.barkboard.core.asr.PcmFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client side of the `:asr` process.
 *
 * Binding is started without blocking, and [decode] waits for the connection on the caller's
 * thread — dictation only ever calls it from a background thread, never the main one.
 */
class AsrClient(private val context: Context) {

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pending>()

    @Volatile
    private var messenger: Messenger? = null

    @Volatile
    private var connectLatch = CountDownLatch(0)

    /** False until `:asr` has answered once, so the first request can wait for the model load. */
    @Volatile
    private var served = false

    /** The warm-up request is sent once per connection; a dead process gets one on reconnect. */
    @Volatile
    private var warmSent = false

    private var binding = false
    private var released = false

    private val replyMessenger: Messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            onReply(message)
            true
        })
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            messenger = service?.let { Messenger(it) }
            connectLatch.countDown()
            sendWarm()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            messenger = null
            // A restarted process has to load the model again before it can answer.
            served = false
            warmSent = false
        }
    }

    /** Starts binding; never blocks. */
    fun connect() {
        if (released || messenger != null || binding) return
        synchronized(this) {
            if (released || messenger != null || binding) return
            val intent = Intent(context, AsrService::class.java)
            connectLatch = CountDownLatch(1)
            binding = runCatching {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
        }
    }

    /**
     * Asks `:asr` to load the engine now, so the first utterance does not pay for it. Never
     * blocks: if the service is not connected yet, the request goes out when it is.
     */
    fun warmUp() {
        if (released) return
        connect()
        sendWarm()
    }

    private fun sendWarm() {
        synchronized(this) {
            if (released || warmSent) return
            val service = messenger ?: return
            warmSent = runCatching {
                service.send(
                    Message.obtain(null, AsrProtocol.MSG_WARM).apply { replyTo = replyMessenger },
                )
            }.isSuccess
        }
    }

    fun decode(samples: FloatArray): AsrResult {
        if (samples.isEmpty()) return AsrResult.Text("")
        connect()
        if (!awaitConnection()) return AsrResult.Failed("not_connected")

        val service = messenger ?: return AsrResult.Failed("not_connected")
        val requestId = nextId.getAndIncrement()
        val file = File(File(context.cacheDir, "asr"), "$requestId.pcm")
        PcmFile.write(file, samples)

        val request = Pending()
        pending[requestId] = request

        val message = Message.obtain(null, AsrProtocol.MSG_DECODE, requestId, 0).apply {
            data = Bundle().apply { putString(AsrProtocol.ARG_AUDIO_PATH, file.absolutePath) }
            replyTo = replyMessenger
        }

        return try {
            service.send(message)
            val timeout = if (served) DECODE_TIMEOUT_MS else FIRST_DECODE_TIMEOUT_MS
            if (!request.latch.await(timeout, TimeUnit.MILLISECONDS)) {
                AsrResult.Failed("timeout")
            } else {
                val error = request.error
                when {
                    error == null -> AsrResult.Text(request.text.orEmpty())
                    error == AsrProtocol.ERROR_MODEL_UNAVAILABLE -> AsrResult.NotReady
                    else -> AsrResult.Failed(error)
                }
            }
        } catch (e: Exception) {
            AsrResult.Failed(e.message ?: "send_failed")
        } finally {
            pending.remove(requestId)
            runCatching { file.delete() }
        }
    }

    fun release() {
        synchronized(this) {
            if (released) return
            released = true
            if (binding) {
                runCatching { context.unbindService(connection) }
            }
            binding = false
            messenger = null
            pending.clear()
        }
    }

    private fun awaitConnection(): Boolean {
        if (messenger != null) return true
        runCatching { connectLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        return messenger != null
    }

    private fun onReply(message: Message) {
        // Checked before the pending lookup: a warm reply answers to no request id.
        if (message.what == AsrProtocol.MSG_WARMED) {
            served = message.arg1 == 1
            return
        }

        val request = pending[message.arg1] ?: return
        served = true
        when (message.what) {
            AsrProtocol.MSG_RESULT -> request.text = message.data?.getString(AsrProtocol.ARG_TEXT)
            AsrProtocol.MSG_ERROR -> request.error = message.data?.getString(AsrProtocol.ARG_MESSAGE)
        }
        request.latch.countDown()
    }

    private class Pending {
        val latch = CountDownLatch(1)

        @Volatile
        var text: String? = null

        @Volatile
        var error: String? = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000L

        /** An 8 s utterance is ~140 ms of decode on desktop; phones are slower but not this slow. */
        const val DECODE_TIMEOUT_MS = 20_000L

        /** The first request also loads ~630 MB of int8 weights off flash inside `:asr`. */
        const val FIRST_DECODE_TIMEOUT_MS = 60_000L
    }
}
