package dev.barkboard.core

import dev.barkboard.core.profile.AppProfile
import dev.barkboard.core.profile.ProfileResolver
import dev.barkboard.core.profile.ProfileSet
import dev.barkboard.core.theme.SpecLoader
import dev.barkboard.core.theme.SpecResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ProfileTest {

    private val browser = AppProfile("com.android.chrome", "Chrome", themeId = "paper")
    private val terminal = AppProfile("com.termux", "Termux", layoutId = "numbers")

    private val profiles = ProfileSet(listOf(browser, terminal))

    private fun okOrFail(result: SpecResult<ProfileSet>): ProfileSet = when (result) {
        is SpecResult.Ok -> result.value
        is SpecResult.Invalid -> fail("expected ok, got ${result.errors}")
    }

    private fun errorsOf(result: SpecResult<*>): List<String> = when (result) {
        is SpecResult.Invalid -> result.errors
        is SpecResult.Ok -> fail("expected a validation error")
    }

    private fun resolve(
        packageName: String?,
        themeReadable: (String) -> Boolean = { true },
        layoutReadable: (String) -> Boolean = { true },
    ) = ProfileResolver.resolve(
        profiles = profiles,
        packageName = packageName,
        defaultThemeId = "midnight",
        defaultLayoutId = "qwerty",
        themeReadable = themeReadable,
        layoutReadable = layoutReadable,
    )

    @Test
    fun `a profile overrides only what it sets`() {
        val themed = resolve("com.android.chrome")
        assertEquals("paper", themed.themeId)
        assertEquals("qwerty", themed.layoutId, "the layout must stay global")
        assertEquals("com.android.chrome", themed.profile?.packageName)

        val laidOut = resolve("com.termux")
        assertEquals("midnight", laidOut.themeId, "the theme must stay global")
        assertEquals("numbers", laidOut.layoutId)
    }

    @Test
    fun `an app without a profile inherits the global selection`() {
        val selection = resolve("com.example.notes")
        assertEquals("midnight", selection.themeId)
        assertEquals("qwerty", selection.layoutId)
        assertNull(selection.profile)
        assertTrue(selection.notes.isEmpty(), "no profile is not a problem to report")
    }

    @Test
    fun `a missing or blank package name inherits too`() {
        listOf(null, "", "   ").forEach { unknown ->
            val selection = resolve(unknown)
            assertEquals("midnight", selection.themeId, "package '$unknown'")
            assertEquals("qwerty", selection.layoutId)
            assertNull(selection.profile)
        }
    }

    @Test
    fun `package names match regardless of case`() {
        assertEquals("com.android.chrome", profiles.forPackage("COM.Android.Chrome")?.packageName)
    }

    @Test
    fun `an override that no longer loads falls back to the global document`() {
        val selection = resolve(
            "com.android.chrome",
            themeReadable = { id -> id != "paper" },
        )

        assertEquals("midnight", selection.themeId, "a deleted theme must not drop to the built-in default")
        assertNull(selection.profile, "a profile that contributed nothing is not applied")
        assertTrue(selection.notes.any { it.contains("paper") }, "the fallback must be reported: ${selection.notes}")
    }

    @Test
    fun `a half readable profile keeps the half that works`() {
        val both = ProfileSet(listOf(AppProfile("com.termux", themeId = "ghost", layoutId = "numbers")))

        val selection = ProfileResolver.resolve(
            profiles = both,
            packageName = "com.termux",
            defaultThemeId = "midnight",
            defaultLayoutId = "qwerty",
            themeReadable = { it != "ghost" },
        )

        assertEquals("midnight", selection.themeId)
        assertEquals("numbers", selection.layoutId, "the readable override still applies")
        assertEquals("com.termux", selection.profile?.packageName)
        assertEquals(1, selection.notes.size)
    }

    @Test
    fun `an empty profile is reported rather than applied`() {
        val empty = ProfileSet(listOf(AppProfile("com.termux", label = "Termux")))
        val selection = ProfileResolver.resolve(empty, "com.termux", "midnight", "qwerty")

        assertNull(selection.profile)
        assertEquals("midnight", selection.themeId)
        assertTrue(selection.notes.single().contains("no overrides"))
    }

    @Test
    fun `upsert replaces an entry instead of adding a second one`() {
        val updated = profiles.upsert(AppProfile("COM.Android.Chrome", themeId = "paper", layoutId = "numbers"))

        assertEquals(2, updated.apps.size, "a case-different package must not become a second entry")
        assertEquals("numbers", updated.forPackage("com.android.chrome")?.layoutId)
    }

    @Test
    fun `remove drops one package and leaves the rest`() {
        val trimmed = profiles.remove("com.android.chrome")
        assertEquals(listOf("com.termux"), trimmed.apps.map { it.packageName })
        assertEquals(profiles.apps.size, profiles.remove("com.nothing").apps.size)
    }

    @Test
    fun `the profile document round trips`() {
        val text = SpecLoader.encode(profiles)
        val parsed = okOrFail(SpecLoader.profiles(text))

        assertEquals(profiles.apps, parsed.apps)
        assertTrue(text.contains("com.android.chrome"))
    }

    @Test
    fun `an empty document is a valid profile set`() {
        assertEquals(emptyList(), okOrFail(SpecLoader.profiles("{}")).apps)
        assertEquals(emptyList(), okOrFail(SpecLoader.profiles("""{ "apps": [] }""")).apps)
    }

    @Test
    fun `broken profile documents are refused with a reason`() {
        fun errors(text: String) = errorsOf(SpecLoader.profiles(text))

        assertTrue(errors("""{ "apps": [ { "packageName": "" } ] }""").any { it.contains("blank") })
        assertTrue(errors("""{ "apps": [ { "packageName": "com.foo bar" } ] }""").any { it.contains("whitespace") })
        assertTrue(errors("""{ "apps": [ { "packageName": "com.foo/bar" } ] }""").any { it.contains("not a package name") })
        assertTrue(
            errors("""{ "apps": [ { "packageName": "com.a" }, { "packageName": "COM.A" } ] }""")
                .any { it.contains("twice") },
        )
        assertTrue(errors("""{ "apps": [ { "packageName": "com.a", "themeId": "" } ] }""").any { it.contains("themeId is blank") })

        // Strict about unknown fields, like the other specs: a typo must be an error, not a
        // profile that silently does nothing.
        assertTrue(errors("""{ "apps": [ { "packageName": "com.a", "themes": "paper" } ] }""").isNotEmpty())
    }
}
