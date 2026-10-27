package dev.barkboard.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import dev.barkboard.core.flight.Flight
import dev.barkboard.core.flight.ParticlePool
import dev.barkboard.core.flight.Vec2
import dev.barkboard.core.layout.KeyBox
import dev.barkboard.core.layout.KeyHitTester
import dev.barkboard.core.layout.KeyKind
import dev.barkboard.core.layout.KeySpec
import dev.barkboard.core.layout.KeyboardMetrics
import dev.barkboard.core.layout.LayoutResolver
import dev.barkboard.core.layout.LayoutSpec
import dev.barkboard.core.layout.ResolvedKeyboard
import dev.barkboard.core.layout.KeyLabels
import dev.barkboard.core.theme.GestureMap
import dev.barkboard.core.theme.Gestures
import kotlin.math.max

/**
 * The keyboard surface: keys, press feedback, swipe gestures, long-press alternates, and the
 * flying glyph that carries a tapped letter to the caret.
 *
 * Text is committed by [Listener.onText] when a glyph *lands*, not when the finger lifts, so
 * the animation never disagrees with the field. Keys are drawn into the bottom of the view;
 * the band above them ([laneDp]) is headroom for the flight.
 */
class KeyboardView(context: Context, private val appearance: KeyboardAppearance) : View(context) {

    interface Listener {
        /** Commit this text to the editor. Called once per landed glyph (or immediately when
         *  the animation is disabled / the pool is full). */
        fun onText(text: String)

        /** A non-text key was pressed: shift, backspace, enter, mic, … */
        fun onKey(kind: KeyKind, spec: KeySpec)

        /** A swipe with a binding, e.g. "voice" or "language". */
        fun onGesture(action: String, spec: KeySpec)

        fun onHaptic(kind: String)
    }

    var listener: Listener? = null

    private var layoutSpec: LayoutSpec = LayoutSpec()
    private var gestures: GestureMap = GestureMap()
    private var keyboard: ResolvedKeyboard =
        LayoutResolver.resolve(LayoutSpec(), KeyboardMetrics(0f, 0f))
    private var hitTester = KeyHitTester(keyboard)

    private val particles = ParticlePool(CAPACITY)
    private var lanePx: Float = appearance.lanePx

    private var pressedKey: KeyBox? = null
    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    private var repeated = false
    private var shiftOn = false
    private var landingTarget: Vec2? = null
    private var popup: Popup? = null
    private var recording = false
    private var lastMeasuredWidth = 0

    private val swipeSlop = resources.displayMetrics.density * 24f
    private val rect = RectF()

