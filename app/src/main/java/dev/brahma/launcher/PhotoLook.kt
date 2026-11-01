package dev.brahma.launcher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Ready-made looks. AUTO reads the photo and sets exposure and contrast to suit it. */
enum class LookPreset(
    val title: String,
    private val exposure: Float = 0f, private val contrast: Float = 0f, private val saturation: Float = 0f,
    private val warmth: Float = 0f, private val fade: Float = 0f, private val vignette: Float = 0f,
    private val grain: Float = 0f, private val glow: Float = 0f
) {
    NONE("Original"),
    AUTO("Auto"),
    FILM("Warm film", 0.05f, 0.1f, -0.1f, 0.25f, 0.15f, 0.25f, 0.25f, 0.1f),
    CINEMA("Cinematic", 0f, 0.3f, 0.1f, 0.1f, 0.1f, 0.4f, 0.2f, 0.15f),
    NOIR("Noir", 0f, 0.35f, -1f, 0f, 0f, 0.45f, 0.45f, 0f),
    GOLDEN("Golden hour", 0.1f, 0f, 0.2f, 0.5f, 0f, 0.2f, 0f, 0.35f),
    MATTE("Matte", 0f, -0.1f, -0.15f, 0f, 0.5f, 0f, 0.15f, 0f),
    VIVID("Vivid", 0.03f, 0.2f, 0.45f),
    COOL("Cool", 0f, 0.1f, -0.05f, -0.4f, 0f, 0.2f),
    CUSTOM("Custom");

    /** The sliders this preset sets. */
    fun look() = PhotoLook(this, exposure, contrast, saturation, warmth, fade, vignette, grain, glow)
}

/**
 * Photo grading like a camera app: exposure, contrast, saturation and warmth run from -1 to 1;
 * fade (lifted blacks), vignette, grain and glow (soft bloom) from 0 to 1.
 */
data class PhotoLook(
    val preset: LookPreset = LookPreset.NONE,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val fade: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val glow: Float = 0f
) {
    /** True when drawing would change nothing, so the photo can be used as it is. */
    val plain get() = preset != LookPreset.AUTO && copy(preset = LookPreset.NONE) == PhotoLook()

    fun with(preset: LookPreset) = preset.look()

    fun encode() = JSONObject().apply {
        put("preset", preset.name)
        put("exposure", exposure.toDouble())
        put("contrast", contrast.toDouble())
        put("saturation", saturation.toDouble())
        put("warmth", warmth.toDouble())
        put("fade", fade.toDouble())
        put("vignette", vignette.toDouble())
        put("grain", grain.toDouble())
        put("glow", glow.toDouble())
    }

    companion object {
        fun decode(item: JSONObject?): PhotoLook {
            if (item == null) return PhotoLook()
            fun value(name: String) = item.optDouble(name, 0.0).toFloat()
            return PhotoLook(LookPreset.entries.find { it.name == item.optString("preset") } ?: LookPreset.NONE,
                value("exposure"), value("contrast"), value("saturation"), value("warmth"),
                value("fade"), value("vignette"), value("grain"), value("glow"))
        }

        /**
         * Exposure and contrast that bring a photo with these [pixels] (ARGB, a small copy is plenty)
         * toward a balanced picture: dark photos brighten, flat ones gain contrast.
         */
        fun balance(pixels: IntArray): PhotoLook {
            if (pixels.isEmpty()) return PhotoLook()
            val luma = FloatArray(pixels.size) {
                (0.299f * ((pixels[it] shr 16) and 0xFF) + 0.587f * ((pixels[it] shr 8) and 0xFF) + 0.114f * (pixels[it] and 0xFF)) / 255f
            }
            val mean = luma.average().toFloat()
            val spread = sqrt(luma.fold(0f) { sum, value -> sum + (value - mean) * (value - mean) } / luma.size)
            return PhotoLook(exposure = ((0.45f - mean) * 1.6f).coerceIn(-0.5f, 0.5f), contrast = ((0.22f - spread) * 1.5f).coerceIn(-0.1f, 0.3f))
        }
    }
}

