package dev.barkboard.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.barkboard.core.theme.ColorKit
import dev.barkboard.core.theme.ColorSpec
import dev.barkboard.core.theme.SpecLoader
import dev.barkboard.core.theme.SpecResult
import dev.barkboard.core.theme.Theme
import dev.barkboard.core.theme.ThemeLimits
import dev.barkboard.ime.theme.ThemeRepository
import dev.barkboard.ime.ui.ThemePreviewView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The look-and-feel editor.
 *
 * Every control writes into an in-memory [Theme] and the preview redraws from it, so nothing is
 * saved until Save. The draft is validated with the same [SpecLoader.validateTheme] the keyboard
 * uses, and Save is refused while it is invalid — the editor cannot produce a keyboard that will
 * not load, which is the whole point of having one.
 *
 * Editing a built-in theme saves over its id into app storage, which shadows the asset rather
 * than replacing it; Revert restores the version this screen opened with, and Duplicate makes a
 * copy under a fresh id so both survive.
 */
class AppearanceActivity : Activity() {

    private lateinit var themes: ThemeRepository
    private lateinit var preview: ThemePreviewView
    private lateinit var errorsView: TextView
    private lateinit var themeButton: Button
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var flightSwitch: Switch
    private lateinit var trailSwitch: Switch
    private lateinit var hapticPressButton: Button
    private lateinit var hapticLandButton: Button

    private lateinit var draft: Theme

    /** What Revert returns to: the theme as it was when this screen opened, or a fresh copy. */
    private lateinit var baseTheme: Theme

    private val colourButtons = LinkedHashMap<Palette, Button>()
    private val sliders = ArrayList<Slider>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themes = ThemeRepository(this)

        val loaded = themes.load().theme
        draft = loaded
        baseTheme = loaded

