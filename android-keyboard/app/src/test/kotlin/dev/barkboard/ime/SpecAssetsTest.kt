package dev.barkboard.ime

import dev.barkboard.core.layout.KeyKind
import dev.barkboard.core.theme.SpecLoader
import dev.barkboard.core.theme.SpecResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shipped documents are validated by the same loader the keyboard uses. Without this a
 * typo in a theme would silently fall back to the built-in defaults and nobody would notice.
 */
class SpecAssetsTest {

    private val assets = File("src/main/assets")

    private fun read(path: String): String {
        val file = File(assets, path)
        assertTrue(file.isFile, "missing asset: ${file.absolutePath}")
        return file.readText()
    }

    private fun <T> requireOk(result: SpecResult<T>): T = when (result) {
        is SpecResult.Ok -> result.value
        is SpecResult.Invalid -> fail(result.errors.joinToString("\n"))
    }

    @Test
    fun `every shipped theme is valid`() {
        val themes = File(assets, "themes").listFiles { file -> file.name.endsWith(".theme.json") }.orEmpty()
        assertTrue(themes.isNotEmpty(), "no themes shipped")

        themes.forEach { file ->
            val theme = requireOk(SpecLoader.theme(file.readText()))
            assertEquals(file.name.removeSuffix(".theme.json"), theme.id, "${file.name} id mismatch")
        }
    }

    @Test
    fun `the qwerty layout is valid and typeable`() {
        val layout = requireOk(SpecLoader.layout(read("layouts/qwerty.json")))

        val kinds = layout.rows.flatMap { row -> row.keys }.map { SpecLoader.keyKind(it) }
        assertTrue(KeyKind.MIC in kinds, "no mic key, so voice typing is unreachable")
        assertTrue(KeyKind.SPACE in kinds)
        assertTrue(KeyKind.BACKSPACE in kinds)
        assertTrue(KeyKind.SHIFT in kinds)
        assertTrue(KeyKind.ENTER in kinds)

        val letters = layout.rows.flatMap { row -> row.keys }
            .filter { SpecLoader.keyKind(it) == KeyKind.CHAR }
            .mapNotNull { it.output }
            .toSet()
        assertEquals(26, letters.size, "expected the full alphabet, got $letters")
    }

    @Test
    fun `every shipped layout is valid, typeable and named after its file`() {
        val layouts = File(assets, "layouts").listFiles { file -> file.name.endsWith(".json") }.orEmpty()
        assertTrue(layouts.size >= 2, "the layout picker needs more than one option to be a choice")

        layouts.forEach { file ->
            val name = file.name
            val layout = requireOk(SpecLoader.layout(file.readText()))
            assertEquals(name.removeSuffix(".json"), layout.id, "$name id mismatch")

            val kinds = layout.rows.flatMap { row -> row.keys }.map { SpecLoader.keyKind(it) }
            listOf(KeyKind.MIC, KeyKind.SPACE, KeyKind.BACKSPACE, KeyKind.SHIFT, KeyKind.ENTER)
                .forEach { kind -> assertTrue(kind in kinds, "$name has no $kind key") }

            val outputs = layout.rows.flatMap { row -> row.keys }
                .filter { SpecLoader.keyKind(it) == KeyKind.CHAR }
                .mapNotNull { it.output }
                .toSet()
            assertTrue(('a'..'z').all { it.toString() in outputs }, "$name is missing letters")
        }
    }

    @Test
    fun `gesture bindings resolve to actions the keyboard handles`() {
        val gestures = requireOk(SpecLoader.gestures(read("gestures/default.json")))
        val handled = setOf("voice", "language", "deleteWord", "dismissKeyboard", "space")

        assertTrue(gestures.bindings.isNotEmpty())
        gestures.bindings.forEach { (binding, action) ->
            assertTrue(action in handled, "$binding points at unhandled action '$action'")
        }
    }
}
