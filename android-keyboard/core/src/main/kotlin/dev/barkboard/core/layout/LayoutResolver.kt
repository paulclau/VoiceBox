package dev.barkboard.core.layout

/**
 * Turns a [LayoutSpec] into pixel geometry. Pure: same inputs, same boxes, no Android types,
 * so the theme editor can preview a layout without an input connection.
 */
object LayoutResolver {

    fun resolve(layout: LayoutSpec, metrics: KeyboardMetrics): ResolvedKeyboard {
        val usable = metrics.width - 2f * metrics.horizontalPadding
        if (usable <= 0f || layout.rows.isEmpty()) {
            return ResolvedKeyboard(emptyList(), 0f, metrics)
        }

        val boxes = ArrayList<KeyBox>()
        var y = 0f

        layout.rows.forEachIndexed { rowIndex, row ->
            val rowHeight = metrics.rowHeight * row.height
            val keys = row.keys.filter { it.width > 0f }

            if (rowHeight <= 0f || keys.isEmpty()) {
                y += rowHeight.coerceAtLeast(0f) + metrics.gap
                return@forEachIndexed
            }

            var units = 0f
            keys.forEach { units += it.width }

            val gaps = metrics.gap * (keys.size - 1)
            val unitWidth = (usable - gaps) / units
            if (unitWidth <= 0f) {
                // Keys cannot fit: skip the row rather than emit overlapping boxes.
                y += rowHeight + metrics.gap
                return@forEachIndexed
            }

            var x = metrics.horizontalPadding
            keys.forEachIndexed { i, key ->
                val w = unitWidth * key.width
                boxes += KeyBox(
                    row = rowIndex,
                    indexInRow = i,
                    spec = key,
                    kind = KeyKind.fromWire(key.kind) ?: KeyKind.CHAR,
                    left = x,
                    top = y,
                    right = x + w,
                    bottom = y + rowHeight,
                )
                x += w + metrics.gap
            }

            y += rowHeight + metrics.gap
        }

        return ResolvedKeyboard(boxes, (y - metrics.gap).coerceAtLeast(0f), metrics)
    }
}
