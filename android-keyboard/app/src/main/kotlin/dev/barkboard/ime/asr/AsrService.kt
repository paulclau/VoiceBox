package dev.barkboard.ime.asr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import dev.barkboard.core.asr.PcmFile
import dev.barkboard.core.asr.TranscribeEngine
import java.io.File

/**
 * Hosts the speech engine in its own process. The int8 Bark 3.0 Clean model peaks around 1.15 GB of
 * resident memory, so keeping it here means an out-of-memory kill takes down dictation rather
 * than the keyboard, and the weights stay warm between utterances.
 */
class AsrService : Service() {

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger

    private var engine: TranscribeEngine? = null
    private var engineChecked = false

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("bark-asr").also { it.start() }
        handler = Handler(worker.looper) { message ->
            when (message.what) {
                AsrProtocol.MSG_DECODE -> handleDecode(message)
                AsrProtocol.MSG_WARM -> warm(message)
            }
            true
        }
        messenger = Messenger(handler)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        engine?.close()
        engine = null
        worker.quitSafely()
        super.onDestroy()
    }

    /** Called only on the worker thread. */
    private fun engine(): TranscribeEngine? {
        if (!engineChecked) {
            engineChecked = true
            engine = EngineFactory.create(this)
            Log.i(TAG, "engine: ${engine?.name ?: "unavailable"}")
        }
        return engine
    }

    /**
     * Loads the engine ahead of the first utterance. Runs on the worker thread, so the keyboard
     * keeps typing while 630 MB of weights are read.
     */
    private fun warm(message: Message) {
        val loaded = engine() != null
        Log.i(TAG, "warm: ${if (loaded) "engine ready" else "no engine"}")
        send(
            message.replyTo,
            Message.obtain(null, AsrProtocol.MSG_WARMED, if (loaded) 1 else 0, 0),
        )
    }

    private fun handleDecode(message: Message) {
        val requestId = message.arg1
        val replyTo = message.replyTo
        val path = message.data?.getString(AsrProtocol.ARG_AUDIO_PATH)

        if (path == null) {
            replyError(replyTo, requestId, "no_audio")
            return
        }

        val active = engine()
        if (active == null) {
            replyError(replyTo, requestId, AsrProtocol.ERROR_MODEL_UNAVAILABLE)
            return
        }

        try {
            val audio = PcmFile.read(File(path))
            if (audio.isEmpty()) {
                replyText(replyTo, requestId, "")
                return
            }
            // Clean decodes the utterance as recorded: no padding in front, no filter after.
            // The model drops fillers and false starts itself.
            val text = active.transcribe(audio).replace("<unk>", "").trim()
            replyText(replyTo, requestId, text)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: ${e.message}")
            replyError(replyTo, requestId, e.message ?: "decode_failed")
        } finally {
            runCatching { File(path).delete() }
        }
    }

    private fun replyText(replyTo: Messenger?, requestId: Int, text: String) {
        val data = Bundle().apply { putString(AsrProtocol.ARG_TEXT, text) }
        send(replyTo, Message.obtain(null, AsrProtocol.MSG_RESULT, requestId, 0).apply { this.data = data })
    }

    private fun replyError(replyTo: Messenger?, requestId: Int, message: String) {
        val data = Bundle().apply { putString(AsrProtocol.ARG_MESSAGE, message) }
        send(replyTo, Message.obtain(null, AsrProtocol.MSG_ERROR, requestId, 0).apply { this.data = data })
    }

    private fun send(replyTo: Messenger?, message: Message) {
        if (replyTo == null) return
        try {
            replyTo.send(message)
        } catch (e: RemoteException) {
            Log.w(TAG, "reply failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "AsrService"
    }
}
