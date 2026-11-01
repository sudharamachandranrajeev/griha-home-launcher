package dev.brahma.launcher

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * Looks for the big lock-screen text. [font] is a bundled font the look needs; [colour] is the colour used
 * when the user has not picked one. Looks that are metal, glass or gold keep their own colours.
 */
enum class LockStyle(val title: String, val font: Int? = null, val colour: Int? = null) {
    PLAIN("Plain"),
    EXTRUDE("3D extrusion"),
    GLASS("Glossy glass"),
    MATTE("Matte, drop shadow"),
    CHROME("Chrome"),
    GOLD("Gold foil"),
    CYBER("Cyber neon glitch", colour = 0xFF22E5FF.toInt()),
    WARLORD("Medieval warlord", font = R.font.unifraktur_cook),
    WITCHER("Witcher steel", font = R.font.cinzel)
}

/** Draws one line of text in a [LockStyle]. Everything is drawn from the text's outline, so it works with any font. */
object TextFx {
    fun draw(canvas: Canvas, text: String, x: Float, y: Float, base: Paint, style: LockStyle, color: Int) {
        val paint = Paint(base).apply { textAlign = Paint.Align.LEFT; this.color = color }
        val size = paint.textSize
        val left = if (base.textAlign == Paint.Align.CENTER) x - paint.measureText(text) / 2 else x
        if (style == LockStyle.PLAIN) return canvas.drawText(text, left, y, paint)
        val path = Path()
        paint.getTextPath(text, 0, text.length, left, y, path)
        val box = RectF().also { path.computeBounds(it, true) }
        when (style) {
            LockStyle.EXTRUDE -> extrude(canvas, path, box, size, color)
            LockStyle.GLASS -> glass(canvas, path, box, size, color)
            LockStyle.MATTE -> matte(canvas, path, size, color)
            LockStyle.CHROME -> metal(canvas, path, box, size, CHROME_STOPS, CHROME_AT, 0xFF1B2230.toInt(), 0x66000000)
            LockStyle.GOLD -> metal(canvas, path, box, size, GOLD_STOPS, GOLD_AT, 0xFF5A3A08.toInt(), 0x66000000)
            LockStyle.CYBER -> cyber(canvas, path, box, size, color, text.length)
            LockStyle.WARLORD -> forged(canvas, path, box, size, color, WARLORD_STOPS, 0xFFFF6A00.toInt(), false)
            LockStyle.WITCHER -> forged(canvas, path, box, size, color, WITCHER_STOPS, 0xFF9B0F18.toInt(), true)
            LockStyle.PLAIN -> Unit
        }
    }

