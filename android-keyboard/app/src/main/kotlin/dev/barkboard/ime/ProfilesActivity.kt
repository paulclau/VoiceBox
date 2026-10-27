package dev.barkboard.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.barkboard.core.profile.AppProfile
import dev.barkboard.core.theme.SpecResult
import dev.barkboard.ime.theme.ThemeRepository

/**
 * Per-app profiles: a theme and a layout per package.
 *
 * The list is the whole job — add an app, point it at a theme and/or a layout, remove it. What an
 * app does *not* set keeps following the global selection, so giving one app a different look does
 * not also freeze its layout.
 *
 * Apps are picked from the launcher list, because a package name is not something anyone should
 * have to remember, with a manual entry for the few apps that do not appear there.
 */
class ProfilesActivity : Activity() {

    private lateinit var themes: ThemeRepository
    private lateinit var list: LinearLayout
    private lateinit var warning: TextView
    private lateinit var defaults: TextView

    /** The open edit sheet, so a change can redraw it instead of leaving stale labels behind. */
    private var editor: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themes = ThemeRepository(this)
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        editor = null
        super.onDestroy()
    }

    // ---- UI ---------------------------------------------------------------------------

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        column.addView(title(getString(R.string.profiles_title)))
        column.addView(hint(getString(R.string.profiles_intro)))

        defaults = hint("")
        column.addView(defaults)
        column.addView(
            hint(getString(R.string.profiles_defaults_hint)).apply { setPadding(0, 0, 0, dp(12)) },
        )

        warning = hint("").apply { setTextColor(Color.parseColor("#FFFF6B6B")) }
        column.addView(warning)

        column.addView(action(getString(R.string.profiles_add)) { pickApp() })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)

        val scroll = ScrollView(this)
        scroll.addView(
            column,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return scroll
    }

    private fun refresh() {
        val defaultTheme = themes.selectedThemeId()
        val defaultLayout = themes.selectedLayoutId()

        defaults.text = getString(
            R.string.profiles_defaults,
            themes.themeName(defaultTheme),
            themes.layoutName(defaultLayout),
        )

        val problems = themes.profileProblems()
        warning.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        warning.text = if (problems.isEmpty()) "" else getString(R.string.profiles_broken, problems.first())

        renderRows(defaultTheme, defaultLayout)
    }

    private fun renderRows(defaultTheme: String, defaultLayout: String) {
        list.removeAllViews()
        val profiles = themes.profiles()

        if (profiles.apps.isEmpty()) {
            list.addView(hint(getString(R.string.profiles_none)))
            return
        }

        // Stored order = the order the user added them, which is the order to show them in.
        profiles.apps.forEach { profile ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(action(displayName(profile)) { openEditor(profile) })

            val themeId = profile.themeId?.takeIf { it.isNotBlank() } ?: defaultTheme
            val layoutId = profile.layoutId?.takeIf { it.isNotBlank() } ?: defaultLayout
            val summary = getString(
                R.string.profiles_summary,
                themes.themeName(themeId),
                themes.layoutName(layoutId),
            )
            val suffix = if (profile.overridesNothing()) " · ${getString(R.string.profiles_inherits)}" else ""
            row.addView(
                hint(summary + suffix).apply { setPadding(dp(4), 0, 0, dp(10)) },
            )

            list.addView(row)
        }
    }

    // ---- Editing ----------------------------------------------------------------------

    private fun openEditor(profile: AppProfile) {
        editor?.dismiss()

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        body.addView(hint(getString(R.string.profiles_edit_hint)))

        body.addView(action(themeLabel(profile)) { pickTheme(profile) })
        body.addView(action(layoutLabel(profile)) { pickLayout(profile) })
        body.addView(action(getString(R.string.profiles_remove)) { confirmRemove(profile) })

        editor = AlertDialog.Builder(this)
            .setTitle(displayName(profile))
            .setView(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun themeLabel(profile: AppProfile): String {
        val id = profile.themeId?.takeIf { it.isNotBlank() }
        return if (id == null) {
            getString(R.string.profiles_theme, getString(R.string.profiles_inherit))
        } else {
            getString(R.string.profiles_theme, themes.themeName(id))
        }
    }

    private fun layoutLabel(profile: AppProfile): String {
        val id = profile.layoutId?.takeIf { it.isNotBlank() }
        return if (id == null) {
            getString(R.string.profiles_layout, getString(R.string.profiles_inherit))
        } else {
            getString(R.string.profiles_layout, themes.layoutName(id))
        }
    }

    private fun pickTheme(profile: AppProfile) {
        val ids = themes.availableThemes()
        val labels = listOf(getString(R.string.profiles_inherit)) + ids.map { themes.themeName(it) }
        // Index 0 is the inherit option; everything after it is a theme id in the same order.
        val checked = ids.indexOf(profile.themeId).let { if (it < 0) 0 else it + 1 }

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_pick_theme)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                apply(profile.copy(themeId = if (which == 0) null else ids[which - 1]))
            }
            .show()
    }

    private fun pickLayout(profile: AppProfile) {
        val ids = themes.availableLayouts()
        val labels = listOf(getString(R.string.profiles_inherit)) + ids.map { themes.layoutName(it) }
        val checked = ids.indexOf(profile.layoutId).let { if (it < 0) 0 else it + 1 }

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_pick_layout)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                apply(profile.copy(layoutId = if (which == 0) null else ids[which - 1]))
            }
            .show()
    }

    /** Writes the profile and redraws the sheet over it, so the labels never go stale. */
    private fun apply(profile: AppProfile) {
        when (val result = themes.saveProfiles(themes.profiles().upsert(profile))) {
            is SpecResult.Ok -> {
                toast(getString(R.string.profiles_saved))
                refresh()
                openEditor(profile)
            }

            is SpecResult.Invalid -> toast(getString(R.string.profiles_failed, result.errors.first()))
        }
    }

    private fun confirmRemove(profile: AppProfile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profiles_remove_confirm, displayName(profile)))
            .setMessage(R.string.profiles_remove_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                when (val result = themes.saveProfiles(themes.profiles().remove(profile.packageName))) {
                    is SpecResult.Ok -> {
                        editor?.dismiss()
                        editor = null
                        toast(getString(R.string.profiles_removed))
                        refresh()
                    }

                    is SpecResult.Invalid -> toast(getString(R.string.profiles_failed, result.errors.first()))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Picking an app ---------------------------------------------------------------

    private fun pickApp() {
        val apps = installedApps()
        val labels = apps.map { it.second }.toMutableList<String>()
        labels += getString(R.string.profiles_type_package)

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_pick_app)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < apps.size) {
                    apply(AppProfile(packageName = apps[which].first, label = apps[which].second))
                } else {
                    typePackage()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun typePackage() {
        val field = EditText(this).apply {
            hint = getString(R.string.profiles_package_hint)
            setSingleLine()
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(hint(getString(R.string.profiles_package_help)))
            addView(field)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_type_package)
            .setView(body)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.profiles_package_blank))
                } else {
                    apply(AppProfile(packageName = name))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Launcher apps, deduplicated by package: one entry per app, not per activity. */
    private fun installedApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { packageManager.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())

        return resolved
            .map { info ->
                val packageName = info.activityInfo?.packageName.orEmpty()
                val label = info.loadLabel(packageManager)?.toString().orEmpty()
                packageName to label.ifBlank { packageName }
            }
            .filter { it.first.isNotEmpty() && it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun displayName(profile: AppProfile): String {
        val label = profile.label.ifBlank { installedLabel(profile.packageName) }
        return if (label.isBlank()) profile.packageName else "$label\n${profile.packageName}"
    }

    private fun installedLabel(packageName: String): String = runCatching {
        packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
    }.getOrDefault("")

    // ---- Small helpers ----------------------------------------------------------------

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        setPadding(0, 0, 0, dp(12))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(2), 0, dp(2))
        setTextColor(Color.parseColor("#FFB6C0CE"))
    }

    private fun action(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
