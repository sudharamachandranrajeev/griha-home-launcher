package dev.brahma.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Generated pictures that go behind a subject. Their colours come from the user's photo. */
enum class BackdropStyle(val title: String) {
    FLUID("Fluid colour"), AURORA("Aurora"), ORBS("Glossy 3D orbs"), BOKEH("Bokeh lights"), WAVES("Silk waves")
}

/** Picks the main colours of a photo, with no Android types so it can be unit tested. */
object Palettes {
    /**
     * [count] distinct colours that stand out in [pixels] (ARGB): vivid ones count for more, and
     * near-black and near-white are ignored. Always returns [count] colours, filling any gap with
     * lighter and darker versions of what was found.
     */
    fun dominant(pixels: IntArray, count: Int): List<Int> {
        val sums = HashMap<Int, DoubleArray>()
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val high = max(r, max(g, b))
            val low = minOf(r, minOf(g, b))
            if (high < 28 || low > 232) continue
            val weight = 0.3 + (high - low) / high.toDouble()
            val entry = sums.getOrPut(((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)) { DoubleArray(4) }
            entry[0] += weight
            entry[1] += r * weight
            entry[2] += g * weight
            entry[3] += b * weight
        }
        val picked = ArrayList<Int>()
        for (entry in sums.values.sortedByDescending { it[0] }) {
            val colour = 0xFF000000.toInt() or ((entry[1] / entry[0]).toInt() shl 16) or ((entry[2] / entry[0]).toInt() shl 8) or (entry[3] / entry[0]).toInt()
            if (picked.none { far(it, colour) < 70 }) picked.add(colour)
            if (picked.size == count) return picked
        }
        if (picked.isEmpty()) picked.add(0xFF5A6EA0.toInt())
        var index = 0
        while (picked.size < count) {
            val from = picked[index++ % picked.size]
            picked.add(if (picked.size % 2 == 0) mix(from, Color.WHITE, 0.45f) else mix(from, Color.BLACK, 0.5f))
        }
        return picked
    }

    private fun far(a: Int, b: Int): Double {
        val r = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val g = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val d = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.sqrt((r * r + g * g + d * d).toDouble())
    }
}

object Backdrops {
    /** The same [seed] always draws the same picture. */
    fun draw(canvas: Canvas, style: BackdropStyle, palette: List<Int>, width: Int, height: Int, seed: Long) {
        val random = Random(seed)
        when (style) {
            BackdropStyle.FLUID -> fluid(canvas, palette, width, height, random)
            BackdropStyle.AURORA -> aurora(canvas, palette, width, height, random)
            BackdropStyle.ORBS -> orbs(canvas, palette, width, height, random)
            BackdropStyle.BOKEH -> bokeh(canvas, palette, width, height, random)
            BackdropStyle.WAVES -> waves(canvas, palette, width, height, random)
        }
    }

