package dev.barkboard.core.layout

/**
 * Point → key. [verticalSlop] lets the top row answer taps slightly above the keyboard
 * (fingers aim high) and the bottom row answers taps on its lower edge.
 */
class KeyHitTester(
    private val keyboard: ResolvedKeyboard,
    private val verticalSlop: Float = 0f,
) {

    fun keyAt(x: Float, y: Float): KeyBox? {
        if (keyboard.isEmpty) return null

        for (box in keyboard.boxes) {
            if (box.contains(x, y)) return box
        }
        if (verticalSlop <= 0f) return null

        for (box in keyboard.boxes) {
            if (y < box.top - verticalSlop || y > box.bottom + verticalSlop) continue
            if (x >= box.left - verticalSlop && x < box.right + verticalSlop) return box
        }
        return null
    }
}
