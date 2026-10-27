package dev.barkboard.core.layout

import kotlinx.serialization.Serializable

/**
 * One key in a layout file. Widths are in key units; the resolver scales each row so its
 * keys fill the usable width exactly.
 */
@Serializable
data class KeySpec(
    val kind: String = "char",
    /** Text a CHAR key types. Required for CHAR, ignored otherwise. */
    val output: String? = null,
    /** Label drawn on the key; defaults to [output]. */
    val label: String? = null,
    val width: Float = 1f,
    /** Alternate characters offered by a long press. */
    val longPress: List<String> = emptyList(),
    /** Swipe bindings local to this key, e.g. `{"swipe-up": "voice"}`. */
    val gesture: Map<String, String> = emptyMap(),
    /** Stable name used by global gesture bindings and per-app overrides. */
    val id: String? = null,
)

@Serializable
data class RowSpec(
    /** Multiplier of the base row height. */
    val height: Float = 1f,
    val keys: List<KeySpec> = emptyList(),
)

@Serializable
data class LayoutSpec(
    val id: String = "",
    val name: String = "",
    /** Gap and outer padding live in the theme; a layout only describes structure. */
    val rows: List<RowSpec> = emptyList(),
)

/** A key resolved to pixels. */
data class KeyBox(
    val row: Int,
    val indexInRow: Int,
    val spec: KeySpec,
    val kind: KeyKind,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

data class KeyboardMetrics(
    val width: Float,
    val rowHeight: Float,
    val gap: Float = 6f,
    val horizontalPadding: Float = 2f,
)

data class ResolvedKeyboard(
    val boxes: List<KeyBox>,
    val height: Float,
    val metrics: KeyboardMetrics,
) {
    val isEmpty: Boolean get() = boxes.isEmpty()
}