    private val backgroundPaint = Paint()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        labelPaint.textSize = appearance.labelSize
        particlePaint.textSize = appearance.labelSize
        borderPaint.strokeWidth = resources.displayMetrics.density
        isClickable = true
        isFocusable = false
    }

    fun setSpec(layout: LayoutSpec, gestures: GestureMap) {
        layoutSpec = layout
        this.gestures = gestures
        lastMeasuredWidth = 0
        pressedKey = null
        popup = null
        particles.clear()
        requestLayout()
        invalidate()
    }

    fun toggleShift() {
        shiftOn = !shiftOn
        invalidate()
    }

    /** Caret position in view coordinates, or null when the editor does not report one. */
    fun setLandingTarget(point: Vec2?) {
        landingTarget = point
    }

    /**
     * Height of the transparent band above the keys, in pixels. The service reports it through
     * [android.inputmethodservice.InputMethodService.onComputeInsets] so the lane neither hides
     * the app's content nor swallows its touches.
     */
    val flightLaneHeightPx: Int get() = lanePx.toInt()

    /** Tints the mic key while an utterance is being captured. */
    fun setRecording(value: Boolean) {
        if (recording == value) return
        recording = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        if (width != lastMeasuredWidth) {
            lastMeasuredWidth = width
            rebuild(width)
        }
        val height = (lanePx + keyboard.height).toInt().coerceAtLeast(1)
        setMeasuredDimension(width, height)
    }

    private fun rebuild(width: Int) {
        lanePx = appearance.lanePx
        val rowHeight = max(appearance.minRowHeight, width * ROW_HEIGHT_FRACTION)
        keyboard = LayoutResolver.resolve(
            layoutSpec,
            KeyboardMetrics(
                width = width.toFloat(),
                rowHeight = rowHeight,
                gap = appearance.keyGap,
                horizontalPadding = appearance.keyGap,
            ),
        )
        hitTester = KeyHitTester(keyboard, verticalSlop = appearance.keyGap * 1.5f)
        pressedKey = null
        popup = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Only the keys get a background: the lane above them is a window onto the app, which is
        // what lets a letter be seen travelling towards the text field.
        if (lanePx > 0f) {
            backgroundPaint.color = appearance.background
            canvas.drawRect(0f, lanePx, width.toFloat(), height.toFloat(), backgroundPaint)
        } else {
            canvas.drawColor(appearance.background)
        }

        canvas.save()
        canvas.translate(0f, lanePx)
        for (i in keyboard.boxes.indices) {
            drawKey(canvas, keyboard.boxes[i])
        }
        canvas.restore()

        drawParticles(canvas)
        popup?.let { drawPopup(canvas, it) }
    }

    private fun drawKey(canvas: Canvas, box: KeyBox) {
        val pressed = box === pressedKey
        val scale = if (pressed) appearance.pressedScale else 1f
        val insetX = box.width * (1f - scale) * 0.5f
        val insetY = box.height * (1f - scale) * 0.5f
        rect.set(box.left + insetX, box.top + insetY, box.right - insetX, box.bottom - insetY)

        keyPaint.color = when {
            isRecordingSource(box) && (pressed || recording) -> appearance.accent
            pressed -> appearance.keyPressed
            else -> appearance.key
        }
        canvas.drawRoundRect(rect, appearance.keyRadius, appearance.keyRadius, keyPaint)

        if (appearance.keyBorder != 0) {
            borderPaint.color = appearance.keyBorder
            canvas.drawRoundRect(rect, appearance.keyRadius, appearance.keyRadius, borderPaint)
        }

        val label = labelFor(box)
        if (label.isEmpty()) return
        labelPaint.color = if (box.kind == KeyKind.SHIFT && shiftOn) appearance.accent else appearance.label
        canvas.drawText(
            label,
            box.centerX,
            box.centerY - (labelPaint.ascent() + labelPaint.descent()) * 0.5f,
            labelPaint,
        )
    }

    private fun isRecordingSource(box: KeyBox): Boolean = box.kind == KeyKind.MIC

    private fun labelFor(box: KeyBox): String = KeyLabels.of(box.spec, box.kind, shiftOn)

    private fun drawParticles(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        for (i in 0 until particles.capacity) {
            val particle = particles.particleAt(i) ?: continue
            val progress = particle.progress(now)
            val eased = Flight.easeOutBack(progress).coerceIn(0f, 1f)
            val point = Flight.pointAt(particle.from, particle.to, eased, particle.config.arc)

            if (particle.config.trail) drawTrail(canvas, particle, progress)

            val scale = Flight.scaleAt(particle.config, progress)
            val alpha = (255f * Flight.alphaAt(progress)).toInt().coerceIn(0, 255)

            canvas.save()
            canvas.translate(point.x, point.y)
            canvas.scale(scale, scale)
            particlePaint.color = appearance.label
            particlePaint.alpha = alpha
            canvas.drawText(
                particle.glyph,
                0f,
                -(particlePaint.ascent() + particlePaint.descent()) * 0.5f,
                particlePaint,
            )
            canvas.restore()
        }
    }

    private fun drawTrail(canvas: Canvas, particle: dev.barkboard.core.flight.Particle, progress: Float) {
        val steps = 4
        for (step in 1..steps) {
            val t = progress - step * 0.04f
            if (t <= 0f) break
            val point = Flight.pointAt(
                particle.from,
                particle.to,
                Flight.easeOutBack(t).coerceIn(0f, 1f),
                particle.config.arc,
            )
            trailPaint.color = appearance.accent
            trailPaint.alpha = (70f / step).toInt().coerceIn(0, 255)
            canvas.drawCircle(point.x, point.y, appearance.labelSize * 0.16f, trailPaint)
        }
    }

    private fun drawPopup(canvas: Canvas, popup: Popup) {
        val box = popup.box
        val stripWidth = popup.itemWidth * popup.items.size
        val left = popup.stripLeft
        val top = (lanePx + box.top - box.height * 1.2f).coerceAtLeast(resources.displayMetrics.density)
        rect.set(left, top, left + stripWidth, top + box.height)

        keyPaint.color = appearance.keyPressed
        canvas.drawRoundRect(rect, appearance.keyRadius, appearance.keyRadius, keyPaint)

        popup.items.forEachIndexed { index, item ->
            val centerX = left + popup.itemWidth * (index + 0.5f)
            labelPaint.color = if (index == popup.index) appearance.accent else appearance.label
            canvas.drawText(
                item,
                centerX,
                top + box.height * 0.5f - (labelPaint.ascent() + labelPaint.descent()) * 0.5f,
                labelPaint,
            )
        }
    }

    // ---- touch ------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> handleMove(event.x, event.y)
            MotionEvent.ACTION_UP -> handleUp(event.x, event.y)
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    private fun handleDown(x: Float, y: Float) {
        val box = hitTester.keyAt(x, y - lanePx)
        if (box == null) {
            cancelTouch()
            return
        }
        removeCallbacks(longPressRunnable)
        removeCallbacks(repeatRunnable)

        pressedKey = box
        downX = x
        downY = y
        swiped = false
        repeated = false
        popup = null

        listener?.onHaptic(appearance.pressHaptic)
        if (box.spec.longPress.isNotEmpty()) postDelayed(longPressRunnable, LONG_PRESS_MS)
        if (box.kind == KeyKind.BACKSPACE) postDelayed(repeatRunnable, REPEAT_DELAY_MS)
        invalidate()
    }

    private fun handleMove(x: Float, y: Float) {
        val box = pressedKey ?: return
        val current = popup
        if (current != null) {
            popup = current.selectAt(x)
            invalidate()
            return
        }
        if (!swiped && Gestures.direction(x - downX, y - downY, swipeSlop) != null) {
            swiped = true
            removeCallbacks(longPressRunnable)
            removeCallbacks(repeatRunnable)
        }
        invalidate()
    }

    private fun handleUp(x: Float, y: Float) {
        removeCallbacks(longPressRunnable)
        removeCallbacks(repeatRunnable)

        val box = pressedKey
        val openPopup = popup
        val didRepeat = repeated
        pressedKey = null
        popup = null
        swiped = false
        repeated = false

        if (box == null) {
            invalidate()
            return
        }

        when {
            openPopup != null -> typeGlyph(openPopup.selected(), box)

            swiped -> {
                val direction = Gestures.direction(x - downX, y - downY, swipeSlop)
                val action = direction?.let {
                    Gestures.resolve(box.spec.gesture, gestures, box.spec.id, box.kind.wire, it)
                }
                if (action != null) listener?.onGesture(action, box.spec)
            }

            didRepeat && box.kind == KeyKind.BACKSPACE -> Unit

            box.kind == KeyKind.CHAR -> {
                val output = box.spec.output.orEmpty()
                val text = if (shiftOn) output.uppercase() else output
                if (shiftOn) shiftOn = false
                typeGlyph(text, box)
            }

            box.kind == KeyKind.SHIFT -> toggleShift()

            else -> listener?.onKey(box.kind, box.spec)
        }
        invalidate()
    }

    private fun cancelTouch() {
        removeCallbacks(longPressRunnable)
        removeCallbacks(repeatRunnable)
        pressedKey = null
        popup = null
        swiped = false
        repeated = false
        invalidate()
    }

    private fun typeGlyph(text: String, box: KeyBox) {
        if (text.isEmpty()) return
        if (!appearance.flightEnabled) {
            listener?.onText(text)
            return
        }
        val from = Vec2(box.centerX, box.centerY + lanePx)
        val spawned = particles.spawn(
            glyph = text,
            from = from,
            to = targetFor(from),
            config = appearance.flightConfig,
            nowMs = SystemClock.uptimeMillis(),
        )
        if (spawned == null) {
            // Pool full (very fast typing): never drop the character, just skip its flight.
            listener?.onText(text)
            return
        }
        startFrames()
    }

    private fun targetFor(from: Vec2): Vec2 {
        val anchor = landingTarget
        if (anchor != null) {
            val usable = appearance.allowFallbackLane || anchor.y > lanePx
            if (usable) {
                return Vec2(
                    x = anchor.x.coerceIn(0f, width.toFloat()),
                    y = anchor.y.coerceIn(0f, height.toFloat()),
                )
            }
        }
        // No caret information: fly to the top of the keyboard and fade there.
        return Vec2(x = from.x.coerceIn(0f, width.toFloat()), y = 0f)
    }

    // ---- frame clock ------------------------------------------------------------------

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = frameTimeNanos / 1_000_000L
            particles.update(now) { particle ->
                listener?.onText(particle.glyph)
                listener?.onHaptic(appearance.landHaptic)
            }
            invalidate()
            if (particles.activeCount > 0) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startFrames() {
        val choreographer = Choreographer.getInstance()
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
    }

    private val longPressRunnable = Runnable {
        val box = pressedKey ?: return@Runnable
        if (box.spec.longPress.isEmpty()) return@Runnable
        popup = Popup.create(box, box.spec.longPress, width.toFloat())
        listener?.onHaptic(appearance.pressHaptic)
        invalidate()
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val box = pressedKey ?: return
            if (box.kind != KeyKind.BACKSPACE || swiped) return
            repeated = true
            listener?.onKey(KeyKind.BACKSPACE, box.spec)
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        removeCallbacks(repeatRunnable)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        particles.clear()
        super.onDetachedFromWindow()
    }

    /** Long-press alternate strip. */
    private class Popup private constructor(
        val box: KeyBox,
        val items: List<String>,
        val index: Int,
        val itemWidth: Float,
        val stripLeft: Float,
    ) {
        fun selected(): String = items[index]

        fun selectAt(x: Float): Popup {
            val raw = ((x - stripLeft) / itemWidth).toInt()
            val clamped = raw.coerceIn(0, items.size - 1)
            return Popup(box, items, clamped, itemWidth, stripLeft)
        }

        companion object {
            fun create(box: KeyBox, items: List<String>, viewWidth: Float): Popup {
                val itemWidth = max(box.width, 1f)
                val stripWidth = itemWidth * items.size
                val left = (box.centerX - stripWidth * 0.5f)
                    .coerceIn(0f, (viewWidth - stripWidth).coerceAtLeast(0f))
                return Popup(box, items, 0, itemWidth, left)
            }
        }
    }

    private companion object {
        const val CAPACITY = 16
        const val ROW_HEIGHT_FRACTION = 0.115f
        const val LONG_PRESS_MS = 380L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
    }
}
