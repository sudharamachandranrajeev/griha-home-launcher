package dev.brahma.launcher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import kotlin.math.max

/** A drawn sticker and where its top-left corner sits in source-photo pixels. */
class Drawn(val bitmap: Bitmap, val left: Int, val top: Int)

/**
 * Draws stickers: soft subject edges, effects behind the subject (outline, glow, shadow) and
 * colour effects on it (duotone, mono, pop, fade). Masks are white-with-alpha ARGB bitmaps,
 * because Android draws those reliably on every version.
 */
object StickerArt {
    private const val VISIBLE = 8

    /**
     * Cleans the cutout edge: pulls it in slightly to drop the halo of background colour, then
     * feathers it so hair blends. [softness] runs from 0 (crisp) to 1 (very soft).
     */
    fun softEdge(mask: Bitmap, softness: Float): Bitmap {
        val side = max(mask.width, mask.height)
        val choke = 0.6f + softness * side * 0.003f
        val feather = 0.5f + softness * side * 0.005f
        val pulled = map(blur(mask, choke)) { (it * 2 - 255).coerceIn(0, 255) }
        return blur(pulled, feather)
    }

    /** The subject cut from [source] with no effects, at source size; used for depth layers. */
    fun cutout(source: Bitmap, edge: Bitmap?, effects: List<StickerEffect>): Bitmap =
        render(source, edge, effects.filter { it.type in colourTypes }, crop = false).bitmap

