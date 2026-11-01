package dev.brahma.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Drawn in code so the Cards layout needs no clock widget; redrawn on each minute tick.
@SuppressLint("ViewConstructor")
class AnalogClockView(context: Context, private val face: Int, private val hands: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f
        paint.style = Paint.Style.FILL
        paint.color = face
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawRoundRect(bounds, radius * 0.38f, radius * 0.38f, paint)
        paint.color = 0xFFE8EDF5.toInt()
        for (tick in 0 until 60) {
            val major = tick % 5 == 0
            paint.strokeWidth = if (major) radius * 0.035f else radius * 0.015f
            val angle = Math.toRadians(tick * 6.0 - 90)
            val outer = radius * 0.86f
            val inner = if (major) radius * 0.72f else radius * 0.8f
            canvas.drawLine(cx + inner * cos(angle).toFloat(), cy + inner * sin(angle).toFloat(),
                cx + outer * cos(angle).toFloat(), cy + outer * sin(angle).toFloat(), paint)
        }
        val now = LocalTime.now()
        hand(canvas, cx, cy, (now.hour % 12 + now.minute / 60.0) * 30, radius * 0.45f, radius * 0.06f, hands)
        hand(canvas, cx, cy, now.minute * 6.0, radius * 0.68f, radius * 0.04f, hands)
        paint.style = Paint.Style.FILL
        paint.color = hands
        canvas.drawCircle(cx, cy, radius * 0.06f, paint)
    }

    fun tick(spoken: String) {
        contentDescription = "Analog clock, $spoken"
        invalidate()
    }

    private fun hand(canvas: Canvas, cx: Float, cy: Float, degrees: Double, length: Float, width: Float, color: Int) {
        val angle = Math.toRadians(degrees - 90)
        paint.color = color
        paint.strokeWidth = width
        canvas.drawLine(cx, cy, cx + length * cos(angle).toFloat(), cy + length * sin(angle).toFloat(), paint)
    }
}

// Five-cell battery glyph from the Cards reference; the text beside it carries the exact value.
@SuppressLint("ViewConstructor")
class BatteryGlyph(context: Context, private val ink: Int, private val fill: Int) : View(context) {
    var percent: Int? = null
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val stroke = height * 0.08f
        val capWidth = width * 0.06f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = ink
        box.set(stroke, stroke, width - capWidth - stroke, height - stroke)
        canvas.drawRoundRect(box, height * 0.18f, height * 0.18f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(width - capWidth, height * 0.32f, width.toFloat(), height * 0.68f, paint)
        val cells = ((percent ?: 0) + 19) / 20
        val gap = stroke * 1.4f
        val cellWidth = (box.width() - gap * 6) / 5
        paint.color = fill
        for (cell in 0 until cells.coerceIn(0, 5)) {
            val left = box.left + gap + cell * (cellWidth + gap)
            canvas.drawRect(left, box.top + gap, left + cellWidth, box.bottom - gap, paint)
        }
    }
}

// Line art from the Arches reference: concentric arches top and bottom right, three plus signs.
@SuppressLint("ViewConstructor")
class LineArt(context: Context, private val color: Int, private val screenHeight: Int) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = min(height, screenHeight).toFloat()
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = w * 0.005f
        val gap = w * 0.03f
        for (ring in 0 until 5) {
            // Top: a U hanging from the top edge, right of the month.
            arch(canvas, w * 0.84f, 0f, h * 0.06f, w * 0.15f - ring * gap, opensUp = true)
            // Bottom: an arch standing on the bottom edge.
            arch(canvas, w * 0.82f, h, h * 0.82f, w * 0.2f - ring * gap, opensUp = false)
        }
        val size = w * 0.16f
        plus(canvas, w * 0.74f, h * 0.36f, size, filled = false)
        plus(canvas, w * 0.86f, h * 0.45f, size, filled = true)
        plus(canvas, w * 0.76f, h * 0.55f, size, filled = false)
    }

    private fun arch(canvas: Canvas, cx: Float, edge: Float, bend: Float, r: Float, opensUp: Boolean) {
        path.reset()
        path.moveTo(cx - r, edge)
        path.lineTo(cx - r, bend)
        val box = RectF(cx - r, bend - r, cx + r, bend + r)
        if (opensUp) path.arcTo(box, 180f, -180f) else path.arcTo(box, 180f, 180f)
        path.lineTo(cx + r, edge)
        canvas.drawPath(path, stroke)
    }

    private fun plus(canvas: Canvas, cx: Float, cy: Float, size: Float, filled: Boolean) {
        val arm = size / 6
        path.reset()
        path.moveTo(cx - arm, cy - size / 2)
        path.lineTo(cx + arm, cy - size / 2)
        path.lineTo(cx + arm, cy - arm)
        path.lineTo(cx + size / 2, cy - arm)
        path.lineTo(cx + size / 2, cy + arm)
        path.lineTo(cx + arm, cy + arm)
        path.lineTo(cx + arm, cy + size / 2)
        path.lineTo(cx - arm, cy + size / 2)
        path.lineTo(cx - arm, cy + arm)
        path.lineTo(cx - size / 2, cy + arm)
        path.lineTo(cx - size / 2, cy - arm)
        path.lineTo(cx - arm, cy - arm)
        path.close()
        canvas.drawPath(path, if (filled) fill else stroke)
    }
}

