package dev.barkboard.ime.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import dev.barkboard.core.asr.EnergyVad
import dev.barkboard.core.asr.Rms
import dev.barkboard.core.text.TranscriptStabilizer
import dev.barkboard.ime.asr.AsrClient
import dev.barkboard.ime.asr.AsrResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Hold-to-talk capture: 16 kHz mono from `AudioRecord`, an energy gate to skip silence and to
 * auto-stop, a growing buffer decoded as partials, then one final decode.
 *
 * Partials are re-decodes of the whole utterance (the engine is an offline transducer, not a
 * streaming one), so only text that extends what is already committed is ever appended.
 */
class VoiceDictation(
    private val context: Context,
    private val asr: AsrClient,
    private val listener: Listener,
) {

    interface Listener {
        /** Text to append to the field. */
        fun onText(text: String)

        fun onState(state: State)

        /** Nothing can be transcribed; [reason] is one of the REASON_* constants. */
        fun onUnavailable(reason: String)
    }

    enum class State { LISTENING, FINISHING, IDLE }

    var isRunning: Boolean = false
        private set

    private val stabilizer = TranscriptStabilizer()
    private val samples = FloatArray(MAX_SECONDS * SAMPLE_RATE)
    private val frame = ShortArray(FRAME_SAMPLES)

    @Volatile
    private var sampleCount = 0

    @Volatile
    private var capturing = false

    @Volatile
    private var acceptingPartials = false

    private var recorder: AudioRecord? = null
    private var job: Job? = null

    fun start(): Boolean {
        if (isRunning) return true

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onUnavailable(REASON_PERMISSION)
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            listener.onUnavailable(REASON_RECORDER)
            return false
        }

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer * 2, FRAME_SAMPLES * 4),
            )
        }.getOrNull()

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            listener.onUnavailable(REASON_RECORDER)
            return false
        }

        val started = runCatching { record.startRecording() }.isSuccess
        if (!started) {
            record.release()
            listener.onUnavailable(REASON_RECORDER)
            return false
        }

        recorder = record
        sampleCount = 0
        stabilizer.reset()
        capturing = true
        acceptingPartials = true
        isRunning = true

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        job = scope.launch { captureLoop(record) }
        scope.launch { partialLoop() }

        listener.onState(State.LISTENING)
        return true
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        capturing = false
        acceptingPartials = false
        listener.onState(State.FINISHING)

        val record = recorder
        recorder = null
        runCatching { record?.stop() }
        runCatching { record?.release() }

        val runningJob = job
        job = null

        val count = sampleCount
        val audio = if (count >= MIN_SAMPLES) samples.copyOf(count) else FloatArray(0)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runningJob?.cancel()

            if (audio.isNotEmpty()) {
                when (val result = asr.decode(audio)) {
                    is AsrResult.Text -> {
                        // Bark 3.0 Clean has already dropped fillers and false starts.
                        val append = stabilizer.finish(result.text)
                        if (append.isNotEmpty()) listener.onText(append)
                    }

                    AsrResult.NotReady -> listener.onUnavailable(REASON_MODEL)
                    is AsrResult.Failed -> {
                        Log.w(TAG, "final decode failed: ${result.message}")
                        listener.onUnavailable(REASON_ASR)
                    }
                }
            }
            listener.onState(State.IDLE)
        }
    }

    private fun captureLoop(record: AudioRecord) {
        val vad = EnergyVad()
        try {
            while (capturing) {
                val read = record.read(frame, 0, frame.size)
                if (read <= 0) continue

                val now = SystemClock.uptimeMillis()
                vad.process(Rms.of(frame, 0, read), now)
                append(frame, read)

                if (vad.shouldAutoStop(now) || sampleCount >= samples.size) {
                    stop()
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture stopped: ${e.message}")
            stop()
        }
    }

    private suspend fun partialLoop() {
        while (capturing) {
            delay(PARTIAL_INTERVAL_MS)
            if (!acceptingPartials) continue

            val count = sampleCount
            if (count < SAMPLE_RATE / 2) continue
            // Past ~30 s the decode stops being worth the latency; the final pass covers it.
            if (count > PARTIAL_LIMIT_SAMPLES) continue

            val window = FloatArray(count)
            System.arraycopy(samples, 0, window, 0, count)

            val result = asr.decode(window)
            if (!acceptingPartials) return
            if (result is AsrResult.Text) {
                val append = stabilizer.offer(result.text)
                if (append.isNotEmpty()) listener.onText(append)
            }
        }
    }

    private fun append(source: ShortArray, read: Int) {
        val existing = sampleCount
        val room = samples.size - existing
        if (room <= 0) return
        val copy = min(read, room)
        for (i in 0 until copy) {
            samples[existing + i] = source[i] / 32768f
        }
        sampleCount = existing + copy
    }

    companion object {
        private const val TAG = "VoiceDictation"

        const val REASON_PERMISSION = "permission"
        const val REASON_RECORDER = "recorder"
        const val REASON_MODEL = "model"
        const val REASON_ASR = "asr"

        const val SAMPLE_RATE = 16_000
        private const val MAX_SECONDS = 60
        private const val FRAME_SAMPLES = 1_600 // 100 ms
        private const val MIN_SAMPLES = SAMPLE_RATE / 4
        private const val PARTIAL_INTERVAL_MS = 700L
        private const val PARTIAL_LIMIT_SAMPLES = SAMPLE_RATE * 30
    }
}
