package dev.barkboard.ime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.barkboard.core.model.BarkModel
import dev.barkboard.ime.asr.BarkAsrNative
import dev.barkboard.ime.model.ModelRepository
import dev.barkboard.ime.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Setup surface: enable the IME, grant the mic, fetch and verify the model, pick a theme.
 * Deliberately plain views: this is a configuration screen, not part of the typing experience.
 */
class SettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var themes: ThemeRepository
    private lateinit var models: ModelRepository

    private lateinit var modelStatus: TextView
    private lateinit var engineStatus: TextView
    private lateinit var themeStatus: TextView
    private lateinit var permissionButton: Button
    private lateinit var profilesButton: Button

    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themes = ThemeRepository(this)
        models = ModelRepository(this)
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        column.addView(heading(getString(R.string.app_name)))

        column.addView(
            action(getString(R.string.settings_enable)) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
        )
        column.addView(
            action(getString(R.string.settings_choose)) {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            },
        )

        permissionButton = action(getString(R.string.settings_permission)) { requestMicPermission() }
        column.addView(permissionButton)

        modelStatus = body("")
        engineStatus = body("")
        themeStatus = body("")
        column.addView(modelStatus)
        column.addView(engineStatus)
        column.addView(themeStatus)

        column.addView(
            action(getString(R.string.settings_download)) {
                if (!downloading) download()
            },
        )
        column.addView(action(getString(R.string.settings_theme_pick)) { pickTheme() })
        column.addView(action(getString(R.string.settings_layout_pick)) { pickLayout() })
        column.addView(
            action(getString(R.string.settings_appearance)) {
                startActivity(Intent(this, AppearanceActivity::class.java))
            },
        )
        profilesButton = action(getString(R.string.settings_profiles, themes.profiles().apps.size)) {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        column.addView(profilesButton)

        column.addView(
            body(getString(R.string.settings_language_note)).apply {
                setPadding(0, dp(24), 0, 0)
            },
        )
        column.addView(body(getString(R.string.settings_attribution)))

        val scroll = ScrollView(this)
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun refresh() {
        val status = models.status()
        modelStatus.text = if (status.ready) {
            getString(R.string.settings_model_ready)
        } else {
            getString(R.string.settings_model_missing, status.verified, status.total)
        }

        val engine = when {
            !status.ready -> getString(R.string.settings_model_missing, status.verified, status.total)
            BarkAsrNative.load() -> BarkModel.DISPLAY_NAME
            else -> "${BarkModel.DISPLAY_NAME} — native library missing in this build"
        }
        engineStatus.text = getString(R.string.settings_engine, engine)

        themeStatus.text = getString(
            R.string.settings_current,
            themes.themeName(themes.selectedThemeId()),
            themes.layoutName(themes.selectedLayoutId()),
        )
        profilesButton.text = getString(R.string.settings_profiles, themes.profiles().apps.size)

        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        permissionButton.text = getString(
            if (granted) R.string.settings_permission_granted else R.string.settings_permission,
        )
        permissionButton.isEnabled = !granted
    }

    private fun download() {
        downloading = true
        scope.launch {
            val result = models.download { name, written, total ->
                runOnUiThread {
                    val percent = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else 0
                    modelStatus.text = getString(R.string.settings_downloading, name, percent)
                }
            }
            downloading = false
            result.onFailure { error ->
                modelStatus.text = getString(R.string.settings_download_failed, error.message ?: "unknown")
            }
            result.onSuccess { refresh() }
        }
    }

    private fun pickTheme() {
        val ids = themes.availableThemes()
        if (ids.isEmpty()) {
            Toast.makeText(this, "No themes in this build", Toast.LENGTH_SHORT).show()
            return
        }

        // Built-in themes and the user's own edits live in the same list; a saved edit shadows
        // the built-in of the same id, so only one entry per id can appear.
        val labels = ids.map { id -> themes.themeName(id) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme_pick)
            .setItems(labels) { _, which ->
                themes.select(ids[which])
                refresh()
                announceNextOpen()
            }
            .show()
    }

    private fun pickLayout() {
        val ids = themes.availableLayouts()
        if (ids.isEmpty()) {
            Toast.makeText(this, "No layouts in this build", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = ids.map { id -> themes.layoutName(id) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_layout_pick)
            .setItems(labels) { _, which ->
                themes.selectLayout(ids[which])
                refresh()
                announceNextOpen()
            }
            .show()
    }

    private fun announceNextOpen() {
        Toast.makeText(this, getString(R.string.settings_applies_next), Toast.LENGTH_SHORT).show()
    }

    private fun requestMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            refresh()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQUEST) refresh()
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        setPadding(0, 0, 0, dp(16))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun action(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MIC_REQUEST = 1001
    }
}
