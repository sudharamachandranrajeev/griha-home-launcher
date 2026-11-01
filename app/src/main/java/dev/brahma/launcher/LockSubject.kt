package dev.brahma.launcher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * A light touch of the theme's colour on the lock photo's subject. The photo behind it is never
 * changed. THEME takes the downloaded theme's pick, or leaves the subject alone without one.
 */
enum class SubjectLook(val title: String, val key: String) {
    THEME("The theme's pick", "theme"),
    NONE("Untouched", "none"),
    WASH("Colour wash", "wash"),
    DUOTONE("Duotone", "duotone"),
    RIM("Edge light", "rim"),
    HALO("Halo behind", "halo");

    companion object {
        /** A theme.json "subject" value; only the real looks count, so a theme cannot ask for "theme". */
        fun ofTheme(key: String?): SubjectLook? = entries.find { it.key == key && it != THEME && it != NONE }
    }
}

object SubjectStyler {
    /** The look actually drawn for [design] under [theme], with its strength. */
    fun resolve(design: LockDesign, theme: ThemeSpec?): Pair<SubjectLook, Float> = when (design.subjectLook) {
        SubjectLook.THEME -> (theme?.lockSubject ?: SubjectLook.NONE) to (theme?.lockAmount ?: 0f)
        else -> design.subjectLook to design.subjectAmount
    }

    /**
     * Draws [subject] (transparent around the subject) through [matrix], styled with [look] in
     * [tint]. [amount] runs 0 to 1, and even 1 keeps most of the photo's own colour.
     */
    fun draw(canvas: Canvas, subject: Bitmap, matrix: Matrix, look: SubjectLook, amount: Float, tint: Int, palette: Palette) {
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val strength = amount.coerceIn(0f, 1f)
        if (strength <= 0f || look == SubjectLook.NONE || look == SubjectLook.THEME) {
            canvas.drawBitmap(subject, matrix, filter)
            return
        }
        val side = maxOf(subject.width, subject.height).toFloat()
        when (look) {
            SubjectLook.HALO -> {
                // extractAlpha with a blur grows the bitmap; the offset lines it back up.
                val offset = IntArray(2)
                val halo = subject.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(side * 0.035f, BlurMaskFilter.Blur.NORMAL) }, offset)
                val placed = Matrix(matrix).apply { preTranslate(offset[0].toFloat(), offset[1].toFloat()) }
                canvas.drawBitmap(halo, placed, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    color = tint
                    alpha = (90 + 150 * strength).toInt().coerceAtMost(255)
                })
                halo.recycle()
                canvas.drawBitmap(subject, matrix, filter)
            }
            SubjectLook.RIM -> {
                canvas.drawBitmap(subject, matrix, filter)
                val rim = rim(subject, side * 0.012f, if (Color.alpha(tint) == 0) palette.accent else tint)
                canvas.drawBitmap(rim, matrix, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (110 + 145 * strength).toInt().coerceAtMost(255)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                })
                rim.recycle()
            }
            else -> {
                filter.colorFilter = ColorMatrixColorFilter(matrix(look, strength, tint, palette))
                canvas.drawBitmap(subject, matrix, filter)
            }
        }
    }

    /** The colour matrix for the wash and duotone looks: the photo's own colours with [strength] of the theme's. */
    fun matrix(look: SubjectLook, strength: Float, tint: Int, palette: Palette): ColorMatrix {
        val r = 0.299f
        val g = 0.587f
        val b = 0.114f
        val styled: FloatArray = when (look) {
            SubjectLook.DUOTONE -> {
                // Shadows in a deep shade of the tint, highlights in a pale one; luma picks between them.
                val low = mix(tint, if (palette.dark) palette.background else Color.BLACK, 0.72f)
                val high = mix(tint, Color.WHITE, 0.82f)
                fun row(shift: Int): FloatArray {
                    val lo = ((low shr shift) and 0xFF).toFloat()
                    val k = (((high shr shift) and 0xFF) - lo) / 255f
                    return floatArrayOf(r * k, g * k, b * k, 0f, lo)
                }
                row(16) + row(8) + row(0) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
            }
            else -> {
                // The tint's hue at the photo's own brightness.
                val light = mix(tint, Color.WHITE, 0.25f)
                val luma = maxOf(1f, r * Color.red(light) + g * Color.green(light) + b * Color.blue(light))
                fun row(channel: Int): FloatArray {
                    val k = channel / luma
                    return floatArrayOf(r * k, g * k, b * k, 0f, 0f)
                }
                row(Color.red(light)) + row(Color.green(light)) + row(Color.blue(light)) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
            }
        }
        // Kept subtle: at full strength a little under half the colour is the theme's.
        val share = strength * if (look == SubjectLook.DUOTONE) 0.55f else 0.45f
        val identity = ColorMatrix().array
        return ColorMatrix(FloatArray(20) { identity[it] * (1 - share) + styled[it] * share })
    }

    // A thin line of [colour] along the subject's top-left edges, as if lit from the upper left.
    private fun rim(subject: Bitmap, width: Float, colour: Int): Bitmap {
        val out = Bitmap.createBitmap(subject.width, subject.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val alpha = subject.extractAlpha()
        canvas.drawBitmap(alpha, 0f, 0f, Paint().apply { color = colour })
        val offset = IntArray(2)
        val soft = subject.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(width, BlurMaskFilter.Blur.NORMAL) }, offset)
        canvas.drawBitmap(soft, offset[0] + width * 1.4f, offset[1] + width * 1.4f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        })
        alpha.recycle()
        soft.recycle()
        return out
    }
}
