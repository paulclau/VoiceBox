package dev.barkboard.core.flight

import kotlin.math.hypot
import kotlin.math.sqrt

data class Vec2(val x: Float, val y: Float) {
    fun distanceTo(other: Vec2): Float = hypot(other.x - x, other.y - y)

    fun lerpTo(other: Vec2, t: Float): Vec2 =
        Vec2(x + (other.x - x) * t, y + (other.y - y) * t)
}

/**
 * Tuning for one flying glyph.
 *
 * [arc] is how far the path bows away from the straight line, as a fraction of the
 * distance. The sign picks the side, so a theme can sweep letters up-left or up-right.
 */
data class FlightConfig(
    val durationMs: Int = 200,
    val arc: Float = 0.35f,
    val startScale: Float = 1.25f,
    val endScale: Float = 0.85f,
    val trail: Boolean = true,
)

/**
 * Pure geometry + easing for the "letter flies from the key and lands at the caret"
 * animation. Kept out of the View so it can be unit tested and previewed by the theme
 * editor.
 */
object Flight {

    const val MIN_DURATION_MS = 120
    const val MAX_DURATION_MS = 260

    /** Short hops feel wrong at a fixed duration, so scale with distance and clamp. */
    fun durationMs(config: FlightConfig, from: Vec2, to: Vec2): Int {
        val distance = from.distanceTo(to)
        val reference = 400f
        val factor = (distance / reference).coerceIn(0.65f, 1.25f)
        return (config.durationMs * factor).toInt().coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    /**
     * Cubic Bézier with both control points offset perpendicular to the straight line,
     * which gives a single clean bow rather than an S-curve.
     */
    fun pointAt(from: Vec2, to: Vec2, t: Float, arc: Float): Vec2 {
        val clamped = t.coerceIn(0f, 1f)
        val (c1, c2) = controlPoints(from, to, arc)
        val u = 1f - clamped
        val a = u * u * u
        val b = 3f * u * u * clamped
        val c = 3f * u * clamped * clamped
        val d = clamped * clamped * clamped
        return Vec2(
            a * from.x + b * c1.x + c * c2.x + d * to.x,
            a * from.y + b * c1.y + c * c2.y + d * to.y,
        )
    }

    fun controlPoints(from: Vec2, to: Vec2, arc: Float): Pair<Vec2, Vec2> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.001f) {
            val same1 = from.lerpTo(to, 1f / 3f)
            val same2 = from.lerpTo(to, 2f / 3f)
            return same1 to same2
        }
        val bow = arc * length
        // Perpendicular of the straight line.
        val px = -dy / length * bow
        val py = dx / length * bow
        val first = from.lerpTo(to, 1f / 3f)
        val second = from.lerpTo(to, 2f / 3f)
        return Vec2(first.x + px, first.y + py) to Vec2(second.x + px, second.y + py)
    }

    /** Overshoots slightly before settling: the letter "arrives" instead of stopping dead. */
    fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val p = t.coerceIn(0f, 1f) - 1f
        return 1f + c3 * p * p * p + c1 * p * p
    }

    fun scaleAt(config: FlightConfig, t: Float): Float {
        val eased = easeOutBack(t)
        return config.startScale + (config.endScale - config.startScale) * eased
    }

    fun alphaAt(t: Float): Float {
        // Full opacity until the final 30%, then fade into the field.
        if (t <= 0.7f) return 1f
        return ((1f - t) / 0.3f).coerceIn(0f, 1f)
    }
}
