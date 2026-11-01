package dev.brahma.launcher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** The ways a lock photo can be blurred. */
enum class BlurStyle(val title: String) {
    SOFT("Soft focus · smooth, like an out-of-focus lens"),
    FROSTED("Frosted glass · soft, pale and fine-grained"),
    BOKEH("Lens bokeh · lights bloom into round discs"),
    PROGRESSIVE("Progressive · sharp band fading to soft"),
    ZOOM("Zoom burst · streaks rushing to the middle"),
    MOTION("Motion · sideways streaks, like a passing train")
}

/**
 * How the blurred lock photo looks. All values run from 0 to 1; [light] 0.5 is neutral, lower
 * darkens and higher brightens.
 */
data class LockBlur(
    val style: BlurStyle = BlurStyle.SOFT,
    val strength: Float = 0.6f,
    /** Colour richness after blurring, as phone blur materials do: blur greys colours out, this brings them back. */
    val vibrancy: Float = 0.45f,
    val light: Float = 0.5f,
    /** Fine noise over the blur; a little stops smooth areas from banding into steps. */
    val grain: Float = 0.2f,
    /** Bokeh: how strongly bright spots bloom into discs. */
    val sparkle: Float = 0.6f,
    /** Progressive and zoom: where the sharp band or the zoom centre sits, from the top. */
    val focus: Float = 0.62f
) {
    fun encode(): JSONObject = JSONObject().apply {
        put("style", style.name)
        put("strength", strength.toDouble())
        put("vibrancy", vibrancy.toDouble())
        put("light", light.toDouble())
        put("grain", grain.toDouble())
        put("sparkle", sparkle.toDouble())
        put("focus", focus.toDouble())
    }

    companion object {
        fun decode(item: JSONObject?): LockBlur {
            if (item == null) return LockBlur()
            fun value(key: String, default: Float) = item.optDouble(key, default.toDouble()).toFloat().coerceIn(0f, 1f)
            return LockBlur(
                BlurStyle.entries.find { it.name == item.optString("style") } ?: BlurStyle.SOFT,
                value("strength", 0.6f), value("vibrancy", 0.45f), value("light", 0.5f),
                value("grain", 0.2f), value("sparkle", 0.6f), value("focus", 0.62f)
            )
        }
    }
}

/**
 * Lock-photo blur. The photo is first halved step by step (each step averages 2×2 pixels, the
 * downsampling half of the dual-filter blur), so the expensive work runs on a small picture. It
 * is then blurred in linear light, where bright areas spread like real light instead of going
 * muddy: three running box blurs for a smooth Gaussian, a hard-edged disc with boosted
 * highlights for bokeh, or streaks for motion and zoom. The small result is scaled up with
 * filtering, colour is restored, and fine grain hides banding.
 */
object LockBlurRenderer {
    /** Blur radius at working size, in pixels; the picture is shrunk until the blur needs about this many. */
    private const val WORK_RADIUS = 9f