object PhotoFilter {
    /** A graded copy of [photo]; [photo] itself is untouched. */
    fun apply(photo: Bitmap, look: PhotoLook): Bitmap {
        val use = if (look.preset == LookPreset.AUTO) {
            val small = Bitmap.createScaledBitmap(photo, 48, 48, true)
            val pixels = IntArray(48 * 48).also { small.getPixels(it, 0, 48, 0, 0, 48, 48) }
            val auto = PhotoLook.balance(pixels)
            look.copy(exposure = (look.exposure + auto.exposure).coerceIn(-1f, 1f), contrast = (look.contrast + auto.contrast).coerceIn(-1f, 1f))
        } else look
        val out = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(photo, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix(use)) })
        if (use.glow > 0f) bloom(canvas, out, use.glow)
        if (use.vignette > 0f) vignette(canvas, out.width, out.height, use.vignette)
        if (use.grain > 0f) grain(canvas, out.width, out.height, use.grain)
        return out
    }

    private fun matrix(look: PhotoLook): ColorMatrix {
        val gain = 2f.pow(look.exposure)
        val amount = 1f + look.contrast * 0.6f
        val shift = 128f * (1 - amount)
        val warm = look.warmth * 0.15f
        val lift = 0.22f * look.fade
        return ColorMatrix().apply {
            postConcat(ColorMatrix(floatArrayOf(gain, 0f, 0f, 0f, 0f, 0f, gain, 0f, 0f, 0f, 0f, 0f, gain, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            postConcat(ColorMatrix(floatArrayOf(amount, 0f, 0f, 0f, shift, 0f, amount, 0f, 0f, shift, 0f, 0f, amount, 0f, shift, 0f, 0f, 0f, 1f, 0f)))
            postConcat(ColorMatrix().apply { setSaturation((1f + look.saturation).coerceIn(0f, 2f)) })
            postConcat(ColorMatrix(floatArrayOf(1f + warm, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f - warm, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            postConcat(ColorMatrix(floatArrayOf(1f - lift, 0f, 0f, 0f, lift * 217f, 0f, 1f - lift, 0f, 0f, lift * 217f, 0f, 0f, 1f - lift, 0f, lift * 217f, 0f, 0f, 0f, 1f, 0f)))
        }
    }

    // Screens a heavily softened copy over the picture: highlights spill, as through a soft lens.
    private fun bloom(canvas: Canvas, picture: Bitmap, glow: Float) {
        val tiny = Bitmap.createScaledBitmap(picture, max(picture.width / 24, 1), max(picture.height / 24, 1), true)
        val soft = Bitmap.createScaledBitmap(tiny, picture.width, picture.height, true)
        canvas.drawBitmap(soft, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            alpha = (glow * 150).toInt()
        })
    }

    private fun vignette(canvas: Canvas, width: Int, height: Int, strength: Float) {
        val radius = hypot(width / 2f, height / 2f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = RadialGradient(width / 2f, height / 2f, radius, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((strength * 190).toInt(), 0, 0, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
    }

    // A tile of random light and dark specks, repeated over the picture.
    private fun grain(canvas: Canvas, width: Int, height: Int, strength: Float) {
        val side = 192
        val random = Random(7)
        val specks = IntArray(side * side) {
            val tone = if (random.nextBoolean()) 255 else 0
            Color.argb(random.nextInt((strength * 70).toInt() + 1), tone, tone, tone)
        }
        val tile = Bitmap.createBitmap(specks, side, side, Bitmap.Config.ARGB_8888)
        val grainSize = max(1f, max(width, height) / 1400f)
        canvas.save()
        canvas.scale(grainSize, grainSize)
        canvas.drawRect(0f, 0f, width / grainSize, height / grainSize, Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
        canvas.restore()
    }
}
