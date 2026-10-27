package dev.barkboard.ime.theme

import android.content.Context
import dev.barkboard.core.layout.LayoutSpec
import dev.barkboard.core.profile.AppProfile
import dev.barkboard.core.profile.ProfileResolver
import dev.barkboard.core.profile.ProfileSet
import dev.barkboard.core.theme.GestureMap
import dev.barkboard.core.theme.SpecLoader
import dev.barkboard.core.theme.SpecResult
import dev.barkboard.core.theme.Theme
import dev.barkboard.ime.ui.KeyboardAppearance
import java.io.File

/**
 * Loads the theme, layout, gesture and per-app profile documents, validating each one and falling
 * back to [Defaults] when a document is broken. Problems are returned, not swallowed, so Settings
 * can show them.
 *
 * Users' themes live in `filesDir/themes` and shadow a built-in with the same id, which is what
 * lets the editor save an edit of "midnight" without touching the APK. Profiles live in
 * `filesDir/profiles.json` and pick a theme and layout per app package. Every read validates:
 * a file that arrived over Bluetooth or was hand-edited is not trusted input.
 */
class ThemeRepository(private val context: Context) {

    class Bundle(
        val theme: Theme,
        val layout: LayoutSpec,
        val gestures: GestureMap,
        val appearance: KeyboardAppearance,
        val problems: List<String>,
        /** The profile that chose this theme and layout, when one did. */
        val profile: AppProfile? = null,
    )

    sealed interface SaveResult {
        data class Ok(val theme: Theme) : SaveResult
        data class Invalid(val errors: List<String>) : SaveResult
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where edited and imported themes are written. */
    val userThemeDir: File
        get() = File(context.filesDir, THEME_DIR).apply { if (!exists()) mkdirs() }

    /** Built-in ids first, then user themes; a user file shadows the asset of the same id. */
    fun availableThemes(): List<String> {
        val builtIn = runCatching {
            context.assets.list(THEME_DIR)
                ?.filter { it.endsWith(THEME_SUFFIX) }
                ?.map { it.removeSuffix(THEME_SUFFIX) }
                .orEmpty()
        }.getOrDefault(emptyList())

        val user = runCatching {
            userThemeDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(THEME_SUFFIX) }
                ?.map { it.name.removeSuffix(THEME_SUFFIX) }
                .orEmpty()
        }.getOrDefault(emptyList())

        return (builtIn + user).distinct().sorted()
    }

    fun isUserTheme(id: String): Boolean = userThemeFile(id).isFile

    fun selectedThemeId(): String {
        val stored = prefs.getString(KEY_THEME, null)
        if (stored != null) return stored
        return availableThemes().firstOrNull() ?: Defaults.theme.id
    }

    fun select(themeId: String) {
        prefs.edit().putString(KEY_THEME, themeId).apply()
    }

    /** Layout ids are asset names; a user cannot add one, only choose between them. */
    fun availableLayouts(): List<String> = runCatching {
        context.assets.list(LAYOUT_DIR)
            ?.filter { it.endsWith(LAYOUT_SUFFIX) }
            ?.map { it.removeSuffix(LAYOUT_SUFFIX) }
            .orEmpty()
    }.getOrDefault(emptyList()).sorted()

    fun selectedLayoutId(): String {
        val stored = prefs.getString(KEY_LAYOUT, null)
        if (stored != null && availableLayouts().contains(stored)) return stored
        return availableLayouts().firstOrNull() ?: Defaults.layout.id
    }

    fun selectLayout(layoutId: String) {
        prefs.edit().putString(KEY_LAYOUT, layoutId).apply()
    }

    /** Display name for a layout id, falling back to the id when the file will not load. */
    fun layoutName(id: String): String {
        val text = layoutText(id) ?: return "$id (missing)"
        return when (val result = SpecLoader.layout(text)) {
            is SpecResult.Ok -> result.value.name.ifBlank { id }
            is SpecResult.Invalid -> "$id (invalid)"
        }
    }

    /**
     * Bumped on every write. The keyboard compares it while starting an input view, so an edit
     * is picked up even though the theme keeps its id.
     */
    fun revision(): Int = prefs.getInt(KEY_REVISION, 0)

