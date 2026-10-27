package dev.barkboard.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import dev.barkboard.core.layout.KeyKind
import dev.barkboard.core.layout.KeySpec
import dev.barkboard.core.model.BarkModel
import dev.barkboard.core.profile.AppProfile
import dev.barkboard.ime.asr.AsrClient
import dev.barkboard.ime.model.ModelRepository
import dev.barkboard.ime.theme.ThemeRepository
import dev.barkboard.ime.ui.CursorAnchorMapper
import dev.barkboard.ime.ui.KeyboardView
import dev.barkboard.ime.voice.VoiceDictation
import java.io.File

/**
 * The IME. Owns the input connection and the editor's caret, and swaps between typing and
 * voice dictation. All text reaches the field through [InputConnection]; the view decides
 * *when* a tapped character is committed (on landing) and the dictation path commits as
 * partials stabilise.
 */
class KeyboardService : InputMethodService(), KeyboardView.Listener {

    private var themeRepository: ThemeRepository? = null
    private var keyboardView: KeyboardView? = null
    private var asrClient: AsrClient? = null
    private var dictation: VoiceDictation? = null
    private var editorAction: Int = EditorInfo.IME_ACTION_NONE
    private var appliedThemeId: String? = null
    private var appliedLayoutId: String? = null

    /**
     * The package of the field we are attached to, i.e. which app the user is typing in. It is
     * read when the view is built and re-read on every input view start, because the same view is
     * reused as the user moves between apps.
     */
    private var editorPackage: String? = null

