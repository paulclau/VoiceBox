package dev.barkboard.ime.theme

import dev.barkboard.core.layout.KeySpec
import dev.barkboard.core.layout.LayoutSpec
import dev.barkboard.core.layout.RowSpec
import dev.barkboard.core.theme.Colors
import dev.barkboard.core.theme.Theme

/**
 * Compiled-in fallbacks. A theme or layout that fails to parse must still leave a working
 * keyboard, so these never depend on an asset.
 */
object Defaults {

    val theme = Theme(
        id = "default",
        name = "Default",
        colors = Colors(
            background = "#FF0B0D10",
            key = "#FF151A21",
            keyPressed = "#FF232B36",
            accent = "#FF5EE0C0",
        ),
    )

    val layout: LayoutSpec = LayoutSpec(
        id = "qwerty",
        name = "QWERTY",
        rows = listOf(
            rowOf("qwertyuiop"),
            RowSpec(
                keys = "asdfghjkl".map { char(it) } + KeySpec(kind = "backspace", width = 1.5f),
            ),
            RowSpec(
                keys = listOf(KeySpec(kind = "shift", width = 1.5f)) +
                    "zxcvbnm".map { char(it) } +
                    KeySpec(kind = "enter", width = 1.5f),
            ),
            RowSpec(
                keys = listOf(
                    KeySpec(kind = "language", width = 1f, gesture = mapOf("swipe-up" to "voice")),
                    KeySpec(kind = "mic", width = 1f, gesture = mapOf("swipe-up" to "voice")),
                    KeySpec(kind = "space", width = 5f, id = "space", gesture = mapOf("swipe-left" to "language")),
                    KeySpec(kind = "emoji", width = 1f),
                ),
            ),
        ),
    )

    private fun rowOf(letters: String) = RowSpec(keys = letters.map { char(it) })

    private fun char(c: Char) = KeySpec(output = c.toString(), label = c.toString())
}