    /** Display name for a theme id, falling back to the id when the file will not load. */
    fun themeName(id: String): String =
        when (val result = read(id)) {
            is SpecResult.Ok -> result.value.name.ifBlank { id }
            is SpecResult.Invalid -> "$id (invalid)"
        }

    /** The raw document, for export. Null when neither a user file nor an asset exists. */
    fun rawTheme(id: String): String? {
        val user = userThemeFile(id)
        if (user.isFile) return runCatching { user.readText() }.getOrNull()
        return runCatching {
            context.assets.open("$THEME_DIR/$id$THEME_SUFFIX").bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    // ---- per-app profiles ---------------------------------------------------------------

    private var profileProblems: List<String> = emptyList()

    private val profilesFile: File
        get() = File(context.filesDir, PROFILE_FILE)

    /**
     * The stored profiles. A document that will not parse reads as "no profiles" rather than as
     * a broken keyboard, and the reason is kept for [profileProblems].
     *
     * Read from disk every time rather than cached: Settings and the keyboard each hold their own
     * repository, so a cache here would serve the keyboard the profiles the user had before they
     * edited them.
     */
    fun profiles(): ProfileSet {
        val file = profilesFile
        if (!file.isFile) {
            profileProblems = emptyList()
            return ProfileSet()
        }

        val text = runCatching { file.readText() }.getOrElse { error ->
            profileProblems = listOf("$PROFILE_FILE: could not be read (${error.message})")
            return ProfileSet()
        }

        return when (val result = SpecLoader.profiles(text)) {
            is SpecResult.Ok -> {
                profileProblems = emptyList()
                result.value
            }

            is SpecResult.Invalid -> {
                profileProblems = result.errors.map { "$PROFILE_FILE: $it" }
                ProfileSet()
            }
        }
    }

    /** Why the stored profiles are not being used, if they are not. */
    fun profileProblems(): List<String> {
        profiles()
        return profileProblems
    }

    /**
     * Validates and writes the profiles, then bumps the revision so the keyboard picks the change
     * up on its next input view.
     */
    fun saveProfiles(profiles: ProfileSet): SpecResult<ProfileSet> {
        val errors = SpecLoader.validateProfiles(profiles)
        if (errors.isNotEmpty()) return SpecResult.Invalid(errors)

        return runCatching {
            writeAtomically(profilesFile, SpecLoader.encode(profiles))
            profileProblems = emptyList()
            bumpRevision()
            SpecResult.Ok(profiles)
        }.getOrElse { error ->
            SpecResult.Invalid(listOf("could not write $PROFILE_FILE: ${error.message}"))
        }
    }

    /** Parses and validates a theme document without storing it. */
    fun read(id: String): SpecResult<Theme> {
        val text = rawTheme(id) ?: return SpecResult.Invalid(listOf("$id: not found"))
        return SpecLoader.theme(text)
    }

    /**
     * Validates and writes [theme], then selects it. The theme keeps its id, so saving an edit of
     * a built-in theme shadows it rather than piling up "midnight-2" copies.
     */
    fun save(theme: Theme): SaveResult {
        val errors = SpecLoader.validateTheme(theme)
        if (errors.isNotEmpty()) return SaveResult.Invalid(errors)

        val target = userThemeFile(theme.id)
        return runCatching {
            writeAtomically(target, SpecLoader.encode(theme))
            select(theme.id)
            bumpRevision()
            SaveResult.Ok(theme)
        }.getOrElse { error ->
            SaveResult.Invalid(listOf("could not write ${target.name}: ${error.message}"))
        }
    }

    /**
     * Stores an imported document under a free id, so importing twice does not silently
     * overwrite the theme that was already there.
     */
    fun import(text: String): SaveResult {
        val parsed = when (val result = SpecLoader.theme(text)) {
            is SpecResult.Ok -> result.value
            is SpecResult.Invalid -> return SaveResult.Invalid(result.errors)
        }

        val baseId = parsed.id.ifBlank { "imported" }
        var id = baseId
        var counter = 2
        while (exists(id) && rawTheme(id) != text) {
            id = "$baseId-$counter"
            counter++
        }

        return save(parsed.copy(id = id, name = parsed.name.ifBlank { id }))
    }

    /** Removes a user theme. A built-in theme cannot be deleted, only shadowed. */
    fun deleteUserTheme(id: String): Boolean {
        val file = userThemeFile(id)
        if (!file.isFile) return false
        val removed = file.delete()
        if (removed) {
            if (selectedThemeId() == id) {
                prefs.edit().remove(KEY_THEME).apply()
            }
            bumpRevision()
        }
        return removed
    }

    /**
     * The documents for one app package: the profile's picks where it has them, the global
     * selection everywhere else. An override that can no longer be read falls back to the global
     * choice and says so in [Bundle.problems].
     */
    fun loadFor(packageName: String?): Bundle {
        val selection = ProfileResolver.resolve(
            profiles = profiles(),
            packageName = packageName,
            defaultThemeId = selectedThemeId(),
            defaultLayoutId = selectedLayoutId(),
            themeReadable = { id -> read(id) is SpecResult.Ok },
            layoutReadable = { id -> layoutText(id)?.let { SpecLoader.layout(it) } is SpecResult.Ok },
        )

        val bundle = load(selection.themeId, selection.layoutId, selection.profile)
        if (selection.notes.isEmpty()) return bundle
        return Bundle(
            theme = bundle.theme,
            layout = bundle.layout,
            gestures = bundle.gestures,
            appearance = bundle.appearance,
            problems = selection.notes + bundle.problems,
            profile = bundle.profile,
        )
    }

    fun load(
        themeId: String = selectedThemeId(),
        layoutId: String = selectedLayoutId(),
        profile: AppProfile? = null,
    ): Bundle {
        val problems = mutableListOf<String>()

        val theme = when (val result = read(themeId)) {
            is SpecResult.Ok -> result.value
            is SpecResult.Invalid -> {
                problems += "$THEME_DIR/$themeId$THEME_SUFFIX: ${result.errors.joinToString("; ")}"
                Defaults.theme
            }
        }

        val layout = loadAsset(
            path = layoutAssetPath(layoutId),
            parse = SpecLoader::layout,
            fallback = Defaults.layout,
            problems = problems,
        )
        val gestures = loadAsset(
            path = GESTURE_PATH,
            parse = SpecLoader::gestures,
            fallback = GestureMap(),
            problems = problems,
        )

        val appearance = KeyboardAppearance.from(theme, context.resources.displayMetrics.density)
        return Bundle(theme, layout, gestures, appearance, problems, profile)
    }

    /** A free id for a new theme, e.g. "custom", "custom-2". */
    fun newThemeId(base: String = "custom"): String {
        var id = base
        var counter = 2
        while (exists(id)) {
            id = "$base-$counter"
            counter++
        }
        return id
    }

    private fun exists(id: String): Boolean =
        userThemeFile(id).isFile || runCatching {
            context.assets.open("$THEME_DIR/$id$THEME_SUFFIX").close()
            true
        }.getOrDefault(false)

    private fun userThemeFile(id: String): File = File(userThemeDir, "$id$THEME_SUFFIX")

    private fun layoutAssetPath(id: String): String = "$LAYOUT_DIR/$id$LAYOUT_SUFFIX"

    private fun layoutText(id: String): String? = runCatching {
        context.assets.open(layoutAssetPath(id)).bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun bumpRevision() {
        prefs.edit().putInt(KEY_REVISION, revision() + 1).apply()
    }

    /** Write to a sibling file and rename, so a crash mid-write cannot leave half a theme. */
    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("rename failed")
        }
    }

    private fun <T> loadAsset(
        path: String,
        parse: (String) -> SpecResult<T>,
        fallback: T,
        problems: MutableList<String>,
    ): T {
        val text = runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrElse {
            problems += "$path: could not be read"
            return fallback
        }

        return when (val result = parse(text)) {
            is SpecResult.Ok -> result.value
            is SpecResult.Invalid -> {
                problems += result.errors.map { "$path: $it" }
                fallback
            }
        }
    }

    private companion object {
        const val PREFS = "barkboard"
        const val KEY_THEME = "theme_id"
        const val KEY_LAYOUT = "layout_id"
        const val KEY_REVISION = "theme_revision"
        const val THEME_DIR = "themes"
        const val LAYOUT_DIR = "layouts"
        const val THEME_SUFFIX = ".theme.json"
        const val LAYOUT_SUFFIX = ".json"
        const val GESTURE_PATH = "gestures/default.json"
        const val PROFILE_FILE = "profiles.json"
    }
}
