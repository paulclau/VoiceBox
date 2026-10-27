package dev.barkboard.ime.ui

import android.graphics.Matrix
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import dev.barkboard.core.flight.Vec2

/**
 * Turns the caret position inside another app's editor into a point in this view.
 *
 * `CursorAnchorInfo` reports the insertion marker in the editor's local coordinates plus a
 * matrix into screen coordinates, so both have to be applied before subtracting this
 * view's own screen position.
 */
object CursorAnchorMapper {

    private val identity = Matrix()

    fun toView(info: CursorAnchorInfo?, view: View): Vec2? {
        if (info == null || view.width == 0 || view.height == 0) return null

        val horizontal = info.insertionMarkerHorizontal
        // There is no "vertical": the API reports the baseline plus the marker's extent.
        // The baseline is what a letter should land on.
        val vertical = info.insertionMarkerBaseline.let {
            if (it.isNaN()) info.insertionMarkerTop else it
        }
        if (horizontal.isNaN() || vertical.isNaN()) return null

        val point = floatArrayOf(horizontal, vertical)
        (info.matrix ?: identity).mapPoints(point)

        val location = IntArray(2)
        view.getLocationOnScreen(location)

        val x = point[0] - location[0]
        val y = point[1] - location[1]

        // Reject wildly out-of-range reports (stale editors, split screens) rather than
        // animating a glyph to a nonsense point.
        if (x < -view.width.toFloat() || x > 2f * view.width) return null
        if (y < -view.height.toFloat() || y > 2f * view.height) return null

        return Vec2(x, y)
    }
}
