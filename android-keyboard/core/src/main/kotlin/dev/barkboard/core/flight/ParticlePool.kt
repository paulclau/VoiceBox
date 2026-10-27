package dev.barkboard.core.flight

/**
 * Pooled flying glyph. Slots are allocated once and reused, so tapping does not allocate
 * during the frame callback.
 */
class Particle internal constructor() {
    var glyph: String = ""
        internal set
    var from: Vec2 = Vec2(0f, 0f)
        internal set
    var to: Vec2 = Vec2(0f, 0f)
        internal set
    var startMs: Long = 0L
        internal set
    var durationMs: Int = 1
        internal set
    var config: FlightConfig = FlightConfig()
        internal set
    var arrived: Boolean = false
        internal set

    fun progress(nowMs: Long): Float {
        if (durationMs <= 0) return 1f
        return ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
    }

    fun positionAt(nowMs: Long): Vec2 = Flight.pointAt(from, to, progress(nowMs), config.arc)
}

/**
 * Fixed-capacity particle set. [update] reports arrivals through a callback so the caller
 * can commit text exactly when the glyph lands.
 */
class ParticlePool(val capacity: Int = 12) {

    private val slots = arrayOfNulls<Particle>(capacity)
    private val recycle = Array(capacity) { Particle() }
    private var recycled = capacity

    var activeCount: Int = 0
        private set

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun spawn(
        glyph: String,
        from: Vec2,
        to: Vec2,
        config: FlightConfig,
        nowMs: Long,
    ): Particle? {
        if (recycled == 0) return null
        val index = slots.indexOfFirst { it == null }
        if (index < 0) return null

        val particle = recycle[--recycled]
        particle.glyph = glyph
        particle.from = from
        particle.to = to
        particle.startMs = nowMs
        particle.durationMs = Flight.durationMs(config, from, to)
        particle.config = config
        particle.arrived = false
        slots[index] = particle
        activeCount++
        return particle
    }

    /** Returns how many particles landed this frame. */
    fun update(nowMs: Long, onArrive: (Particle) -> Unit = {}): Int {
        var landed = 0
        for (i in slots.indices) {
            val particle = slots[i] ?: continue
            if (particle.progress(nowMs) < 1f) continue
            if (!particle.arrived) {
                particle.arrived = true
                landed++
                onArrive(particle)
            }
            slots[i] = null
            recycle[recycled++] = particle
            activeCount--
        }
        return landed
    }

    fun clear() {
        for (i in slots.indices) {
            val particle = slots[i] ?: continue
            slots[i] = null
            recycle[recycled++] = particle
        }
        activeCount = 0
    }

    /** Null for an idle slot; draw by iterating `0 until capacity`. */
    fun particleAt(index: Int): Particle? =
        if (index in slots.indices) slots[index] else null
}