    /** The theme revision this view was built from, so an edit is picked up without a new id. */
    private var appliedRevision: Int = -1

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // Called before the view is created, so the first view already belongs to the right app.
        editorPackage = info?.packageName
    }

    override fun onCreateInputView(): View {
        val repository = themeRepository ?: ThemeRepository(this).also { themeRepository = it }
        val bundle = repository.loadFor(editorPackage)
        bundle.problems.forEach { Log.w(TAG, it) }
        announceProfile(bundle.profile, bundle.theme.id, bundle.layout.id)

        val view = KeyboardView(this, bundle.appearance)
        view.listener = this
        view.setSpec(bundle.layout, bundle.gestures)
        keyboardView = view

        // A flight lane is only visible if the IME window itself is see-through; the keys paint
        // their own background either way.
        if (bundle.appearance.lanePx > 0f) {
            val imeWindow = getWindow()?.window
            if (imeWindow != null) {
                imeWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            } else {
                Log.d(TAG, "no IME window to clear; the flight lane will show the window background")
            }
        }
        appliedThemeId = bundle.theme.id
        appliedLayoutId = bundle.layout.id
        appliedRevision = repository.revision()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorAction = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE

        // Ask the editor to report its caret so the flying glyph has somewhere to land.
        runCatching {
            currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        }.onFailure { Log.d(TAG, "cursor updates unavailable: ${it.message}") }

        // The app can change while the keyboard stays up, so the package is taken from this
        // call's EditorInfo, not from whatever was here when the view was built.
        info?.packageName?.let { editorPackage = it }

        // 630 MB of weights take seconds to read, so the engine starts loading now rather than on
        // the first mic press.
        warmUpAsr()

        // Pick up a theme, layout or profile chosen while Settings was open. Appearance is baked
        // into the view at construction, so a changed theme means a new input view.
        themeRepository?.let { repository ->
            val bundle = repository.loadFor(editorPackage)
            bundle.problems.forEach { Log.w(TAG, it) }
            // The editor saves under the same id it started from, so the revision — not the id —
            // is what says "this changed since the view was built". A profile switch between two
            // apps shows up as a different id here, which is exactly what needs a rebuild.
            val changed = bundle.theme.id != appliedThemeId ||
                bundle.layout.id != appliedLayoutId ||
                repository.revision() != appliedRevision
            if (changed || keyboardView == null) {
                announceProfile(bundle.profile, bundle.theme.id, bundle.layout.id)
                setInputView(onCreateInputView())
            } else {
                keyboardView?.setSpec(bundle.layout, bundle.gestures)
            }
        }
    }

    private fun announceProfile(profile: AppProfile?, themeId: String, layoutId: String) {
        if (profile == null) return
        Log.i(TAG, "profile ${profile.packageName}: theme $themeId, layout $layoutId")
    }

    /**
     * The flight lane is part of the IME window but must behave like it is not: the app may lay
     * out its content under it, and touches there belong to the app, not to us.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)

        val view = keyboardView ?: return
        val lane = view.flightLaneHeightPx
        if (lane <= 0 || view.height <= lane) return

        outInsets.contentTopInsets = lane
        outInsets.visibleTopInsets = lane
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, lane, view.width, view.height)
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: android.view.inputmethod.CursorAnchorInfo?) {
        val view = keyboardView ?: return
        view.setLandingTarget(CursorAnchorMapper.toView(cursorAnchorInfo, view))
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDictation()
        runCatching { currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR) }
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopDictation()
        asrClient?.release()
        asrClient = null
        super.onDestroy()
    }

    // ---- KeyboardView.Listener --------------------------------------------------------

    override fun onText(text: String) {
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)
    }

    override fun onKey(kind: KeyKind, spec: KeySpec) {
        when (kind) {
            KeyKind.BACKSPACE -> currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
            KeyKind.ENTER -> performEnter()
            KeyKind.SPACE -> onText(" ")
            KeyKind.MIC -> toggleDictation()
            KeyKind.LANGUAGE -> showLanguagePicker()
            KeyKind.MACRO -> onText(spec.output.orEmpty())
            KeyKind.EMOJI, KeyKind.SHIFT, KeyKind.CHAR -> Unit
        }
    }

    override fun onGesture(action: String, spec: KeySpec) {
        when (action) {
            "voice" -> toggleDictation()
            "language" -> showLanguagePicker()
            "deleteWord" -> deleteWord()
            "dismissKeyboard" -> requestHideSelf(0)
            "space" -> onText(" ")
            else -> Log.d(TAG, "unbound gesture action: $action")
        }
    }

    override fun onHaptic(kind: String) {
        val view = keyboardView ?: return
        val constant = when (kind) {
            "none" -> return
            "light" -> android.view.HapticFeedbackConstants.KEYBOARD_TAP
            "tick" -> android.view.HapticFeedbackConstants.CLOCK_TICK
            else -> android.view.HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant)
    }

    // ---- actions ---------------------------------------------------------------------

    private fun performEnter() {
        val connection = currentInputConnection ?: return
        val action = editorAction
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.performEditorAction(action)
            return
        }
        if (sendDefaultEditorAction(true)) return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun deleteWord() {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        if (before.isEmpty()) {
            connection.deleteSurroundingTextInCodePoints(1, 0)
            return
        }
        val trimmed = before.trimEnd()
        val cut = trimmed.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
        val length = if (cut < 0) before.length else before.length - (trimmed.length - cut - 1).coerceAtLeast(0)
        connection.deleteSurroundingTextInCodePoints(length.coerceAtLeast(1), 0)
    }

    private fun showLanguagePicker() {
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        manager.showInputMethodPicker()
    }

    // ---- dictation -------------------------------------------------------------------

    /**
     * Starts the `:asr` model load while the user is typing. Skipped unless a model is on disk:
     * the presence check is a stat, not the SHA-256 pass the `:asr` process does, and without it an
     * unconfigured keyboard would spawn an idle process every time it opened.
     *
     * The trade is memory for latency: a keyboard that only ever types still leaves ~1.2 GB
     * resident in `:asr` until Android reclaims it. That is the point of the warm-up, but it is why
     * it is guarded by a real model being present.
     */
    private fun warmUpAsr() {
        val directory = ModelRepository.directory(this)
        if (BarkModel.FILES.any { !File(directory, it.name).isFile }) return

        val client = asrClient ?: AsrClient(this).also { created ->
            asrClient = created
        }
        client.warmUp()
    }

    private fun toggleDictation() {
        val running = dictation?.isRunning == true
        if (running) {
            stopDictation()
            return
        }
        if (!hasMicPermission()) {
            startActivity(
                Intent(this, GrantActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            return
        }

        val client = asrClient ?: AsrClient(this).also { created ->
            created.connect()
            asrClient = created
        }

        val session = VoiceDictation(this, client, dictationListener)
        dictation = session
        keyboardView?.setRecording(true)
        if (!session.start()) {
            keyboardView?.setRecording(false)
            dictation = null
        }
    }

    private fun stopDictation() {
        dictation?.stop()
        dictation = null
        keyboardView?.setRecording(false)
    }

    private val dictationListener = object : VoiceDictation.Listener {
        override fun onText(text: String) {
            if (text.isEmpty()) return
            currentInputConnection?.commitText(text, 1)
        }

        override fun onState(state: VoiceDictation.State) {
            if (state == VoiceDictation.State.IDLE) {
                keyboardView?.setRecording(false)
            }
        }

        override fun onUnavailable(reason: String) {
            keyboardView?.setRecording(false)
            val message = when (reason) {
                VoiceDictation.REASON_PERMISSION -> getString(R.string.grant_rationale)
                VoiceDictation.REASON_MODEL -> getString(R.string.settings_model_missing, 0, 4)
                else -> reason
            }
            Toast.makeText(this@KeyboardService, message, Toast.LENGTH_LONG).show()
            if (reason == VoiceDictation.REASON_MODEL) {
                startActivity(
                    Intent(this@KeyboardService, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "KeyboardService"
    }
}