    private fun base(canvas: Canvas, top: Int, bottom: Int, height: Int) {
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP) })
    }

    // Big stretched, overlapping blobs of colour, drawn small and enlarged so they melt into each other.
    private fun fluid(canvas: Canvas, palette: List<Int>, width: Int, height: Int, random: Random) {
        val small = Bitmap.createBitmap(max(width / 6, 1), max(height / 6, 1), Bitmap.Config.ARGB_8888)
        val art = Canvas(small)
        base(art, mix(palette[0], Color.BLACK, 0.35f), mix(palette[1 % palette.size], Color.BLACK, 0.2f), small.height)
        repeat(7) { index ->
            val colour = palette[index % palette.size]
            val radius = small.width * (0.35f + random.nextFloat() * 0.45f)
            art.save()
            art.translate(random.nextFloat() * small.width, random.nextFloat() * small.height)
            art.rotate(random.nextFloat() * 180f)
            art.scale(1.7f, 1f)
            art.drawCircle(0f, 0f, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(0f, 0f, radius, intArrayOf(withAlpha(colour, 235), withAlpha(colour, 120), withAlpha(colour, 0)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })
            art.restore()
        }
        canvas.drawBitmap(Bitmap.createScaledBitmap(small, width, height, true), 0f, 0f, null)
    }

    // Dark sky with glowing ribbons that drift across it.
    private fun aurora(canvas: Canvas, palette: List<Int>, width: Int, height: Int, random: Random) {
        base(canvas, mix(palette[0], Color.BLACK, 0.85f), mix(palette[1 % palette.size], Color.BLACK, 0.7f), height)
        val small = Bitmap.createBitmap(max(width / 5, 1), max(height / 5, 1), Bitmap.Config.ARGB_8888)
        val art = Canvas(small)
        repeat(5) { index ->
            val colour = palette[index % palette.size]
            val path = Path()
            val centre = small.height * (0.25f + random.nextFloat() * 0.55f)
            val swing = small.height * (0.05f + random.nextFloat() * 0.12f)
            val turns = 1f + random.nextFloat() * 1.5f
            val phase = random.nextFloat() * 6.28f
            for (step in 0..40) {
                val x = small.width * step / 40f
                val y = centre + swing * sin(turns * 2 * PI.toFloat() * step / 40f + phase)
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            art.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = small.height * (0.05f + random.nextFloat() * 0.07f)
                this.color = withAlpha(colour, 150)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            })
        }
        val soft = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(small, max(small.width / 3, 1), max(small.height / 3, 1), true), width, height, true)
        canvas.drawBitmap(soft, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) })
    }

    // Glossy spheres: a light spot, a shaded edge and a soft shadow, so they look solid.
    private fun orbs(canvas: Canvas, palette: List<Int>, width: Int, height: Int, random: Random) {
        base(canvas, mix(palette[0], Color.WHITE, 0.35f), mix(palette[1 % palette.size], Color.BLACK, 0.3f), height)
        val orbs = List(6) { Triple(random.nextFloat(), random.nextFloat(), 0.07f + random.nextFloat() * 0.2f) }.sortedBy { it.third }
        orbs.forEachIndexed { index, (x, y, size) ->
            val colour = palette[index % palette.size]
            val radius = width * size
            val cx = width * (0.1f + x * 0.8f)
            val cy = height * (0.08f + y * 0.84f)
            canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = colour
                setShadowLayer(radius * 0.5f, radius * 0.12f, radius * 0.3f, 0x66000000)
            })
            canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx - radius * 0.35f, cy - radius * 0.4f, radius * 1.45f,
                    intArrayOf(mix(colour, Color.WHITE, 0.75f), colour, mix(colour, Color.BLACK, 0.5f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            })
        }
    }

    // Out-of-focus lights.
    private fun bokeh(canvas: Canvas, palette: List<Int>, width: Int, height: Int, random: Random) {
        base(canvas, mix(palette[0], Color.BLACK, 0.8f), mix(palette[1 % palette.size], Color.BLACK, 0.6f), height)
        repeat(26) {
            val colour = mix(palette[random.nextInt(palette.size)], Color.WHITE, random.nextFloat() * 0.4f)
            val radius = width * (0.03f + random.nextFloat() * 0.1f)
            val cx = random.nextFloat() * width
            val cy = random.nextFloat() * height
            canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, radius, intArrayOf(withAlpha(colour, 40), withAlpha(colour, 110), withAlpha(colour, 0)),
                    floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            })
        }
    }

    // Layers of smooth folds, lighter toward the front, like silk.
    private fun waves(canvas: Canvas, palette: List<Int>, width: Int, height: Int, random: Random) {
        base(canvas, mix(palette[0], Color.WHITE, 0.25f), mix(palette[1 % palette.size], Color.BLACK, 0.2f), height)
        repeat(5) { layer ->
            val colour = palette[(layer + 1) % palette.size]
            val top = height * (0.2f + layer * 0.16f)
            val swing = height * (0.03f + random.nextFloat() * 0.05f)
            val turns = 0.6f + random.nextFloat() * 1.1f
            val phase = random.nextFloat() * 6.28f
            val path = Path().apply {
                moveTo(0f, height.toFloat())
                for (step in 0..40) {
                    val x = width * step / 40f
                    lineTo(x, top + swing * sin(turns * 2 * PI.toFloat() * step / 40f + phase))
                }
                lineTo(width.toFloat(), height.toFloat())
                close()
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, top - swing, 0f, height.toFloat(), withAlpha(mix(colour, Color.WHITE, 0.3f), 200),
                    withAlpha(mix(colour, Color.BLACK, 0.35f), 220), Shader.TileMode.CLAMP)
            })
        }
    }

    private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha shl 24)

    /** A seed unlikely to repeat across days when [daily] is on. */
    fun seedFor(seed: Long, daily: Boolean, epochDay: Long) = if (daily) seed + abs(epochDay) * 7919L else seed
}
