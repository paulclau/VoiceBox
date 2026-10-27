package dev.barkboard.ime.asr

/** Message contract between the keyboard process and the `:asr` process. */
object AsrProtocol {
    const val MSG_DECODE = 1

    /** Load the engine now, before anyone asks for a transcript. */
    const val MSG_WARM = 2

    const val MSG_RESULT = 10
    const val MSG_ERROR = 11

    /** Answer to [MSG_WARM]; `arg1` is 1 when the engine is loaded and 0 when it could not be. */
    const val MSG_WARMED = 12

    const val ARG_REQUEST_ID = "request_id"
    const val ARG_AUDIO_PATH = "audio_path"
    const val ARG_TEXT = "text"
    const val ARG_MESSAGE = "message"

    /** The engine could not be loaded; the caller should send the user to Settings. */
    const val ERROR_MODEL_UNAVAILABLE = "model_unavailable"
}

sealed interface AsrResult {
    data class Text(val text: String) : AsrResult

    /** No verified model or native library: nothing to transcribe with. */
    data object NotReady : AsrResult

    data class Failed(val message: String) : AsrResult
}