    /**
     * Draws [photo] blurred with [blur] onto [canvas] through [photoMatrix] (photo pixels to
     * screen pixels). [width] and [height] are the screen; [theme] tints the light veil.
     */
    fun draw(canvas: Canvas, photo: Bitmap, photoMatrix: Matrix, blur: LockBlur, width: Int, height: Int, theme: Int) {
        val toScreen = FloatArray(9).also { photoMatrix.getValues(it) }[Matrix.MSCALE_X]
        // Strength 0 to 1 is a blur radius of 1% to 9% of the screen width.
        val screenRadius = width * (0.01f + 0.08f * blur.strength) * (if (blur.style == BlurStyle.FROSTED) 0.8f else 1f)
        val photoRadius = screenRadius / toScreen
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = grade(blur) }
        fun place(small: Bitmap) = Matrix(photoMatrix).apply { preScale(photo.width / small.width.toFloat(), photo.height / small.height.toFloat()) }
        when (blur.style) {
            BlurStyle.PROGRESSIVE -> {
                // Sharp photo, then a light and a full blur faded in with distance from the focus band.
                canvas.drawBitmap(photo, photoMatrix, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = grade(blur, 0.5f) })
                val focusY = height * blur.focus
                listOf(0.35f to 0.10f, 1f to 0.26f).forEach { (share, start) ->
                    val small = blurred(photo, photoRadius * share, blur)
                    val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                    canvas.drawBitmap(small, place(small), paint)
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
                        shader = band(focusY, height, start)
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    })
                    canvas.restoreToCount(layer)
                }
            }
            else -> {
                val small = blurred(photo, photoRadius, blur)
                canvas.drawBitmap(small, place(small), paint)
            }
        }
        veil(canvas, blur, width, height, theme)
        grain(canvas, blur, width, height)
    }

    /** Alpha that is clear around [focusY] and fully opaque from [start] of the screen height away from it. */
    private fun band(focusY: Float, height: Int, start: Float): Shader {
        val h = height.toFloat()
        val clear = 0.06f * h
        val full = clear + start * h
        val stops = floatArrayOf(focusY - full, focusY - clear, focusY + clear, focusY + full).map { (it / h).coerceIn(0f, 1f) }
        val positions = floatArrayOf(0f, stops[0], stops[1], stops[2], stops[3], 1f)
        for (i in 1 until positions.size) positions[i] = max(positions[i], positions[i - 1])
        val colours = intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK, Color.BLACK)
        return LinearGradient(0f, 0f, 0f, h, colours, positions, Shader.TileMode.CLAMP)
    }

    /** The photo shrunk and blurred; drawn scaled up, the shrinking costs nothing visible. */
    fun blurred(photo: Bitmap, photoRadius: Float, blur: LockBlur): Bitmap {
        var small = photo
        // Halve while the blur would still be wide at the next size: each halving averages 2×2 pixels.
        var radius = photoRadius
        while (radius / 2 >= WORK_RADIUS && small.width > 32 && small.height > 32) {
            small = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true)
            radius /= 2
        }
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        val image = LinearImage.from(pixels, w, h)
        val r = radius.coerceAtLeast(0.5f)
        when (blur.style) {
            BlurStyle.SOFT, BlurStyle.FROSTED, BlurStyle.PROGRESSIVE -> image.gaussian(r)
            BlurStyle.BOKEH -> {
                image.boostPoints(r, 0.5f, 1f + 7f * blur.sparkle)
                image.disc(r)
                image.gaussian(r * 0.12f)
            }
            BlurStyle.MOTION -> {
                image.boostHighlights(0.7f, 1f + 1.5f * blur.sparkle)
                image.streak(r * 2.2f)
                image.gaussian(r * 0.15f)
            }
            BlurStyle.ZOOM -> {
                image.gaussian(r * 0.2f)
                image.zoom(w * 0.5f, h * blur.focus, 0.10f + 0.35f * blur.strength)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(image.toPixels(), 0, w, 0, 0, w, h)
        return out
    }

    /** Saturation back up after blurring, and light down or up; [amount] scales both. */
    private fun grade(blur: LockBlur, amount: Float = 1f): ColorMatrixColorFilter {
        val saturation = 1f + (blur.vibrancy * 1.1f - 0.1f + if (blur.style == BlurStyle.FROSTED) -0.15f else 0f) * amount
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        val lift = (blur.light - 0.5f) * 2f * amount
        val scale = 1f + lift * 0.25f
        matrix.postConcat(ColorMatrix(floatArrayOf(scale, 0f, 0f, 0f, lift * 18f, 0f, scale, 0f, 0f, lift * 18f, 0f, 0f, scale, 0f, lift * 18f, 0f, 0f, 0f, 1f, 0f)))
        return ColorMatrixColorFilter(matrix)
    }

    // A veil of the theme's page colour; frosted glass is always pale.
    private fun veil(canvas: Canvas, blur: LockBlur, width: Int, height: Int, theme: Int) {
        val light = (blur.light - 0.5f) * 2f
        val alpha = when {
            blur.style == BlurStyle.FROSTED -> 0.22f + max(light, 0f) * 0.25f
            light > 0f -> light * 0.22f
            else -> 0f
        }
        val colour = if (blur.style == BlurStyle.FROSTED) mix(Color.WHITE, theme, 0.25f) else mix(Color.WHITE, theme, 0.4f)
        if (alpha > 0f) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = tone(colour, alpha) })
        if (light < 0f) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = tone(Color.BLACK, -light * 0.45f) })
    }

    // Grey noise blended softly over the picture; frosted glass gets a finer, stronger frost.
    private fun grain(canvas: Canvas, blur: LockBlur, width: Int, height: Int) {
        val amount = blur.grain * if (blur.style == BlurStyle.FROSTED) 1.6f else 1f
        if (amount <= 0.01f) return
        val tile = Bitmap.createBitmap(NOISE, NOISE, Bitmap.Config.ARGB_8888)
        val random = Random(7)
        val spread = (amount * 46).roundToInt().coerceAtMost(90)
        tile.setPixels(IntArray(NOISE * NOISE) {
            val v = (128 + random.nextInt(-spread, spread + 1)).coerceIn(0, 255)
            Color.rgb(v, v, v)
        }, 0, NOISE, 0, 0, NOISE, NOISE)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        })
    }

    private const val NOISE = 256
}