    private val CHROME_STOPS = intArrayOf(0xFFF7FAFF.toInt(), 0xFFB8C6DA.toInt(), 0xFF2A3342.toInt(), 0xFF8EA0B8.toInt(), 0xFFFFFFFF.toInt())
    private val CHROME_AT = floatArrayOf(0f, 0.44f, 0.5f, 0.58f, 1f)
    private val GOLD_STOPS = intArrayOf(0xFFFFF3B0.toInt(), 0xFFE8B83A.toInt(), 0xFF8A5A12.toInt(), 0xFFF7D774.toInt(), 0xFFB07A1C.toInt())
    private val GOLD_AT = floatArrayOf(0f, 0.3f, 0.52f, 0.72f, 1f)
    private val WARLORD_STOPS = intArrayOf(0xFF9A9FA7.toInt(), 0xFF4A4F57.toInt(), 0xFF1D2025.toInt(), 0xFF55595F.toInt())
    private val WITCHER_STOPS = intArrayOf(0xFFF2F4F7.toInt(), 0xFFB9C0C9.toInt(), 0xFF6D7681.toInt(), 0xFFD7DCE2.toInt())

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
    }

    private fun gradient(box: RectF, colors: IntArray, at: FloatArray? = null) =
        LinearGradient(0f, box.top, 0f, box.bottom, colors, at, Shader.TileMode.CLAMP)

    // Stacked copies pushed down and right make the sides; the front is lit from above.
    private fun extrude(canvas: Canvas, path: Path, box: RectF, size: Float, color: Int) {
        val depth = (size * 0.09f).toInt().coerceIn(6, 40)
        val side = mix(color, Color.BLACK, 0.55f)
        for (step in depth downTo 1) {
            canvas.save()
            canvas.translate(step * 0.75f, step * 0.75f)
            canvas.drawPath(path, fill(mix(side, Color.BLACK, 0.35f * step / depth)))
            canvas.restore()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient(box, intArrayOf(mix(color, Color.WHITE, 0.4f), color)) })
        canvas.drawPath(path, stroke(0x88FFFFFF.toInt(), size * 0.008f))
    }

    // See-through tinted body, a bright rim and a gloss across the top half.
    private fun glass(canvas: Canvas, path: Path, box: RectF, size: Float, color: Int) {
        val tint = mix(color, Color.WHITE, 0.55f)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = gradient(box, intArrayOf((tint and 0xFFFFFF) or 0x99000000.toInt(), (tint and 0xFFFFFF) or 0x40000000))
            setShadowLayer(size * 0.07f, 0f, size * 0.04f, 0x66000000)
        })
        canvas.drawPath(path, stroke(0xDDFFFFFF.toInt(), size * 0.012f))
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(box, Paint().apply { shader = gradient(box, intArrayOf(0xB0FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF, 0x60FFFFFF), floatArrayOf(0f, 0.5f, 0.78f, 1f)) })
        canvas.restore()
    }

    private fun matte(canvas: Canvas, path: Path, size: Float, color: Int) {
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            setShadowLayer(size * 0.08f, size * 0.03f, size * 0.06f, 0x77000000)
        })
    }

    // Gradient-filled metal with a dark edge and a soft shadow.
    private fun metal(canvas: Canvas, path: Path, box: RectF, size: Float, stops: IntArray, at: FloatArray, edge: Int, shadow: Int) {
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = edge
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = size * 0.03f
            strokeJoin = Paint.Join.ROUND
            setShadowLayer(size * 0.07f, 0f, size * 0.04f, shadow)
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient(box, stops, at) })
        canvas.save()
        canvas.clipPath(path)
        canvas.translate(0f, size * 0.012f)
        canvas.drawPath(path, stroke(0x99FFFFFF.toInt(), size * 0.008f))
        canvas.restore()
    }

    // Glow, split-colour copies and a few shifted slices make the glitch; the bright core sits on top.
    private fun cyber(canvas: Canvas, path: Path, box: RectF, size: Float, color: Int, seed: Int) {
        val magenta = 0xFFFF2BD6.toInt()
        repeat(2) { pass ->
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = size * 0.03f
                alpha = 150 - pass * 40
                maskFilter = BlurMaskFilter(size * (0.12f + pass * 0.1f), BlurMaskFilter.Blur.NORMAL)
            })
        }
        val shift = size * 0.025f
        canvas.save(); canvas.translate(-shift, 0f); canvas.drawPath(path, fill(magenta and 0xFFFFFF or 0xB0000000.toInt())); canvas.restore()
        canvas.save(); canvas.translate(shift, 0f); canvas.drawPath(path, fill(0xB022E5FF.toInt())); canvas.restore()
        val random = kotlin.random.Random(seed)
        repeat(3) {
            val top = box.top + random.nextFloat() * box.height() * 0.8f
            canvas.save()
            canvas.clipRect(box.left - size, top, box.right + size, top + box.height() * 0.08f)
            canvas.translate((random.nextFloat() - 0.5f) * size * 0.2f, 0f)
            canvas.drawPath(path, fill(magenta))
            canvas.restore()
        }
        canvas.drawPath(path, fill(mix(color, Color.WHITE, 0.75f)))
        canvas.drawPath(path, stroke(color, size * 0.008f))
    }

    // Dark rim, a lit top-left edge and a shaded bottom-right edge, then the metal on top. A glow sits behind.
    private fun forged(canvas: Canvas, path: Path, box: RectF, size: Float, color: Int, stops: IntArray, glow: Int, accent: Boolean) {
        val d = size * 0.014f
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = glow
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = size * 0.02f
            alpha = 130
            maskFilter = BlurMaskFilter(size * 0.12f, BlurMaskFilter.Blur.NORMAL)
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFF0B0C0E.toInt()
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = size * 0.04f
            strokeJoin = Paint.Join.ROUND
        })
        canvas.save(); canvas.translate(d, d); canvas.drawPath(path, fill(0xFF050506.toInt())); canvas.restore()
        canvas.save(); canvas.translate(-d, -d); canvas.drawPath(path, fill(0xFFE9EDF2.toInt())); canvas.restore()
        val tinted = if (color == Color.WHITE || color == 0 || color == 0xFF000000.toInt()) stops else IntArray(stops.size) { mix(stops[it], color, 0.35f) }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient(box, tinted) })
        if (accent) {
            // A blood-red line with a diamond in the middle under the name, like a sword laid under a title.
            val y = box.bottom + size * 0.1f
            val middle = (box.left + box.right) / 2
            val red = 0xFFB3131B.toInt()
            canvas.drawRect(box.left, y - size * 0.006f, box.right, y + size * 0.006f, fill(red))
            canvas.drawPath(Path().apply {
                moveTo(middle, y - size * 0.04f); lineTo(middle + size * 0.04f, y); lineTo(middle, y + size * 0.04f); lineTo(middle - size * 0.04f, y); close()
            }, fill(red))
        }
    }
}
