package dev.barkboard.ime.asr

import android.content.Context
import android.util.Log
import dev.barkboard.core.asr.TranscribeEngine
import dev.barkboard.core.model.BarkModel
import dev.barkboard.ime.model.ModelRepository

/**
 * Picks the engine, or nothing. There is deliberately no fake engine: a stub that returns ""
 * would make an unconfigured keyboard look like it failed to hear you.
 */
object EngineFactory {

    fun create(context: Context): TranscribeEngine? {
        val directory = ModelRepository.directory(context)
        if (BarkModel.unverifiedFiles(directory).isNotEmpty()) {
            Log.i(TAG, "no verified model in $directory")
            return null
        }
        val engine = SherpaBarkEngine(directory)
        if (engine.load()) return engine
        engine.close()
        return null
    }

    private const val TAG = "EngineFactory"
}