        setContentView(buildUi())
        render()
    }

    // ---- UI ---------------------------------------------------------------------------

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        column.addView(title(getString(R.string.appearance_title)))

        preview = ThemePreviewView(this).apply {
            setLayout(themes.load().layout)
            setTheme(draft)
        }
        column.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)),
        )
        column.addView(
            hint(getString(R.string.appearance_preview_hint)).apply {
                setPadding(0, dp(6), 0, dp(14))
            },
        )

        themeButton = action("") { pickBaseTheme() }
        column.addView(themeButton)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveButton = action(getString(R.string.appearance_save)) { save() }
        actions.addView(saveButton, weight())
        actions.addView(action(getString(R.string.appearance_duplicate)) { duplicate() }, weight())
        actions.addView(action(getString(R.string.appearance_revert)) { revert() }, weight())
        column.addView(actions)

        val files = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        files.addView(action(getString(R.string.appearance_import)) { importTheme() }, weight())
        files.addView(action(getString(R.string.appearance_export)) { exportTheme() }, weight())
        column.addView(files)

        deleteButton = action(getString(R.string.appearance_delete)) { confirmDelete() }
        column.addView(deleteButton)

        errorsView = hint("")
        errorsView.setTextColor(Color.parseColor("#FFFF6B6B"))
        column.addView(errorsView)

        // ---- Colours ----
        column.addView(section(getString(R.string.appearance_colours)))
        Palette.entries.forEach { slot ->
            val button = action(getString(slot.titleRes)) { pickColour(slot) }
            colourButtons[slot] = button
            column.addView(button)
        }

        // ---- Key shape ----
        column.addView(section(getString(R.string.appearance_keys)))
        addSlider(
            column,
            getString(R.string.appearance_key_radius),
            ThemeLimits.radiusDp.start,
            ThemeLimits.radiusDp.endInclusive,
            0,
            get = { it.key.radiusDp },
            set = { theme, value -> theme.copy(key = theme.key.copy(radiusDp = value)) },
        )
        addSlider(
            column,
            getString(R.string.appearance_key_gap),
            ThemeLimits.gapDp.start,
            ThemeLimits.gapDp.endInclusive,
            0,
            get = { it.key.gapDp },
            set = { theme, value -> theme.copy(key = theme.key.copy(gapDp = value)) },
        )
        addSlider(
            column,
            getString(R.string.appearance_label_size),
            ThemeLimits.labelSizeSp.start,
            ThemeLimits.labelSizeSp.endInclusive,
            0,
            get = { it.key.labelSizeSp },
            set = { theme, value -> theme.copy(key = theme.key.copy(labelSizeSp = value)) },
        )
        addSlider(
            column,
            getString(R.string.appearance_pressed_scale),
            ThemeLimits.pressedScale.start,
            ThemeLimits.pressedScale.endInclusive,
            2,
            get = { it.key.pressedScale },
            set = { theme, value -> theme.copy(key = theme.key.copy(pressedScale = value)) },
        )

        // ---- Flight ----
        column.addView(section(getString(R.string.appearance_flight)))
        flightSwitch = Switch(this).apply {
            text = getString(R.string.appearance_flight_enabled)
            setOnCheckedChangeListener { _, checked ->
                if (checked != draft.flight.enabled) {
                    update(draft.copy(flight = draft.flight.copy(enabled = checked)))
                }
            }
        }
        column.addView(flightSwitch)

        addSlider(
            column,
            getString(R.string.appearance_flight_duration),
            ThemeLimits.durationMs.start.toFloat(),
            ThemeLimits.durationMs.endInclusive.toFloat(),
            0,
            get = { it.flight.durationMs.toFloat() },
            set = { theme, value ->
                theme.copy(flight = theme.flight.copy(durationMs = value.roundToInt()))
            },
        )
        addSlider(
            column,
            getString(R.string.appearance_flight_arc),
            ThemeLimits.arc.start,
            ThemeLimits.arc.endInclusive,
            2,
            get = { it.flight.arc },
            set = { theme, value -> theme.copy(flight = theme.flight.copy(arc = value)) },
        )
        addSlider(
            column,
            getString(R.string.appearance_flight_start_scale),
            ThemeLimits.flightScale.start,
            ThemeLimits.flightScale.endInclusive,
            2,
            get = { it.flight.startScale },
            set = { theme, value -> theme.copy(flight = theme.flight.copy(startScale = value)) },
        )
        addSlider(
            column,
            getString(R.string.appearance_flight_end_scale),
            ThemeLimits.flightScale.start,
            ThemeLimits.flightScale.endInclusive,
            2,
            get = { it.flight.endScale },
            set = { theme, value -> theme.copy(flight = theme.flight.copy(endScale = value)) },
        )
        trailSwitch = Switch(this).apply {
            text = getString(R.string.appearance_flight_trail)
            setOnCheckedChangeListener { _, checked ->
                if (checked != draft.flight.trail) {
                    update(draft.copy(flight = draft.flight.copy(trail = checked)))
                }
            }
        }
        column.addView(trailSwitch)

        addSlider(
            column,
            getString(R.string.appearance_lane),
            ThemeLimits.laneDp.start,
            ThemeLimits.laneDp.endInclusive,
            0,
            get = { it.flight.laneDp },
            set = { theme, value -> theme.copy(flight = theme.flight.copy(laneDp = value)) },
        )
        column.addView(hint(getString(R.string.appearance_lane_warning)))

        // ---- Haptics ----
        column.addView(section(getString(R.string.appearance_haptics)))
        hapticPressButton = action("") { pickHaptic(landing = false) }
        hapticLandButton = action("") { pickHaptic(landing = true) }
        column.addView(hapticPressButton)
        column.addView(hapticLandButton)

        column.addView(
            hint(getString(R.string.settings_attribution)).apply { setPadding(0, dp(24), 0, 0) },
        )

        val scroll = ScrollView(this)
        scroll.addView(column)
        return scroll
    }

    /** One slider bound to one field of the theme, with the current value spelled out. */
    private inner class Slider(
        val min: Float,
        val max: Float,
        val decimals: Int,
        val get: (Theme) -> Float,
        val set: (Theme, Float) -> Theme,
    ) {
        lateinit var bar: SeekBar
        lateinit var value: TextView

        fun format(raw: Float): String =
            if (decimals == 0) raw.roundToInt().toString() else String.format(Locale.US, "%.${decimals}f", raw)

        fun progressFor(raw: Float): Int {
            if (max <= min) return 0
            return (((raw - min) / (max - min)) * STEPS).roundToInt().coerceIn(0, STEPS)
        }

        fun valueFor(progress: Int): Float = min + (max - min) * (progress.toFloat() / STEPS)
    }

    private fun addSlider(
        column: LinearLayout,
        label: String,
        min: Float,
        max: Float,
        decimals: Int,
        get: (Theme) -> Float,
        set: (Theme, Float) -> Theme,
    ) {
        val slider = Slider(min, max, decimals, get, set)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(hint(label), weight())
        slider.value = hint("")
        header.addView(slider.value)
        column.addView(header)

        slider.bar = SeekBar(this).apply {
            this.max = STEPS
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val raw = slider.valueFor(progress)
                    slider.value.text = slider.format(raw)
                    if (fromUser) update(slider.set(draft, raw))
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        column.addView(slider.bar)
        sliders += slider
    }

    // ---- State ------------------------------------------------------------------------

    private fun update(next: Theme) {
        draft = next
        render()
    }

    private fun render() {
        preview.setTheme(draft)

        themeButton.text = getString(
            R.string.appearance_theme,
            draft.name.ifBlank { draft.id },
        )

        colourButtons.forEach { (slot, button) ->
            val hex = colourOf(draft, slot)
            val colour = ColorSpec.parse(hex) ?: Color.TRANSPARENT
            button.text = String.format(
                Locale.US,
                "%s  %s",
                getString(slot.titleRes),
                hex,
            )
            button.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(colour)
                setStroke(dp(1), 0x33FFFFFF)
            }
            button.setTextColor(
                if (ColorKit.prefersDarkLabel(colour)) Color.BLACK else Color.WHITE,
            )
        }

        sliders.forEach { slider ->
            val raw = slider.get(draft)
            slider.bar.progress = slider.progressFor(raw)
            slider.value.text = slider.format(raw)
        }

        flightSwitch.isChecked = draft.flight.enabled
        trailSwitch.isChecked = draft.flight.trail
        hapticPressButton.text = getString(R.string.appearance_haptic_press, draft.haptics.press)
        hapticLandButton.text = getString(R.string.appearance_haptic_land, draft.haptics.land)

        val errors = SpecLoader.validateTheme(draft)
        saveButton.isEnabled = errors.isEmpty()
        errorsView.visibility = if (errors.isEmpty()) View.GONE else View.VISIBLE
        errorsView.text = if (errors.isEmpty()) "" else errors.joinToString("\n")

        // Only an edited theme can be deleted; a built-in can be shadowed but never removed.
        deleteButton.visibility = if (themes.isUserTheme(baseTheme.id)) View.VISIBLE else View.GONE
    }

    // ---- Actions ----------------------------------------------------------------------

    private fun save() {
        when (val result = themes.save(draft)) {
            is ThemeRepository.SaveResult.Ok -> {
                baseTheme = result.theme
                render()
                toast(getString(R.string.appearance_saved))
            }

            is ThemeRepository.SaveResult.Invalid -> {
                errorsView.visibility = View.VISIBLE
                errorsView.text = result.errors.joinToString("\n")
            }
        }
    }

    private fun revert() {
        update(baseTheme)
        toast(getString(R.string.appearance_reverted))
    }

    private fun duplicate() {
        val id = themes.newThemeId(draft.id.ifBlank { "custom" })
        val copy = draft.copy(id = id, name = "${draft.name.ifBlank { draft.id }} copy")
        baseTheme = copy
        update(copy)
        toast(getString(R.string.appearance_duplicated))
    }

    private fun pickBaseTheme() {
        val ids = themes.availableThemes()
        if (ids.isEmpty()) return
        val labels = ids.map { id -> themes.themeName(id) }
        AlertDialog.Builder(this)
            .setTitle(R.string.appearance_theme_pick)
            .setItems(labels.toTypedArray()) { _, which ->
                val id = ids[which]
                when (val result = themes.read(id)) {
                    is SpecResult.Ok -> {
                        baseTheme = result.value
                        update(result.value)
                    }

                    is SpecResult.Invalid -> toast(
                        getString(R.string.appearance_import_failed, result.errors.first()),
                    )
                }
            }
            .show()
    }

    private fun pickColour(slot: Palette) {
        val current = colourOf(draft, slot)
        val argb = ColorSpec.parse(current) ?: 0xFF000000.toInt()
        val hsl = ColorKit.toHsl(argb)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }

        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(argb)
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        body.addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        var hue = hsl.hue * 360f
        var saturation = hsl.saturation * 100f
        var lightness = hsl.lightness * 100f
        var alpha = ColorKit.alpha(argb)

        fun refresh() {
            val colour = ColorKit.toArgb(
                ColorKit.Hsl(hue / 360f, saturation / 100f, lightness / 100f),
                alpha,
            )
            (swatch.background as GradientDrawable).setColor(colour)
        }

        body.addView(sliderRow(getString(R.string.appearance_colour_hue), 0, 360, hue.roundToInt()) { value ->
            hue = value.toFloat()
            refresh()
        })
        body.addView(sliderRow(getString(R.string.appearance_colour_saturation), 0, 100, saturation.roundToInt()) { value ->
            saturation = value.toFloat()
            refresh()
        })
        body.addView(sliderRow(getString(R.string.appearance_colour_lightness), 0, 100, lightness.roundToInt()) { value ->
            lightness = value.toFloat()
            refresh()
        })
        body.addView(sliderRow(getString(R.string.appearance_colour_alpha), 0, 255, alpha) { value ->
            alpha = value
            refresh()
        })

        val scroll = ScrollView(this)
        scroll.addView(body)

        AlertDialog.Builder(this)
            .setTitle(getString(slot.titleRes))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val hex = ColorKit.toHex(
                    ColorKit.toArgb(ColorKit.Hsl(hue / 360f, saturation / 100f, lightness / 100f), alpha),
                )
                update(withColour(draft, slot, hex))

                // A key label that disappears into its key is the most common way to break a
                // theme by hand, so offer to fix the contrast instead of leaving it unreadable.
                if (slot == Palette.KEY && needsContrastFix()) {
                    suggestLabelColour()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun needsContrastFix(): Boolean {
        val key = ColorSpec.parse(draft.colors.key) ?: return false
        val label = ColorSpec.parse(draft.colors.label) ?: return false
        val (lighter, darker) = if (ColorKit.luminance(key) > ColorKit.luminance(label)) key to label else label to key
        val ratio = (ColorKit.luminance(lighter) + 0.05f) / (ColorKit.luminance(darker) + 0.05f)
        return ratio < 3f
    }

    private fun suggestLabelColour() {
        AlertDialog.Builder(this)
            .setTitle(R.string.appearance_contrast_title)
            .setMessage(R.string.appearance_contrast_message)
            .setPositiveButton(R.string.appearance_contrast_fix) { _, _ ->
                val key = ColorSpec.parse(draft.colors.key) ?: return@setPositiveButton
                val hex = if (ColorKit.prefersDarkLabel(key)) "#FF101010" else "#FFF5F7FA"
                update(draft.copy(colors = draft.colors.copy(label = hex)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A labelled integer slider inside the colour dialog. */
    private fun sliderRow(label: String, min: Int, max: Int, start: Int, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(hint(label), weight())
        val value = hint(start.toString())
        header.addView(value)
        row.addView(header)

        val bar = SeekBar(this).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = (start - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val actual = min + progress
                    value.text = actual.toString()
                    if (fromUser) onChange(actual)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        row.addView(bar)
        return row
    }

    private fun pickHaptic(landing: Boolean) {
        val kinds = ThemeLimits.hapticKinds.sorted().toTypedArray()
        val current = if (landing) draft.haptics.land else draft.haptics.press
        AlertDialog.Builder(this)
            .setTitle(if (landing) R.string.appearance_haptic_land_pick else R.string.appearance_haptic_press_pick)
            .setSingleChoiceItems(kinds, kinds.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                val chosen = kinds[which]
                update(
                    if (landing) {
                        draft.copy(haptics = draft.haptics.copy(land = chosen))
                    } else {
                        draft.copy(haptics = draft.haptics.copy(press = chosen))
                    },
                )
                dialog.dismiss()
            }
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.appearance_delete_confirm, baseTheme.name.ifBlank { baseTheme.id }))
            .setMessage(R.string.appearance_delete_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val id = baseTheme.id
                if (themes.deleteUserTheme(id)) {
                    val fallback = themes.load().theme
                    baseTheme = fallback
                    update(fallback)
                    toast(getString(R.string.appearance_deleted))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Files ------------------------------------------------------------------------

    private fun importTheme() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    private fun exportTheme() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "${draft.id.ifBlank { "theme" }}.kbtheme")
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQUEST_EXPORT -> runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(SpecLoader.encode(draft))
                } ?: error("could not open the chosen file")
            }.onSuccess {
                toast(getString(R.string.appearance_exported))
            }.onFailure {
                toast(getString(R.string.appearance_export_failed, it.message ?: "unknown"))
            }

            REQUEST_IMPORT -> {
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()

                if (text == null) {
                    toast(getString(R.string.appearance_import_failed, "could not read the file"))
                    return
                }

                when (val result = themes.import(text)) {
                    is ThemeRepository.SaveResult.Ok -> {
                        baseTheme = result.theme
                        update(result.theme)
                        toast(getString(R.string.appearance_imported, result.theme.name))
                    }

                    is ThemeRepository.SaveResult.Invalid -> toast(
                        getString(R.string.appearance_import_failed, result.errors.first()),
                    )
                }
            }
        }
    }

    // ---- Small helpers ----------------------------------------------------------------

    private enum class Palette(val titleRes: Int) {
        BACKGROUND(R.string.appearance_colour_background),
        KEY(R.string.appearance_colour_key),
        KEY_PRESSED(R.string.appearance_colour_key_pressed),
        ACCENT(R.string.appearance_colour_accent),
        LABEL(R.string.appearance_colour_label),
        LABEL_ON_ACCENT(R.string.appearance_colour_label_on_accent),
        KEY_BORDER(R.string.appearance_colour_border),
    }

    private fun colourOf(theme: Theme, slot: Palette): String = when (slot) {
        Palette.BACKGROUND -> theme.colors.background
        Palette.KEY -> theme.colors.key
        Palette.KEY_PRESSED -> theme.colors.keyPressed
        Palette.ACCENT -> theme.colors.accent
        Palette.LABEL -> theme.colors.label
        Palette.LABEL_ON_ACCENT -> theme.colors.labelOnAccent
        Palette.KEY_BORDER -> theme.colors.keyBorder
    }

    private fun withColour(theme: Theme, slot: Palette, hex: String): Theme = when (slot) {
        Palette.BACKGROUND -> theme.copy(colors = theme.colors.copy(background = hex))
        Palette.KEY -> theme.copy(colors = theme.colors.copy(key = hex))
        Palette.KEY_PRESSED -> theme.copy(colors = theme.colors.copy(keyPressed = hex))
        Palette.ACCENT -> theme.copy(colors = theme.colors.copy(accent = hex))
        Palette.LABEL -> theme.copy(colors = theme.colors.copy(label = hex))
        Palette.LABEL_ON_ACCENT -> theme.copy(colors = theme.colors.copy(labelOnAccent = hex))
        Palette.KEY_BORDER -> theme.copy(colors = theme.colors.copy(keyBorder = hex))
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        setPadding(0, 0, 0, dp(12))
    }

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, dp(24), 0, dp(6))
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

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(4)
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val STEPS = 1000
        const val REQUEST_IMPORT = 2001
        const val REQUEST_EXPORT = 2002
    }
}
