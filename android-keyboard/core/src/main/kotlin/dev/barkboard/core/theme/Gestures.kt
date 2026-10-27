package dev.barkboard.core.theme

import kotlinx.serialization.Serializable

@Serializable
data class GestureMap(
    /** Global bindings keyed by `"<keyId>.<gesture>"` or `"<kind>.<gesture>"`. */
    val bindings: Map<String, String> = emptyMap(),
)

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT;
    val wire: String get() = name.lowercase()
}

object Gestures {

    /** Dominant-axis swipe, or null when the finger stayed inside [threshold]. */
    fun direction(dx: Float, dy: Float, threshold: Float): SwipeDirection? {
        if (threshold <= 0f) return null
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        if (ax < threshold && ay < threshold) return null
        return if (ax >= ay) {
            if (dx > 0f) SwipeDirection.RIGHT else SwipeDirection.LEFT
        } else {
            if (dy > 0f) SwipeDirection.DOWN else SwipeDirection.UP
        }
    }

    /**
     * Key-local binding wins over a global one; the global binding is looked up by the
     * key's id first, then by its kind.
     */
    fun resolve(
        local: Map<String, String>,
        global: GestureMap,
        keyId: String?,
        kindWire: String,
        swipe: SwipeDirection,
    ): String? {
        val gesture = "swipe-${swipe.wire}"
        local[gesture]?.takeIf { it.isNotBlank() }?.let { return it }
        val id = keyId?.takeIf { it.isNotBlank() }
        if (id != null) {
            global.bindings["$id.$gesture"]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return global.bindings["$kindWire.$gesture"]?.takeIf { it.isNotBlank() }
    }
}
