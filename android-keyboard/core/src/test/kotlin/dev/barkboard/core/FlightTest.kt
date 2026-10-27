package dev.barkboard.core

import dev.barkboard.core.flight.Flight
import dev.barkboard.core.flight.FlightConfig
import dev.barkboard.core.flight.ParticlePool
import dev.barkboard.core.flight.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlightTest {

    @Test
    fun `path starts at the key and ends at the caret`() {
        val from = Vec2(100f, 900f)
        val to = Vec2(300f, 400f)

        val start = Flight.pointAt(from, to, 0f, 0.35f)
        val end = Flight.pointAt(from, to, 1f, 0.35f)

        assertEquals(from, start)
        assertEquals(to, end)
        assertEquals(to, Flight.pointAt(from, to, 4f, 0.35f), "t is clamped")
    }

    @Test
    fun `path bows away from the straight line`() {
        val from = Vec2(0f, 0f)
        val to = Vec2(100f, 0f)

        val mid = Flight.pointAt(from, to, 0.5f, 0.35f)

        assertEquals(50f, mid.x, 0.01f)
        assertEquals(26.25f, mid.y, 0.01f) // 0.75 * (0.35 * 100)
    }

    @Test
    fun `a negative arc bows the other way`() {
        val mid = Flight.pointAt(Vec2(0f, 0f), Vec2(100f, 0f), 0.5f, -0.35f)
        assertEquals(-26.25f, mid.y, 0.01f)
    }

    @Test
    fun `zero-length flight does not divide by zero`() {
        val point = Vec2(10f, 10f)
        assertEquals(point, Flight.pointAt(point, point, 0.5f, 0.5f))
    }

    @Test
    fun `easing overshoots then settles`() {
        assertEquals(0f, Flight.easeOutBack(0f), 0.0001f)
        assertEquals(1f, Flight.easeOutBack(1f), 0.0001f)
        assertTrue(Flight.easeOutBack(0.8f) > 1f, "expected a settle overshoot")

        val config = FlightConfig(startScale = 1.25f, endScale = 0.85f)
        assertEquals(1.25f, Flight.scaleAt(config, 0f), 0.0001f)
        assertEquals(0.85f, Flight.scaleAt(config, 1f), 0.0001f)
    }

    @Test
    fun `alpha holds then fades out`() {
        assertEquals(1f, Flight.alphaAt(0.2f))
        assertEquals(0.5f, Flight.alphaAt(0.85f), 0.01f)
        assertEquals(0f, Flight.alphaAt(1f), 0.01f)
    }

    @Test
    fun `duration scales with distance and stays inside the clamp`() {
        val config = FlightConfig(durationMs = 200)

        assertEquals(200, Flight.durationMs(config, Vec2(0f, 0f), Vec2(400f, 0f)))
        // Short hops shrink by the 0.65 factor, not all the way to the floor.
        assertEquals(130, Flight.durationMs(config, Vec2(0f, 0f), Vec2(5f, 0f)))
        assertEquals(Flight.MIN_DURATION_MS, Flight.durationMs(FlightConfig(durationMs = 60), Vec2(0f, 0f), Vec2(5f, 0f)))
        assertEquals(250, Flight.durationMs(config, Vec2(0f, 0f), Vec2(50_000f, 0f))) // 1.25 factor, under the ceiling
        assertEquals(Flight.MAX_DURATION_MS, Flight.durationMs(FlightConfig(durationMs = 400), Vec2(0f, 0f), Vec2(400f, 0f)))
    }

    @Test
    fun `pool spawns, arrives once, and recycles`() {
        val pool = ParticlePool(capacity = 2)
        val from = Vec2(0f, 100f)
        val to = Vec2(0f, 0f)
        val config = FlightConfig(durationMs = 200)

        assertNotNull(pool.spawn("a", from, to, config, nowMs = 0))
        assertNotNull(pool.spawn("b", from, to, config, nowMs = 0))
        assertEquals(2, pool.activeCount)
        assertNull(pool.spawn("c", from, to, config, nowMs = 0), "pool is bounded")

        val landed = mutableListOf<String>()
        assertEquals(0, pool.update(nowMs = 50, onArrive = { landed += it.glyph }))
        assertEquals(2, pool.update(nowMs = 5_000, onArrive = { landed += it.glyph }))
        assertEquals(listOf("a", "b"), landed)
        assertEquals(0, pool.activeCount)
        assertEquals(0, pool.update(nowMs = 5_100, onArrive = { landed += it.glyph }), "arrives once")

        assertNotNull(pool.spawn("d", from, to, config, nowMs = 6_000), "slot was recycled")
        assertEquals(1, pool.activeCount)
    }

    @Test
    fun `pool exposes active particles for drawing`() {
        val pool = ParticlePool(capacity = 3)
        pool.spawn("x", Vec2(0f, 0f), Vec2(10f, 10f), FlightConfig(), 0)

        val drawn = (0 until pool.capacity).mapNotNull { pool.particleAt(it) }
        assertEquals(1, drawn.size)
        assertEquals("x", drawn.first().glyph)
    }
}
