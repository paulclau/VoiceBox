package dev.barkboard.core.theme

import kotlinx.serialization.Serializable

/**
 * Visual spec. Every colour is a hex string validated at load time, so a typo is a
 * readable error rather than a transparent key.
 */
@Serializable
data class Colors(
    val background: String,
    val key: String,
    val keyPressed: String,
    val accent: String,
    val label: String = "#FFFFFFFF",
    val labelOnAccent: String = "#FF000000",
    val keyBorder: String = "#00000000",
)

@Serializable
data class KeyStyle(
    val radiusDp: Float = 12f,
    val gapDp: Float = 6f,
    val labelSizeSp: Float = 22f,
    /** Pressed keys shrink slightly; the flying glyph starts at [FlightStyle.startScale]. */
    val pressedScale: Float = 0.94f,
    /** Optional font asset name; falls back to the platform sans. */
    val fontAsset: String? = null,
)

@Serializable
data class FlightStyle(
    val enabled: Boolean = true,
    val durationMs: Int = 200,
    val arc: Float = 0.35f,
    val startScale: Float = 1.25f,
    val endScale: Float = 0.85f,
    val trail: Boolean = true,
    /** Animate even when the caret position is unknown (falls back to the landing lane). */
    val allowFallbackLane: Boolean = true,
    /**
     * Transparent band above the keys for the glyph to fly through. Costs the app that much
     * height, so it is opt-in (0) and must be validated on a device.
     */
    val laneDp: Float = 0f,
)

@Serializable
data class Haptics(
    val press: String = "light",
    val land: String = "tick",
)

@Serializable
data class Theme(
    val id: String = "",
    val name: String = "",
    val colors: Colors,
    val key: KeyStyle = KeyStyle(),
    val flight: FlightStyle = FlightStyle(),
    val haptics: Haptics = Haptics(),
)

/**
 * The one place the editor and the validator agree on what a legal theme looks like.
 *
 * Sliders read these ranges and [SpecLoader] validates against them, so the editor can never
 * build a theme that the keyboard would then refuse to load.
 */
object ThemeLimits {
    val radiusDp = 0f..40f
    val gapDp = 0f..20f
    val labelSizeSp = 8f..48f
    val pressedScale = 0.6f..1.2f
    val durationMs = 60..400
    val arc = -1f..1f
    val flightScale = 0.2f..3f
    val laneDp = 0f..120f
    val hapticKinds = setOf("none", "light", "medium", "heavy", "tick", "click")
}

/** ARGB packed the same way Android wants it, without depending on Android. */
object ColorSpec {

    fun parse(hex: String): Int? {
        val value = hex.trim()
        if (!value.startsWith("#")) return null
        val body = value.substring(1)
        val rgb = when (body.length) {
            3 -> buildString {
                body.forEach { append(it).append(it) }
            }
            6 -> body
            8 -> body
            else -> return null
        }
        if (rgb.any { it.digitToIntOrNull(16) == null }) return null
        val packed = rgb.toLong(16)
        return when (body.length) {
            6 -> (0xFF000000L or packed).toInt()
            8 -> packed.toInt()
            else -> (0xFF000000L or packed).toInt()
        }
    }
}
