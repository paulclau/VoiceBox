package dev.barkboard.core.theme

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Colour maths for the theme editor: HSL in, ARGB out, and back.
 *
 * The editor drives three sliders (hue, saturation, lightness) plus alpha, because that is the
 * model people can actually aim with — "darker", "less saturated" — where RGB sliders make
 * almost every position look muddy. Everything here is pure so it is unit-tested on the JVM,
 * and it accepts the same packed ARGB ints [ColorSpec] returns.
 */
object ColorKit {

    /**
     * All components are 0..1. Hue is a position on the wheel; saturation and lightness are a
     * fraction of the way to the extreme.
     */
    data class Hsl(val hue: Float, val saturation: Float, val lightness: Float) {
        fun normalized(): Hsl = Hsl(
            hue = hue.mod(1f),
            saturation = saturation.coerceIn(0f, 1f),
            lightness = lightness.coerceIn(0f, 1f),
        )
    }

    fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    fun toHsl(argb: Int): Hsl {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val lightness = (max + min) * 0.5f

        if (max - min < 1e-6f) return Hsl(0f, 0f, lightness)

        val delta = max - min
        val saturation = if (lightness > 0.5f) {
            delta / (2f - max - min)
        } else {
            delta / (max + min)
        }
        val hue = when (max) {
            r -> ((g - b) / delta + if (g < b) 6f else 0f) / 6f
            g -> ((b - r) / delta + 2f) / 6f
            else -> ((r - g) / delta + 4f) / 6f
        }
        return Hsl(hue.mod(1f), saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
    }

    fun toArgb(hsl: Hsl, alpha: Int = 255): Int {
        val h = hsl.hue.mod(1f)
        val s = hsl.saturation.coerceIn(0f, 1f)
        val l = hsl.lightness.coerceIn(0f, 1f)

        val (r, g, b) = if (s < 1e-6f) {
            Triple(l, l, l)
        } else {
            val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
            val p = 2f * l - q
            Triple(hueToRgb(p, q, h + 1f / 3f), hueToRgb(p, q, h), hueToRgb(p, q, h - 1f / 3f))
        }

        return (alpha.coerceIn(0, 255) shl 24) or
            (channel(r) shl 16) or
            (channel(g) shl 8) or
            channel(b)
    }

    /** `#AARRGGBB`, the form the theme files use, so an edit round-trips through a file. */
    fun toHex(argb: Int): String = "#" + (argb.toLong() and 0xFFFFFFFFL).toString(16)
        .uppercase()
        .padStart(8, '0')

    /** Replaces only the alpha of a hex colour; a malformed input is returned untouched. */
    fun withAlpha(hex: String, alpha: Int): String {
        val parsed = ColorSpec.parse(hex) ?: return hex
        return toHex((parsed and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24))
    }

    /**
     * Relative luminance per WCAG 2, used to pick a readable label colour automatically when a
     * theme's accent or key colour is edited.
     */
    fun luminance(argb: Int): Float {
        fun channel(value: Int): Float {
            val c = value / 255f
            return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** True when text drawn on [background] should be dark rather than light. */
    fun prefersDarkLabel(background: Int): Boolean = luminance(background) > 0.5f

    private fun hueToRgb(p: Float, q: Float, t: Float): Float {
        val tt = t.mod(1f)
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 0.5f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }
    }

    private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()
}
