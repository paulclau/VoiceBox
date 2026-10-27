package dev.barkboard.ime.ui

import dev.barkboard.core.flight.FlightConfig
import dev.barkboard.core.theme.ColorSpec
import dev.barkboard.core.theme.Theme

/**
 * The theme resolved into pixels and packed colours, built once per load and handed to the
 * view. The view never parses JSON or converts dp.
 */
class KeyboardAppearance(
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val accent: Int,
    val label: Int,
    val labelOnAccent: Int,
    val keyBorder: Int,
    val keyRadius: Float,
    val keyGap: Float,
    val labelSize: Float,
    val pressedScale: Float,
    val lanePx: Float,
    val flightEnabled: Boolean,
    val allowFallbackLane: Boolean,
    val flightConfig: FlightConfig,
    val pressHaptic: String,
    val landHaptic: String,
    val minRowHeight: Float,
) {
    companion object {
        fun from(theme: Theme, density: Float): KeyboardAppearance {
            fun color(hex: String, fallback: Int): Int = ColorSpec.parse(hex) ?: fallback

            return KeyboardAppearance(
                background = color(theme.colors.background, 0xFF0B0D10.toInt()),
                key = color(theme.colors.key, 0xFF151A21.toInt()),
                keyPressed = color(theme.colors.keyPressed, 0xFF232B36.toInt()),
                accent = color(theme.colors.accent, 0xFF5EE0C0.toInt()),
                label = color(theme.colors.label, 0xFFFFFFFF.toInt()),
                labelOnAccent = color(theme.colors.labelOnAccent, 0xFF000000.toInt()),
                keyBorder = color(theme.colors.keyBorder, 0x00000000),
                keyRadius = theme.key.radiusDp * density,
                keyGap = theme.key.gapDp * density,
                labelSize = theme.key.labelSizeSp * density,
                pressedScale = theme.key.pressedScale,
                lanePx = theme.flight.laneDp * density,
                flightEnabled = theme.flight.enabled,
                allowFallbackLane = theme.flight.allowFallbackLane,
                flightConfig = FlightConfig(
                    durationMs = theme.flight.durationMs,
                    arc = theme.flight.arc,
                    startScale = theme.flight.startScale,
                    endScale = theme.flight.endScale,
                    trail = theme.flight.trail,
                ),
                pressHaptic = theme.haptics.press,
                landHaptic = theme.haptics.land,
                minRowHeight = 46f * density,
            )
        }
    }
}