/** A picture in linear light, one float per channel, for blurs that behave like real light. */
class LinearImage(val width: Int, val height: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
    companion object {
        private val TO_LINEAR = FloatArray(256) { (it / 255f).toDouble().pow(2.2).toFloat() }

        fun from(pixels: IntArray, width: Int, height: Int): LinearImage {
            val n = width * height
            val image = LinearImage(width, height, FloatArray(n), FloatArray(n), FloatArray(n))
            for (i in 0 until n) {
                val p = pixels[i]
                image.r[i] = TO_LINEAR[(p shr 16) and 0xFF]
                image.g[i] = TO_LINEAR[(p shr 8) and 0xFF]
                image.b[i] = TO_LINEAR[p and 0xFF]
            }
            return image
        }

        /** Widths of three box blurs whose result matches a Gaussian of [sigma] (Kovesi's method). */
        fun boxes(sigma: Float, count: Int = 3): IntArray {
            val ideal = sqrt(12.0 * sigma * sigma / count + 1)
            var lower = floor(ideal).toInt()
            if (lower % 2 == 0) lower--
            val upper = lower + 2
            val m = ((12.0 * sigma * sigma - count * lower * lower - 4.0 * count * lower - 3.0 * count) / (-4.0 * lower - 4)).roundToInt()
            return IntArray(count) { if (it < m) lower else upper }
        }
    }

    fun toPixels(): IntArray {
        // A soft shoulder instead of a hard clip, so boosted lights roll off into white rather than flat patches.
        fun shoulder(v: Float) = if (v <= 0.8f) v else 0.8f + 0.2f * (1f - exp(-(v - 0.8f) / 0.2f))
        fun encode(v: Float) = (shoulder(v.coerceAtLeast(0f)).toDouble().pow(1 / 2.2) * 255 + 0.5).toInt().coerceAtMost(255)
        return IntArray(width * height) { (0xFF shl 24) or (encode(r[it]) shl 16) or (encode(g[it]) shl 8) or encode(b[it]) }
    }

    private fun channels() = listOf(r, g, b)

    /** A smooth Gaussian with the given radius: three running box blurs across, then down. */
    fun gaussian(radius: Float) = channels().forEach { gaussianChannel(it, radius) }

    fun gaussianChannel(channel: FloatArray, radius: Float) {
        if (radius < 0.5f) return
        val scratch = FloatArray(width * height)
        boxes(radius / 2f).forEach { size ->
            val reach = (size - 1) / 2
            boxRows(channel, scratch, reach)
            boxColumns(scratch, channel, reach)
        }
    }

    /** Running-sum box blur along rows; edges repeat the border pixel. */
    private fun boxRows(source: FloatArray, target: FloatArray, reach: Int) {
        if (reach <= 0) { source.copyInto(target); return }
        val scale = 1f / (2 * reach + 1)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (k in -reach..reach) sum += source[row + k.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                target[row + x] = sum * scale
                sum += source[row + (x + reach + 1).coerceAtMost(width - 1)] - source[row + (x - reach).coerceAtLeast(0)]
            }
        }
    }

    private fun boxColumns(source: FloatArray, target: FloatArray, reach: Int) {
        if (reach <= 0) { source.copyInto(target); return }
        val scale = 1f / (2 * reach + 1)
        for (x in 0 until width) {
            var sum = 0f
            for (k in -reach..reach) sum += source[k.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                target[y * width + x] = sum * scale
                sum += source[(y + reach + 1).coerceAtMost(height - 1) * width + x] - source[(y - reach).coerceAtLeast(0) * width + x]
            }
        }
    }

    /**
     * Small lights: pixels brighter than [threshold] and much brighter than their surroundings
     * (within [radius]) are multiplied up to [gain], so street lamps bloom into discs while a
     * bright sky, which is bright all around, stays as it is.
     */
    fun boostPoints(radius: Float, threshold: Float, gain: Float) {
        val n = width * height
        val lum = FloatArray(n) { 0.2126f * r[it] + 0.7152f * g[it] + 0.0722f * b[it] }
        val around = LinearImage(width, height, lum.copyOf(), FloatArray(n), FloatArray(n))
        around.gaussianChannel(around.r, radius)
        val floor = threshold.toDouble().pow(2.2).toFloat()
        for (i in 0 until n) {
            if (lum[i] <= floor) continue
            val standout = ((lum[i] / (around.r[i] + 1e-4f) - 1.3f) / 1.7f).coerceIn(0f, 1f)
            val bright = ((lum[i] - floor) / (1f - floor)).coerceIn(0f, 1f)
            val k = 1f + (gain - 1f) * standout * bright
            r[i] *= k; g[i] *= k; b[i] *= k
        }
    }

    /** Lights above [threshold] (by brightness, 0 to 1 in linear light) are multiplied up to [gain], so they bloom. */
    fun boostHighlights(threshold: Float, gain: Float) {
        val floor = threshold.toDouble().pow(2.2).toFloat()
        for (i in r.indices) {
            val lum = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
            if (lum <= floor) continue
            val t = ((lum - floor) / (1f - floor)).coerceIn(0f, 1f)
            val k = 1f + (gain - 1f) * t * t
            r[i] *= k; g[i] *= k; b[i] *= k
        }
    }

    /**
     * A flat, hard-edged disc blur: what an out-of-focus lens does to points of light. Each row of
     * the disc is a span read from per-row running sums, so a pixel costs one add per disc row.
     */
    fun disc(radius: Float) {
        val rad = ceil(radius).toInt().coerceAtLeast(1)
        val spans = IntArray(2 * rad + 1) { val dy = it - rad; sqrt(max(0f, radius * radius - dy * dy)).toInt() }
        val area = spans.sumOf { 2 * it + 1 }.toFloat()
        val prefix = FloatArray((width + 1) * height)
        channels().forEach { channel ->
            for (y in 0 until height) {
                val row = y * (width + 1)
                for (x in 0 until width) prefix[row + x + 1] = prefix[row + x] + channel[y * width + x]
            }
            val out = FloatArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                var sum = 0f
                for (i in spans.indices) {
                    val yy = (y + i - rad).coerceIn(0, height - 1)
                    val half = spans[i]
                    val left = x - half
                    val right = x + half
                    val row = yy * (width + 1)
                    val l = left.coerceIn(0, width)
                    val rr = (right + 1).coerceIn(0, width)
                    sum += prefix[row + rr] - prefix[row + l]
                    // Off the edges, repeat the border pixel.
                    if (left < 0) sum += -left * channel[yy * width]
                    if (right >= width) sum += (right - width + 1) * channel[yy * width + width - 1]
                }
                out[y * width + x] = sum / area
            }
            out.copyInto(channel)
        }
    }

    /** Long sideways streaks with a soft tail: two box passes along rows only. */
    fun streak(length: Float) {
        val reach = (length / 2).toInt().coerceAtLeast(1)
        val scratch = FloatArray(width * height)
        channels().forEach { channel ->
            boxRows(channel, scratch, reach)
            boxRows(scratch, channel, (reach * 0.6f).toInt())
        }
    }

    /** Rays toward ([cx], [cy]): each pixel averages samples along its line to the centre, more the further out. */
    fun zoom(cx: Float, cy: Float, amount: Float) {
        val samples = 18
        val source = listOf(r.copyOf(), g.copyOf(), b.copyOf())
        val maxDistance = sqrt(width * width + height * height.toFloat())
        for (y in 0 until height) for (x in 0 until width) {
            val dx = x - cx
            val dy = y - cy
            // Calm near the centre, streaking more toward the edges.
            val pull = amount * (sqrt(dx * dx + dy * dy) / maxDistance * 2f).coerceAtMost(1f)
            var sr = 0f; var sg = 0f; var sb = 0f; var weight = 0f
            for (s in 0 until samples) {
                val t = 1f - pull * s / (samples - 1)
                val sx = (cx + dx * t).roundToInt().coerceIn(0, width - 1)
                val sy = (cy + dy * t).roundToInt().coerceIn(0, height - 1)
                val w = exp(-s * 1.6f / samples)
                val i = sy * width + sx
                sr += source[0][i] * w; sg += source[1][i] * w; sb += source[2][i] * w
                weight += w
            }
            val i = y * width + x
            r[i] = sr / weight; g[i] = sg / weight; b[i] = sb / weight
        }
    }
}
