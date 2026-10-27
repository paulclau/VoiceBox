package dev.barkboard.core.profile

import kotlinx.serialization.Serializable

/**
 * One app's override. Both overrides are optional, so a profile can change only the theme or
 * only the layout and inherit the rest from the global selection — which is what people expect
 * from "this app looks different" without having to restate everything.
 */
@Serializable
data class AppProfile(
    /** The app's package name, e.g. `com.android.chrome`. The key of the whole feature. */
    val packageName: String,
    /** Shown in Settings when the package cannot be resolved to an installed app. */
    val label: String = "",
    val themeId: String? = null,
    val layoutId: String? = null,
) {
    fun overridesNothing(): Boolean = themeId.isNullOrBlank() && layoutId.isNullOrBlank()
}

/**
 * The user's profiles, in one small document (`filesDir/profiles.json`). It is a spec like the
 * other three, validated the same way, so a hand-edited file cannot set up a keyboard that will
 * not load.
 */
@Serializable
data class ProfileSet(
    val apps: List<AppProfile> = emptyList(),
) {
    /**
     * The profile for an app, if any. Case-insensitive: package names are lowercase by
     * convention, and a hand-written `com.Example.App` should still match.
     */
    fun forPackage(packageName: String?): AppProfile? {
        val name = packageName?.trim().orEmpty()
        if (name.isEmpty()) return null
        return apps.firstOrNull { it.packageName.trim().equals(name, ignoreCase = true) }
    }

    /** Adds [profile], replacing any existing entry for the same package. */
    fun upsert(profile: AppProfile): ProfileSet {
        val kept = apps.filterNot { it.packageName.trim().equals(profile.packageName.trim(), ignoreCase = true) }
        return copy(apps = kept + profile)
    }

    fun remove(packageName: String): ProfileSet =
        copy(apps = apps.filterNot { it.packageName.trim().equals(packageName.trim(), ignoreCase = true) })
}

/** What an input view will actually use, and the profile (if any) that produced it. */
data class ProfileSelection(
    val themeId: String,
    val layoutId: String,
    val profile: AppProfile?,
    /** Human-readable reasons an override was dropped. Reported, never silent. */
    val notes: List<String>,
)

object ProfileResolver {

    /**
     * Picks the documents for [packageName], falling back to the global selection.
     *
     * An override is only used if it can actually be read: a profile pointing at a theme the
     * user has since deleted must degrade to the global theme, not to the built-in default.
     * That is what [themeReadable] and [layoutReadable] are for — the store knows what exists,
     * this stays pure and testable.
     */
    fun resolve(
        profiles: ProfileSet,
        packageName: String?,
        defaultThemeId: String,
        defaultLayoutId: String,
        themeReadable: (String) -> Boolean = { true },
        layoutReadable: (String) -> Boolean = { true },
    ): ProfileSelection {
        val profile = profiles.forPackage(packageName)
            ?: return ProfileSelection(defaultThemeId, defaultLayoutId, null, emptyList())

        val notes = mutableListOf<String>()
        var applied = false

        val theme = profile.themeId?.takeIf { it.isNotBlank() }?.let { id ->
            if (themeReadable(id)) {
                applied = true
                id
            } else {
                notes += "${profile.packageName}: theme '$id' no longer exists; using $defaultThemeId"
                defaultThemeId
            }
        } ?: defaultThemeId

        val layout = profile.layoutId?.takeIf { it.isNotBlank() }?.let { id ->
            if (layoutReadable(id)) {
                applied = true
                id
            } else {
                notes += "${profile.packageName}: layout '$id' no longer exists; using $defaultLayoutId"
                defaultLayoutId
            }
        } ?: defaultLayoutId

        if (!applied && notes.isEmpty()) {
            notes += "${profile.packageName}: profile has no overrides yet"
        }

        return ProfileSelection(theme, layout, if (applied) profile else null, notes)
    }
}