    /**
     * Draws [source] cut by [edge] (null keeps the whole picture) with [effects]. With [crop] the
     * result is trimmed to the subject plus room for its effects; otherwise it keeps source size.
     */
    fun render(source: Bitmap, edge: Bitmap?, effects: List<StickerEffect>, crop: Boolean = true): Drawn {
        val alpha = edge ?: solid(source.width, source.height)
        val side = max(source.width, source.height).toFloat()
        var left = 0
        var top = 0
        var width = source.width
        var height = source.height
        if (crop) {
            val box = bounds(alpha) ?: intArrayOf(0, 0, source.width, source.height)
            val pad = effects.maxOfOrNull { reach(it, side) }?.toInt()?.plus(2) ?: 2
            left = box[0] - pad
            top = box[1] - pad
            width = box[2] - box[0] + pad * 2
            height = box[3] - box[1] + pad * 2
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val shape = place(alpha, width, height, -left, -top)
        val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        effects.filter { it.type !in colourTypes && it.type != EffectType.FADE }.forEach { effect ->
            val radius = reach(effect, side)
            when (effect.type) {
                EffectType.OUTLINE -> layer(canvas, tint, map(blur(shape, radius * 0.6f)) { (it * 10).coerceAtMost(255) }, effect.color, 1f, 0f)
                EffectType.GLOW -> {
                    val core = map(blur(shape, radius * 0.15f)) { (it * 4).coerceAtMost(255) }
                    val halo = blur(core, radius * 0.5f)
                    layer(canvas, tint, halo, effect.color, 0.5f + effect.strength * 0.5f, 0f)
                    layer(canvas, tint, blur(core, radius * 0.18f), effect.color, effect.strength, 0f)
                }
                EffectType.SHADOW -> layer(canvas, tint, blur(shape, radius * 0.5f), effect.color, effect.strength * 0.9f, radius * 0.45f)
                else -> Unit
            }
        }
        val subject = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(subject).apply {
            drawBitmap(source, -left.toFloat(), -top.toFloat(), null)
            drawBitmap(shape, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        colourMatrix(effects)?.let { paint.colorFilter = ColorMatrixColorFilter(it) }
        effects.lastOrNull { it.type == EffectType.FADE }?.let { paint.alpha = (255 * (1 - 0.75f * it.strength)).toInt() }
        canvas.drawBitmap(subject, 0f, 0f, paint)
        return Drawn(out, left, top)
    }

    private val colourTypes = setOf(EffectType.DUOTONE, EffectType.MONO, EffectType.POP)

    /** How far an effect reaches beyond the subject, in pixels. */
    private fun reach(effect: StickerEffect, side: Float) = when (effect.type) {
        EffectType.OUTLINE -> 1 + effect.size * side * 0.035f
        EffectType.GLOW -> 2 + effect.size * side * 0.08f
        EffectType.SHADOW -> 1 + effect.size * side * 0.04f
        else -> 0f
    }

    /** Combined colour change for the subject, or null when no colour effect is on. */
    fun colourMatrix(effects: List<StickerEffect>): ColorMatrix? {
        val colour = effects.filter { it.type in colourTypes }
        if (colour.isEmpty()) return null
        return ColorMatrix().apply {
            colour.forEach { effect ->
                val target = when (effect.type) {
                    EffectType.DUOTONE -> duotone(mix(effect.color, Color.BLACK, 0.75f), mix(effect.color, Color.WHITE, 0.35f))
                    EffectType.MONO -> ColorMatrix().apply { setSaturation(0f); postConcat(contrast(1f + effect.size * 0.6f)) }
                    else -> ColorMatrix().apply { setSaturation(1f + effect.strength * 1.2f); postConcat(contrast(1f + effect.size * 0.4f)) }
                }
                val strength = if (effect.type == EffectType.POP) 1f else effect.strength
                postConcat(blend(target, strength))
            }
        }
    }

    // Maps brightness onto a line from the dark colour to the light colour.
    private fun duotone(dark: Int, light: Int): ColorMatrix {
        fun row(shift: Int): FloatArray {
            val low = ((dark shr shift) and 0xFF).toFloat()
            val range = (((light shr shift) and 0xFF) - low) / 255f
            return floatArrayOf(0.299f * range, 0.587f * range, 0.114f * range, 0f, low)
        }
        return ColorMatrix(row(16) + row(8) + row(0) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
    }

    private fun contrast(amount: Float): ColorMatrix {
        val shift = 128 * (1 - amount)
        return ColorMatrix(floatArrayOf(amount, 0f, 0f, 0f, shift, 0f, amount, 0f, 0f, shift, 0f, 0f, amount, 0f, shift, 0f, 0f, 0f, 1f, 0f))
    }

    private fun blend(target: ColorMatrix, amount: Float): ColorMatrix {
        val identity = ColorMatrix().array
        val to = target.array
        return ColorMatrix(FloatArray(20) { identity[it] * (1 - amount) + to[it] * amount })
    }

    private fun layer(canvas: Canvas, paint: Paint, shape: Bitmap, color: Int, opacity: Float, dy: Float) {
        paint.colorFilter = PorterDuffColorFilter(color or 0xFF000000.toInt(), PorterDuff.Mode.SRC_IN)
        paint.alpha = (255 * opacity.coerceIn(0f, 1f)).toInt()
        canvas.drawBitmap(shape, 0f, dy, paint)
        paint.colorFilter = null
        paint.alpha = 255
    }

    private fun solid(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun place(mask: Bitmap, width: Int, height: Int, dx: Int, dy: Int): Bitmap {
        if (dx == 0 && dy == 0 && mask.width == width && mask.height == height) return mask
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(mask, dx.toFloat(), dy.toFloat(), null)
        }
    }

    /** Gaussian-style blur of a mask's alpha; the result keeps the mask's size. */
    fun blur(mask: Bitmap, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        if (radius < 0.5f) {
            canvas.drawBitmap(mask, 0f, 0f, null)
        } else {
            val offset = IntArray(2)
            val blurred = mask.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }, offset)
            canvas.drawBitmap(blurred, offset[0].toFloat(), offset[1].toFloat(), paint)
            blurred.recycle()
        }
        return out
    }

    /** Applies [transform] to every pixel's alpha. */
    private fun map(mask: Bitmap, transform: (Int) -> Int): Bitmap {
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        for (index in pixels.indices) pixels[index] = (transform(pixels[index] ushr 24) shl 24) or 0xFFFFFF
        return Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        }
    }

    /** Left, top, right, bottom of the visible part of [mask], or null when nothing is visible. */
    fun bounds(mask: Bitmap): IntArray? {
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        return Masks.bounds(IntArray(pixels.size) { pixels[it] ushr 24 }, mask.width, VISIBLE)
    }
}

/** Pure mask arithmetic, kept free of Android types so it can be unit tested. */
object Masks {
    fun bounds(alpha: IntArray, width: Int, threshold: Int): IntArray? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (index in alpha.indices) {
            if (alpha[index] < threshold) continue
            val x = index % width
            val y = index / width
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        return if (right < 0) null else intArrayOf(left, top, right + 1, bottom + 1)
    }

    /** Converts ML Kit's per-pixel subject confidence (0 to 1) to alpha; null when no subject. */
    fun fromConfidence(confidence: FloatArray, minimumShare: Float = 0.005f): IntArray? {
        var subject = 0
        val alpha = IntArray(confidence.size) { index ->
            val value = (confidence[index] * 255).toInt().coerceIn(0, 255)
            if (value >= 128) subject++
            value
        }
        return if (subject < confidence.size * minimumShare) null else alpha
    }
}
