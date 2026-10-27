package dev.barkboard.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.barkboard.core.flight.Flight
import dev.barkboard.core.flight.ParticlePool
import dev.barkboard.core.flight.Vec2
import dev.barkboard.core.layout.KeyBox
import dev.barkboard.core.layout.KeyLabels
import dev.barkboard.core.layout.KeyboardMetrics
import dev.barkboard.core.layout.LayoutResolver
import dev.barkboard.core.layout.LayoutSpec
import dev.barkboard.core.layout.ResolvedKeyboard
import dev.barkboard.core.theme.Theme
import dev.barkboard.ime.theme.Defaults
import kotlin.math.max

/**
 * A miniature keyboard drawn with the theme currently being edited: a strip standing in for the
 * text field, three rows of keys, and a live flying glyph when a key is tapped.
 *
 * It exists because theme numbers are not legible on a slider. Radius, gap, label size, press
 * scale, flight duration, arc and both scales are all things you judge by looking at them, and a
 * preview that runs the real geometry ([LayoutResolver]) and the real animation ([Flight]) cannot
 * drift from what the keyboard will do.
 */
class ThemePreviewView(context: Context) : View(context) {

    private var appearance: KeyboardAppearance =
        KeyboardAppearance.from(Defaults.theme, resources.displayMetrics.density)
    private var layout: LayoutSpec = Defaults.layout
    private var keyboard: ResolvedKeyboard = ResolvedKeyboard(emptyList(), 0f, KeyboardMetrics(1f, 1f))

    private val particles = ParticlePool(capacity = 6)
    private var pressedIndex = -1

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    private val density: Float get() = resources.displayMetrics.density

    /** Height of the stand-in text field, above the keys. */
    private val fieldHeight: Float get() = 54f * density

    private var caret: Vec2 = Vec2(0f, 0f)

    private val frame = object : Runnable {
        override fun run() {
            if (particles.activeCount == 0) return
            particles.update(SystemClock.uptimeMillis())
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setAppearance(value: KeyboardAppearance) {
        appearance = value
        labelPaint.textSize = value.labelSize
        rebuild(width.toFloat(), height.toFloat())
        invalidate()
    }

    /** The rows the preview shows; the caller passes the same layout the keyboard is using. */
    fun setLayout(spec: LayoutSpec) {
        layout = spec
        rebuild(width.toFloat(), height.toFloat())
        invalidate()
    }

    fun setTheme(theme: Theme) {
        setAppearance(KeyboardAppearance.from(theme, density))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild(w.toFloat(), h.toFloat())
    }

    private fun rebuild(widthF: Float, heightF: Float) {
        if (widthF <= 0f || heightF <= 0f) return

        val gap = appearance.keyGap
        val keyArea = (heightF - fieldHeight).coerceAtLeast(1f)
        val rowHeight = max(appearance.minRowHeight * 0.85f, (keyArea - gap * 2f) / 3f)

        // Three rows is enough to judge shape and colour without a squashed preview.
        val rows = layout.rows.take(3).ifEmpty { Defaults.layout.rows.take(3) }
        keyboard = LayoutResolver.resolve(
            layout.copy(rows = rows),
            KeyboardMetrics(
                width = widthF,
                rowHeight = rowHeight,
                gap = gap,
                horizontalPadding = gap,
            ),
        )

        caret = Vec2(widthF * 0.58f, fieldHeight * 0.56f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(appearance.background)
        drawField(canvas)

        canvas.save()
        canvas.translate(0f, fieldHeight)
        for (box in keyboard.boxes) drawKey(canvas, box)
        canvas.restore()

        drawParticles(canvas)
    }

    private fun drawField(canvas: Canvas) {
        fieldPaint.color = (appearance.label and 0x00FFFFFF) or (0x14 shl 24)
        rect.set(appearance.keyGap, appearance.keyGap * 0.5f, width - appearance.keyGap, fieldHeight - appearance.keyGap)
        canvas.drawRoundRect(rect, appearance.keyRadius * 0.6f, appearance.keyRadius * 0.6f, fieldPaint)

        // A caret for the glyph to land on. Short enough to read as a cursor, not a letter.
        caretPaint.color = appearance.accent
        caretPaint.strokeWidth = 2f * density
        canvas.drawLine(caret.x, caret.y - 15f * density, caret.x, caret.y + 6f * density, caretPaint)
    }

    private fun drawKey(canvas: Canvas, box: KeyBox) {
        val pressed = keyboard.boxes.indexOf(box) == pressedIndex
        val scale = if (pressed) appearance.pressedScale else 1f
        val insetX = box.width * (1f - scale) * 0.5f
        val insetY = box.height * (1f - scale) * 0.5f
        rect.set(box.left + insetX, box.top + insetY, box.right - insetX, box.bottom - insetY)

        keyPaint.color = if (pressed) appearance.keyPressed else appearance.key
        canvas.drawRoundRect(rect, appearance.keyRadius, appearance.keyRadius, keyPaint)

        if (appearance.keyBorder != 0) {
            borderPaint.color = appearance.keyBorder
            canvas.drawRoundRect(rect, appearance.keyRadius, appearance.keyRadius, borderPaint)
        }

        val label = KeyLabels.of(box.spec, box.kind)
        if (label.isEmpty()) return
        labelPaint.color = appearance.label
        canvas.drawText(
            label,
            box.centerX,
            box.centerY - (labelPaint.ascent() + labelPaint.descent()) * 0.5f,
            labelPaint,
        )
    }

    private fun drawParticles(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        for (i in 0 until particles.capacity) {
            val particle = particles.particleAt(i) ?: continue
            val progress = particle.progress(now)
            val eased = Flight.easeOutBack(progress).coerceIn(0f, 1f)
            val point = Flight.pointAt(particle.from, particle.to, eased, particle.config.arc)
            val alpha = (Flight.alphaAt(progress) * 255f).toInt()

            if (particle.config.trail) {
                trailPaint.color = (appearance.accent and 0x00FFFFFF) or ((alpha / 3) shl 24)
                canvas.drawLine(particle.from.x, particle.from.y, point.x, point.y, trailPaint)
            }

            val scale = Flight.scaleAt(particle.config, progress)
            val size = appearance.labelSize * scale
            canvas.save()
            canvas.translate(point.x, point.y)
            canvas.scale(scale, scale)
            val paint = Paint(labelPaint)
            paint.textSize = size / scale
            paint.alpha = alpha
            paint.color = appearance.label
            canvas.drawText(particle.glyph, 0f, -(paint.ascent() + paint.descent()) * 0.5f, paint)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = indexAt(event.x, event.y - fieldHeight)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = indexAt(event.x, event.y - fieldHeight)
                if (index != pressedIndex) {
                    pressedIndex = index
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val index = pressedIndex
                pressedIndex = -1
                if (index >= 0 && appearance.flightEnabled) {
                    val box = keyboard.boxes[index]
                    particles.spawn(
                        glyph = KeyLabels.of(box.spec, box.kind).ifEmpty { "·" },
                        from = Vec2(box.centerX, box.centerY + fieldHeight),
                        to = caret,
                        config = appearance.flightConfig,
                        nowMs = SystemClock.uptimeMillis(),
                    )
                    postOnAnimation(frame)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun indexAt(x: Float, y: Float): Int =
        keyboard.boxes.indexOfFirst { it.contains(x, y) }
}
