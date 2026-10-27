package dev.barkboard.core

import dev.barkboard.core.theme.ColorKit
import dev.barkboard.core.theme.ColorSpec
import dev.barkboard.core.theme.ThemeLimits
import dev.barkboard.core.theme.SpecLoader
import dev.barkboard.core.theme.SpecResult
import dev.barkboard.core.theme.Theme
import dev.barkboard.core.theme.Colors
import dev.barkboard.core.theme.FlightStyle
import dev.barkboard.core.theme.Haptics
import dev.barkboard.core.theme.KeyStyle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorKitTest {

    /** Every colour the shipped themes use, plus the awkward ones. */
    private val samples = listOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
        0xFF0000FF.toInt(), 0xFF5EE0C0.toInt(), 0x801E2530.toInt(), 0xFF7F7F7F.toInt(),
    )

    @Test
    fun `round trips every sample through hsl without drifting`() {
        samples.forEach { argb ->
            val back = ColorKit.toArgb(ColorKit.toHsl(argb), ColorKit.alpha(argb))
            assertChannelClose(argb, back)
        }
    }

    @Test
    fun `greys have no saturation and keep their lightness`() {
        val grey = ColorKit.toHsl(0xFF808080.toInt())
        assertEquals(0f, grey.saturation, 1e-4f)
        assertEquals(0.502f, grey.lightness, 0.01f)
        assertEquals(0f, ColorKit.toHsl(0xFF000000.toInt()).lightness, 1e-4f)
        assertEquals(1f, ColorKit.toHsl(0xFFFFFFFF.toInt()).lightness, 1e-4f)
    }

    @Test
    fun `primary red sits at hue zero`() {
        val red = ColorKit.toHsl(0xFFFF0000.toInt())
        assertEquals(0f, red.hue, 1e-4f)
        assertEquals(1f, red.saturation, 1e-4f)
        assertEquals(0.5f, red.lightness, 1e-4f)
    }

    @Test
    fun `alpha survives a hex round trip`() {
        assertEquals(0x80, ColorKit.alpha(ColorSpec.parse(ColorKit.toHex(0x80123456.toInt()))!!))
        assertEquals("#80123456", ColorKit.toHex(0x80123456.toInt()))
        assertEquals("#FF000000", ColorKit.toHex(0xFF000000.toInt()))
    }

    @Test
    fun `withAlpha only replaces the alpha channel`() {
        assertEquals("#005EE0C0", ColorKit.withAlpha("#FF5EE0C0", 0))
        assertEquals("#805EE0C0", ColorKit.withAlpha("#5EE0C0", 0x80))
        // Malformed input is returned untouched rather than becoming black.
        assertEquals("not-a-colour", ColorKit.withAlpha("not-a-colour", 0x40))
    }

    @Test
    fun `luminance picks a readable label`() {
        assertTrue(ColorKit.prefersDarkLabel(0xFFFFFFFF.toInt()))
        assertTrue(!ColorKit.prefersDarkLabel(0xFF0B0D10.toInt()))
        // A mid teal accent is bright enough to need dark text.
        assertTrue(ColorKit.prefersDarkLabel(0xFF5EE0C0.toInt()))
    }

    private fun assertChannelClose(expected: Int, actual: Int) {
        listOf(16, 8, 0).forEach { shift ->
            val a = (expected shr shift) and 0xFF
            val b = (actual shr shift) and 0xFF
            assertTrue(abs(a - b) <= 1, "channel at shift $shift: $a != $b in ${ColorKit.toHex(expected)} → ${ColorKit.toHex(actual)}")
        }
    }
}

class ThemeEncodingTest {

    private val custom = Theme(
        id = "custom",
        name = "Custom",
        colors = Colors(
            background = "#FF101010",
            key = "#FF202020",
            keyPressed = "#FF303030",
            accent = "#FFFF8800",
            label = "#FFF0F0F0",
            labelOnAccent = "#FF000000",
            keyBorder = "#40FFFFFF",
        ),
        key = KeyStyle(radiusDp = 30f, gapDp = 2f, labelSizeSp = 34f, pressedScale = 1.1f),
        flight = FlightStyle(
            enabled = false,
            durationMs = 320,
            arc = -0.8f,
            startScale = 2.4f,
            endScale = 0.4f,
            trail = false,
            allowFallbackLane = false,
            laneDp = 40f,
        ),
        haptics = Haptics(press = "heavy", land = "click"),
    )

    @Test
    fun `a theme encodes and decodes unchanged`() {
        val text = SpecLoader.encode(custom)
        val decoded = SpecLoader.theme(text)
        assertTrue(decoded is SpecResult.Ok, "expected a valid theme, got $decoded")
        assertEquals(custom, (decoded as SpecResult.Ok<Theme>).value)
    }

    @Test
    fun `the encoded document is the same format the assets use`() {
        val text = SpecLoader.encode(custom)
        // A reader of the shipped theme files must be able to read an exported one.
        assertTrue(text.contains("\"id\": \"custom\""), text)
        assertTrue(text.contains("\"laneDp\": 40"), text)
        assertTrue(text.contains("\"haptics\""), text)
    }

    @Test
    fun `a hand-edited file with a broken colour is still refused`() {
        val broken = SpecLoader.encode(custom).replace("#FF101010", "orange")
        val result = SpecLoader.theme(broken)
        assertTrue(result is SpecResult.Invalid, "a non-colour must not load")
    }

    @Test
    fun `every extreme the editor allows is a legal theme`() {
        // The editor's sliders are built from these ranges, so their ends must validate.
        val extremes = listOf(
            custom.copy(
                key = KeyStyle(
                    radiusDp = ThemeLimits.radiusDp.start,
                    gapDp = ThemeLimits.gapDp.start,
                    labelSizeSp = ThemeLimits.labelSizeSp.start,
                    pressedScale = ThemeLimits.pressedScale.start,
                ),
                flight = custom.flight.copy(
                    durationMs = ThemeLimits.durationMs.start,
                    arc = ThemeLimits.arc.start,
                    startScale = ThemeLimits.flightScale.start,
                    endScale = ThemeLimits.flightScale.start,
                    laneDp = ThemeLimits.laneDp.start,
                ),
            ),
            custom.copy(
                key = KeyStyle(
                    radiusDp = ThemeLimits.radiusDp.endInclusive,
                    gapDp = ThemeLimits.gapDp.endInclusive,
                    labelSizeSp = ThemeLimits.labelSizeSp.endInclusive,
                    pressedScale = ThemeLimits.pressedScale.endInclusive,
                ),
                flight = custom.flight.copy(
                    durationMs = ThemeLimits.durationMs.endInclusive,
                    arc = ThemeLimits.arc.endInclusive,
                    startScale = ThemeLimits.flightScale.endInclusive,
                    endScale = ThemeLimits.flightScale.endInclusive,
                    laneDp = ThemeLimits.laneDp.endInclusive,
                ),
            ),
        )

        extremes.forEach { theme ->
            assertEquals(emptyList(), SpecLoader.validateTheme(theme))
            assertTrue(SpecLoader.theme(SpecLoader.encode(theme)) is SpecResult.Ok)
        }
    }
}
