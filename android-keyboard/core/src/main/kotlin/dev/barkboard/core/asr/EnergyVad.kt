package dev.barkboard.core.asr

/**
 * Energy gate with hysteresis, used to skip silence and to auto-stop an utterance.
 *
 * Hysteresis matters: a single threshold makes the gate chatter on the quiet tail of a
 * word, and every chatter would cut the utterance.
 */
class EnergyVad(
    private val openThreshold: Float = 0.012f,
    private val closeThreshold: Float = 0.008f,
    private val silenceHoldMs: Long = 700L,
) {
    enum class State { SILENCE, SPEECH }

    var state: State = State.SILENCE
        private set

    /** Timestamp of the last frame above [closeThreshold]. */
    var lastVoiceMs: Long = -1L
        private set

    var speechMs: Long = 0L
        private set

    private var lastFrameMs: Long = -1L

    fun process(rms: Float, nowMs: Long): State {
        val delta = if (lastFrameMs < 0L) 0L else (nowMs - lastFrameMs).coerceAtLeast(0L)
        lastFrameMs = nowMs

        when (state) {
            State.SILENCE -> if (rms >= openThreshold) {
                state = State.SPEECH
                lastVoiceMs = nowMs
                speechMs += delta
            }

            State.SPEECH -> {
                if (rms >= closeThreshold) {
                    lastVoiceMs = nowMs
                    speechMs += delta
                } else if (shouldAutoStop(nowMs)) {
                    state = State.SILENCE
                }
            }
        }
        return state
    }

    /** True once the speaker has been quiet long enough to end the utterance. */
    fun shouldAutoStop(nowMs: Long): Boolean =
        state == State.SPEECH && lastVoiceMs >= 0L && nowMs - lastVoiceMs >= silenceHoldMs

    fun reset() {
        state = State.SILENCE
        lastVoiceMs = -1L
        speechMs = 0L
        lastFrameMs = -1L
    }
}

/** Root-mean-square of 16-bit PCM, normalised to 0..1. */
object Rms {

    fun of(samples: ShortArray, from: Int = 0, to: Int = samples.size): Float {
        val end = to.coerceAtMost(samples.size)
        val start = from.coerceAtLeast(0)
        if (end <= start) return 0f
        var sum = 0.0
        for (i in start until end) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return kotlin.math.sqrt(sum / (end - start)).toFloat()
    }
}