// Dial reference: large hour, minutes in an outlined pill, and a minute ring that turns so the
// current minute always sits in the pill. Redrawn on each minute tick.
@SuppressLint("ViewConstructor")
class DialClockView(context: Context, private val ink: Int, private val muted: Int, private val use24Hour: Boolean) : View(context) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val pill = RectF()

    override fun onDraw(canvas: Canvas) {
        val now = LocalTime.now()
        val radius = min(width * 0.5f, height * 0.48f)
        val cx = width * 0.42f
        val cy = height / 2f
        for (minute in 0 until 60) {
            val offset = ((minute - now.minute + 90) % 60) - 30 // -30..29 minutes from now
            val angle = Math.toRadians(offset * 6.0)
            val fade = (1f - kotlin.math.abs(offset) / 30f).coerceIn(0.08f, 1f)
            val major = minute % 5 == 0
            line.color = muted
            line.alpha = (255 * fade).toInt()
            line.strokeWidth = radius * if (major) 0.012f else 0.006f
            val inner = radius * if (major) 0.86f else 0.9f
            canvas.drawLine(cx + inner * cos(angle).toFloat(), cy + inner * sin(angle).toFloat(),
                cx + radius * 0.96f * cos(angle).toFloat(), cy + radius * 0.96f * sin(angle).toFloat(), line)
            if (major && kotlin.math.abs(offset) > 4) {
                text.color = muted
                text.alpha = (255 * fade).toInt()
                text.textSize = radius * 0.1f
                val r = radius * 0.72f
                canvas.drawText(minute.toString(), cx + r * cos(angle).toFloat(),
                    cy + r * sin(angle).toFloat() + text.textSize * 0.35f, text)
            }
        }
        // Minute pill on the ring's east side.
        val px = cx + radius * 0.66f
        val ph = radius * 0.24f
        val pw = radius * 0.46f
        pill.set(px - pw / 2, cy - ph / 2, px + pw / 2, cy + ph / 2)
        line.color = ink
        line.alpha = 255
        line.style = Paint.Style.STROKE
        line.strokeWidth = radius * 0.012f
        canvas.drawRoundRect(pill, ph / 2, ph / 2, line)
        line.style = Paint.Style.FILL
        text.color = ink
        text.alpha = 255
        text.textSize = radius * 0.15f
        text.isFakeBoldText = true
        canvas.drawText(now.minute.toString().padStart(2, '0'), px, cy + text.textSize * 0.35f, text)
        text.isFakeBoldText = false
        // Hour, left of the pill.
        val hour = if (use24Hour) now.hour else (now.hour + 11) % 12 + 1
        text.textSize = radius * 0.42f
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(hour.toString().padStart(2, '0'), px - pw / 2 - radius * 0.06f, cy + text.textSize * 0.36f, text)
        text.textAlign = Paint.Align.CENTER
    }

    fun tick(spoken: String) {
        contentDescription = "Clock, $spoken"
        invalidate()
    }
}

// Battery as a slider from the soft UI kit: a pressed-in track, an accent fill that glows up to the
// level, then a raised knob, all in the palette's finish.
@SuppressLint("ViewConstructor")
class LevelSlider(context: Context, private val colors: Palette) : View(context) {
    var percent: Int? = null
        set(value) { field = value; invalidate() }
    private val density = resources.displayMetrics.density
    private val track = colors.depthDrawable(density, 100 * density, Role.FIELD, mix(colors.background, colors.muted, 0.12f))
    private val fill = colors.depthDrawable(density, 100 * density, Role.PRIMARY, glow = true)
    private val knob = colors.depthDrawable(density, 100 * density, Role.KEY,
        if (colors.dark) mix(colors.surface, android.graphics.Color.WHITE, 0.55f) else android.graphics.Color.WHITE)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        listOf(track, fill, knob).forEach { it.callback = this }
        unclipAncestors()
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable) = who === track || who === fill || who === knob || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val radius = height * 0.40f
        val bar = height * 0.17f
        val x = radius + (width - radius * 2) * ((percent ?: 0).coerceIn(0, 100) / 100f)
        track.setBounds(radius.toInt(), (cy - bar).toInt(), (width - radius).toInt(), (cy + bar).toInt())
        track.draw(canvas)
        if (x - radius > bar) {
            fill.setBounds(radius.toInt(), (cy - bar).toInt(), x.toInt(), (cy + bar).toInt())
            fill.draw(canvas)
        }
        knob.setBounds((x - radius).toInt(), (cy - radius).toInt(), (x + radius).toInt(), (cy + radius).toInt())
        knob.draw(canvas)
    }
}
