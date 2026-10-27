package dev.barkboard.core

import dev.barkboard.core.theme.ColorSpec
import dev.barkboard.core.theme.GestureMap
import dev.barkboard.core.theme.Gestures
import dev.barkboard.core.theme.SpecLoader
import dev.barkboard.core.theme.SpecResult
import dev.barkboard.core.theme.SwipeDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SpecTest {

    private fun <T> okOrFail(result: SpecResult<T>): T = when (result) {
        is SpecResult.Ok -> result.value
        is SpecResult.Invalid -> fail("expected ok, got ${result.errors}")
    }

    private fun errorsOf(result: SpecResult<*>): List<String> = when (result) {
        is SpecResult.Invalid -> result.errors
        is SpecResult.Ok -> fail("expected a validation error")
    }

    private val validTheme = """
        {
          "id": "midnight",
          "name": "Midnight",
          "colors": {
            "background": "#0b0d10",
            "key": "#151a21",
            "keyPressed": "#232b36",
            "accent": "#5ee0c0"
          },
          "key": { "radiusDp": 12, "gapDp": 6, "labelSizeSp": 22 },
          "flight": { "durationMs": 200, "arc": 0.35 },
          "haptics": { "press": "light", "land": "tick" }
        }
    """.trimIndent()

    private val validLayout = """
        {
          "id": "qwerty",
          "rows": [
            { "keys": [ { "output": "q" }, { "kind": "shift", "width": 1.5 } ] },
            { "keys": [ { "kind": "space", "width": 6 }, { "kind": "mic" } ] }
          ]
        }
    """.trimIndent()

    @Test
    fun `valid documents load`() {
        val theme = okOrFail(SpecLoader.theme(validTheme))
        assertEquals("midnight", theme.id)
        assertEquals(200, theme.flight.durationMs)

        val layout = okOrFail(SpecLoader.layout(validLayout))
        assertEquals(2, layout.rows.size)
    }

    @Test
    fun `bad colour is reported by name`() {
        val broken = validTheme.replace("\"#151a21\"", "\"not-a-colour\"")
        val errors = errorsOf(SpecLoader.theme(broken))

        assertTrue(errors.any { it.contains("colors.key") }, errors.toString())
    }

    @Test
    fun `unknown field is rejected rather than ignored`() {
        val broken = validTheme.replaceFirst("{", """{ "radius": 4,""")
        val errors = errorsOf(SpecLoader.theme(broken))
        assertTrue(errors.any { it.contains("radius") }, errors.toString())
    }

    @Test
    fun `out of range flight is rejected`() {
        val broken = validTheme.replace("\"durationMs\": 200", "\"durationMs\": 2000")
        val errors = errorsOf(SpecLoader.theme(broken))

        assertTrue(errors.any { it.contains("flight.durationMs") }, errors.toString())
    }

    @Test
    fun `char key without output is rejected`() {
        val broken = validLayout.replace("""{ "output": "q" }""", """{ "kind": "char" }""")
        val errors = errorsOf(SpecLoader.layout(broken))

        assertTrue(errors.any { it.contains("needs an output") }, errors.toString())
    }

    @Test
    fun `unknown kind and gesture name are rejected`() {
        val broken = validLayout
            .replace("""{ "kind": "mic" }""", """{ "kind": "teleport" }""")
            .replace("""{ "output": "q" }""", """{ "output": "q", "gesture": { "swipe-sideways": "voice" } }""")
        val errors = errorsOf(SpecLoader.layout(broken))

        assertTrue(errors.any { it.contains("teleport") }, errors.toString())
        assertTrue(errors.any { it.contains("swipe-sideways") }, errors.toString())
    }

    @Test
    fun `empty layout is rejected`() {
        val errors = errorsOf(SpecLoader.layout("""{ "id": "empty", "rows": [] }"""))
        assertTrue(errors.any { it.contains("rows") }, errors.toString())
    }

    @Test
    fun `gesture map validates its keys`() {
        okOrFail(SpecLoader.gestures("""{ "bindings": { "space.swipe-down": "dismissKeyboard" } }"""))

        val errors = errorsOf(SpecLoader.gestures("""{ "bindings": { "space.swoosh": "x", "space": "y" } }"""))
        assertEquals(2, errors.size, errors.toString())
    }

    @Test
    fun `colours parse in all supported lengths`() {
        assertEquals(0xFFFFFFFF.toInt(), ColorSpec.parse("#fff"))
        assertEquals(0xFF112233.toInt(), ColorSpec.parse("#112233"))
        assertEquals(0x80112233.toInt(), ColorSpec.parse("#80112233"))
        assertNull(ColorSpec.parse("112233"))
        assertNull(ColorSpec.parse("#12345"))
        assertNull(ColorSpec.parse("#gggggg"))
    }

    @Test
    fun `swipe direction needs a dominant axis past the threshold`() {
        assertEquals(SwipeDirection.UP, Gestures.direction(2f, -40f, 20f))
        assertEquals(SwipeDirection.DOWN, Gestures.direction(-2f, 40f, 20f))
        assertEquals(SwipeDirection.RIGHT, Gestures.direction(40f, 5f, 20f))
        assertEquals(SwipeDirection.LEFT, Gestures.direction(-40f, 5f, 20f))
        assertNull(Gestures.direction(5f, 5f, 20f))
        assertNull(Gestures.direction(40f, 0f, 0f))
    }

    @Test
    fun `key-local binding wins over the global map`() {
        val global = GestureMap(
            mapOf(
                "mic.swipe-up" to "globalById",
                "space.swipe-left" to "language",
            ),
        )

        assertEquals(
            "voice",
            Gestures.resolve(mapOf("swipe-up" to "voice"), global, "mic", "mic", SwipeDirection.UP),
        )
        assertEquals(
            "globalById",
            Gestures.resolve(emptyMap(), global, "mic", "mic", SwipeDirection.UP),
        )
        assertEquals(
            "language",
            Gestures.resolve(emptyMap(), global, "space", "space", SwipeDirection.LEFT),
        )
        assertNull(Gestures.resolve(emptyMap(), global, "enter", "enter", SwipeDirection.UP))
        assertEquals(
            "language",
            Gestures.resolve(emptyMap(), global, null, "space", SwipeDirection.LEFT),
            "kind is the fallback when a key has no id",
        )
    }
}
