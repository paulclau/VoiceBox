package dev.barkboard.core

import dev.barkboard.core.layout.KeyHitTester
import dev.barkboard.core.layout.KeyKind
import dev.barkboard.core.layout.KeySpec
import dev.barkboard.core.layout.KeyboardMetrics
import dev.barkboard.core.layout.LayoutResolver
import dev.barkboard.core.layout.LayoutSpec
import dev.barkboard.core.layout.RowSpec
import dev.barkboard.core.theme.SpecLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutTest {

    private fun row(vararg keys: KeySpec, height: Float = 1f) = RowSpec(height, keys.toList())

    private fun char(output: String, width: Float = 1f) = KeySpec(output = output, width = width)

    @Test
    fun `keys fill the usable width exactly`() {
        val layout = LayoutSpec(
            id = "t",
            rows = listOf(row(char("a"), char("b", 2f))),
        )
        val metrics = KeyboardMetrics(width = 320f, rowHeight = 48f, gap = 4f, horizontalPadding = 2f)

        val resolved = LayoutResolver.resolve(layout, metrics)

        assertEquals(2, resolved.boxes.size)
        assertEquals(2f, resolved.boxes[0].left, 0.01f)
        assertEquals(104f, resolved.boxes[0].width, 0.01f)
        assertEquals(110f, resolved.boxes[1].left, 0.01f)
        assertEquals(208f, resolved.boxes[1].width, 0.01f)
        assertEquals(318f, resolved.boxes[1].right, 0.01f)
        assertEquals(48f, resolved.height, 0.01f)
    }

    @Test
    fun `row heights multiply and stack with gaps`() {
        val layout = LayoutSpec(
            id = "t",
            rows = listOf(row(char("a")), row(char("b"), height = 2f)),
        )
        val metrics = KeyboardMetrics(width = 100f, rowHeight = 40f, gap = 10f, horizontalPadding = 0f)

        val resolved = LayoutResolver.resolve(layout, metrics)

        val first = resolved.boxes[0]
        val second = resolved.boxes[1]
        assertEquals(0f, first.top, 0.01f)
        assertEquals(40f, first.bottom, 0.01f)
        assertEquals(50f, second.top, 0.01f)
        assertEquals(130f, second.bottom, 0.01f)
        assertEquals(130f, resolved.height, 0.01f)
    }

    @Test
    fun `non-positive widths are dropped and empty rows skipped`() {
        val layout = LayoutSpec(
            id = "t",
            rows = listOf(
                RowSpec(1f, emptyList()),
                row(char("a", 0f), char("b")),
            ),
        )
        val metrics = KeyboardMetrics(width = 200f, rowHeight = 40f, gap = 0f, horizontalPadding = 0f)

        val resolved = LayoutResolver.resolve(layout, metrics)

        assertEquals(1, resolved.boxes.size)
        assertEquals(KeyKind.CHAR, resolved.boxes.first().kind)
        assertEquals(200f, resolved.boxes.first().width, 0.01f)
    }

    @Test
    fun `unusable width yields nothing rather than overlapping keys`() {
        val layout = LayoutSpec(id = "t", rows = listOf(row(char("a"), char("b"), char("c"))))
        val metrics = KeyboardMetrics(width = 10f, rowHeight = 40f, gap = 100f, horizontalPadding = 5f)

        val resolved = LayoutResolver.resolve(layout, metrics)

        assertTrue(resolved.isEmpty)
        assertEquals(0f, resolved.height, 0.01f)
    }

    @Test
    fun `hit testing finds the key under a point`() {
        val layout = LayoutSpec(id = "t", rows = listOf(row(char("a"), char("b"))))
        val resolved = LayoutResolver.resolve(
            layout,
            KeyboardMetrics(width = 200f, rowHeight = 50f, gap = 0f, horizontalPadding = 0f),
        )
        val tester = KeyHitTester(resolved)

        assertEquals("a", tester.keyAt(10f, 10f)?.spec?.output)
        assertEquals("b", tester.keyAt(150f, 10f)?.spec?.output)
        assertNull(tester.keyAt(10f, 80f))
        assertNull(tester.keyAt(400f, 10f))
    }

    @Test
    fun `slop lets the top row answer a high tap`() {
        val layout = LayoutSpec(id = "t", rows = listOf(row(char("a"))))
        val resolved = LayoutResolver.resolve(
            layout,
            KeyboardMetrics(width = 100f, rowHeight = 50f, gap = 0f, horizontalPadding = 0f),
        )

        assertNull(KeyHitTester(resolved).keyAt(50f, -6f))
        assertEquals("a", KeyHitTester(resolved, verticalSlop = 8f).keyAt(50f, -6f)?.spec?.output)
    }

    @Test
    fun `kind falls back to char for an unknown wire value`() {
        val spec = KeySpec(kind = "teleport", output = "x")
        assertEquals(KeyKind.CHAR, SpecLoader.keyKind(spec))
        assertTrue(SpecLoader.validateLayout(LayoutSpec("t", rows = listOf(row(spec)))).isNotEmpty())
    }
}
