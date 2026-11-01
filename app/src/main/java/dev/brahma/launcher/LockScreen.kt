package dev.brahma.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlin.math.cos
import kotlin.math.sin

enum class LockBackground(val title: String) {
    THEME("Layout colours and shapes"), GRADIENT("Gradient"), PHOTO("Photo"), BLURRED("Photo, blurred"),
    GENERATED("Generated from the photo's colours")
}

/** What the giant text behind the subject says in the iOS-style depth look. */
enum class LockBig(val title: String, val pattern: String) {
    DAY("Day number", DatePatterns.DAY), WEEKDAY("Weekday", DatePatterns.WEEKDAY), MONTH("Month", DatePatterns.MONTH)
}

enum class LockFont(val title: String, val family: String, val style: Int = Typeface.NORMAL) {
    THIN("Thin", "sans-serif-thin"), HEAVY("Heavy", "sans-serif-black"), BOLD("Bold", "sans-serif", Typeface.BOLD), CONDENSED("Condensed", "sans-serif-condensed", Typeface.BOLD),
    SERIF("Serif", "serif"), SCRIPT("Script", dev.brahma.launcher.SCRIPT)
}

/**
 * The lock designer's choices. With [custom] off the lock screen follows the home layout.
 * [textColor] 0 means the layout's accent (white over photos). Positions are screen fractions.
 */
data class LockDesign(
    val custom: Boolean = false,
    val background: LockBackground = LockBackground.THEME,
    val stickerId: String? = null,
    val depth: Boolean = true,
    val font: LockFont = LockFont.THIN,
    val textColor: Int = 0,
    val textSize: Float = 0.5f,
    val shadow: Boolean = false,
    val textY: Float = 0.6f,
    val subjectX: Float = 0.5f,
    val subjectY: Float = 0.7f,
    val subjectScale: Float = 0.8f,
    /** iOS-style depth: giant text just below Android's clock, with the photo moved so the subject overlaps it. */
    val ios: Boolean = false,
    val big: LockBig = LockBig.DAY,
    /** A photo picked just for the lock screen; it wins over [stickerId]'s photo. [photoVersion] changes with it. */
    val photoId: String? = null,
    val photoVersion: Long = 0,
    val look: PhotoLook = PhotoLook(),
    /** Null picks a style from [seed]. With [daily] on, the picture also changes every day. */
    val backdrop: BackdropStyle? = null,
    val seed: Long = 1,
    val daily: Boolean = false,
    /** Keeps text and subject out of the band where Android draws its clock. */
    val clearClock: Boolean = true,
    val style: LockStyle = LockStyle.PLAIN,
    /** How the blurred photo background looks. */
    val blur: LockBlur = LockBlur()
) {
    fun encode(): String = JSONObject().apply {
        put("custom", custom)
        put("background", background.name)
        put("sticker", stickerId ?: "")
        put("depth", depth)
        put("font", font.name)
        put("text_color", textColor)
        put("text_size", textSize.toDouble())
        put("shadow", shadow)
        put("text_y", textY.toDouble())
        put("subject_x", subjectX.toDouble())
        put("subject_y", subjectY.toDouble())
        put("subject_scale", subjectScale.toDouble())
        put("ios", ios)
        put("big", big.name)
        put("photo", photoId ?: "")
        put("photo_version", photoVersion)
        put("look", look.encode())
        put("backdrop", backdrop?.name ?: "")
        put("seed", seed)
        put("daily", daily)
        put("clear_clock", clearClock)
        put("fx", style.name)
        put("blur", blur.encode())
    }.toString()

    companion object {
        fun decode(text: String?): LockDesign {
            if (text.isNullOrBlank()) return LockDesign()
            return try {
                val item = JSONObject(text)
                LockDesign(
                    custom = item.optBoolean("custom"),
                    background = LockBackground.entries.find { it.name == item.optString("background") } ?: LockBackground.THEME,
                    stickerId = item.optString("sticker").ifBlank { null },
                    depth = item.optBoolean("depth", true),
                    font = LockFont.entries.find { it.name == item.optString("font") } ?: LockFont.THIN,
                    textColor = item.optInt("text_color"),
                    textSize = item.optDouble("text_size", 0.5).toFloat(),
                    shadow = item.optBoolean("shadow"),
                    textY = item.optDouble("text_y", 0.6).toFloat(),
                    subjectX = item.optDouble("subject_x", 0.5).toFloat(),
                    subjectY = item.optDouble("subject_y", 0.7).toFloat(),
                    subjectScale = item.optDouble("subject_scale", 0.8).toFloat(),
                    ios = item.optBoolean("ios"),
                    big = LockBig.entries.find { it.name == item.optString("big") } ?: LockBig.DAY,
                    photoId = item.optString("photo").ifBlank { null },
                    photoVersion = item.optLong("photo_version"),
                    look = PhotoLook.decode(item.optJSONObject("look")),
                    backdrop = BackdropStyle.entries.find { it.name == item.optString("backdrop") },
                    seed = item.optLong("seed", 1),
                    daily = item.optBoolean("daily"),
                    clearClock = item.optBoolean("clear_clock", true),
                    style = LockStyle.entries.find { it.name == item.optString("fx") } ?: LockStyle.PLAIN,
                    blur = LockBlur.decode(item.optJSONObject("blur"))
                )
            } catch (_: org.json.JSONException) {
                LockDesign()
            }
        }
    }
}

/**
 * Draws a lock-screen wallpaper, either from the home layout or from the lock designer.
 * Android's own clock and unlock UI always sit on top; the designer shows where.
 */
object LockScreen {
    /** Share of the screen height, from the top, that Android's lock clock usually covers. */
    const val CLOCK_TOP = 0.08f
    const val CLOCK_BOTTOM = 0.3f

    /** Everything the picture shows; a new picture is needed only when this changes. */
    fun key(settings: LauncherSettings, dark: Boolean, today: LocalDate, design: LockDesign = LockDesign(), sticker: Sticker? = null) =
        listOf(today, settings.layout.name, dark, settings.name.trim(), design.encode(), sticker?.version ?: "").joinToString("|")

    fun render(
        context: Context, settings: LauncherSettings, dark: Boolean, width: Int, height: Int,
        design: LockDesign = LockDesign(), sticker: Sticker? = null
    ): Bitmap {
        val colors = settings.layout.palette(dark)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val locale: Locale = context.resources.configuration.locales[0]
        val now = LocalDateTime.now()
        if (!design.custom) {
            canvas.drawColor(colors.background)
            motif(context, canvas, settings.layout, colors, width, height)
            clearBand(canvas, colors.background, width, height)
            val script = settings.layout == HomeLayout.ARCHES || settings.layout == HomeLayout.CARDS
            drawText(canvas, now, settings.name, locale, width, height, 0.6f, 1f, colors.accent, colors.ink, colors.muted,
                when {
                    script -> context.resources.getFont(R.font.dancing_script)
                    settings.layout == HomeLayout.LUXE -> context.resources.getFont(R.font.cinzel)
                    else -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
                }, false)
            return bitmap
        }
        val files = StickerFiles(context)
        val usable = sticker?.takeIf { files.source(it.id).exists() }
        val fromPhoto = design.background == LockBackground.PHOTO || design.background == LockBackground.BLURRED ||
            design.background == LockBackground.GENERATED
        val raw = usable?.let { files.load(files.source(it.id), maxOf(width, height)) }
        val photo = raw?.let { if (design.look.plain) it else PhotoFilter.apply(it, design.look) }
        val mask = usable?.takeIf { fromPhoto && photo != null && it.subject }?.let { files.load(files.mask(it.id), maxOf(width, height)) }
        val typeface = design.style.font?.let { context.resources.getFont(it) }
            ?: if (design.font == LockFont.SCRIPT) context.resources.getFont(R.font.dancing_script)
            else Typeface.create(design.font.family, design.font.style)
        val depthText = if (design.ios) IosText.measure(now, locale, design, typeface, width, height) else null
        val clockBottom = height * (CLOCK_BOTTOM + 0.03f)
        // Photo pixels to screen pixels: centre-cropped; in the iOS look moved so the head overlaps the giant text;
        // otherwise only moved down when the subject would reach into Android's clock.
        val box = mask?.let { StickerArt.bounds(it) }
        val fit = when {
            photo == null -> floatArrayOf(1f, 0f, 0f)
            depthText != null && box != null -> fitSubject(photo.width, photo.height, box, width, height, depthText.overlapY)
            else -> {
                val scale = maxOf(width / photo.width.toFloat(), height / photo.height.toFloat())
                val covered = floatArrayOf(scale, (width - photo.width * scale) / 2, (height - photo.height * scale) / 2)
                if (design.clearClock && box != null && covered[2] + box[1] * scale < clockBottom)
                    fitSubject(photo.width, photo.height, box, width, height, clockBottom)
                else covered
            }
        }
        val photoMatrix = Matrix().apply {
            setScale(fit[0], fit[0])
            postTranslate(fit[1], fit[2])
        }
        when {
            photo != null && design.background == LockBackground.PHOTO -> canvas.drawBitmap(photo, photoMatrix, Paint(Paint.FILTER_BITMAP_FLAG))
            photo != null && design.background == LockBackground.BLURRED ->
                LockBlurRenderer.draw(canvas, photo, photoMatrix, design.blur, width, height, colors.background)
            design.background == LockBackground.GENERATED -> {
                val colours = photo?.let {
                    val small = Bitmap.createScaledBitmap(it, 32, 32, true)
                    Palettes.dominant(IntArray(32 * 32).also { pixels -> small.getPixels(pixels, 0, 32, 0, 0, 32, 32) }, 4)
                } ?: listOf(colors.accent, colors.secondary, mix(colors.accent, Color.WHITE, 0.5f), colors.ink)
                val seed = Backdrops.seedFor(design.seed, design.daily, LocalDate.now().toEpochDay())
                val styles = BackdropStyle.entries
                Backdrops.draw(canvas, design.backdrop ?: styles[Random(seed).nextInt(styles.size)], colours, width, height, seed)
            }
            design.background == LockBackground.GRADIENT -> {
                canvas.drawPaint(Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, height.toFloat(), mix(colors.accent, colors.background, 0.15f),
                        mix(colors.background, colors.secondary, 0.25f), Shader.TileMode.CLAMP)
                })
                if (design.clearClock) clearBand(canvas, mix(colors.accent, colors.background, 0.15f), width, height)
            }
            else -> {
                canvas.drawColor(colors.background)
                motif(context, canvas, settings.layout, colors, width, height)
                if (design.clearClock) clearBand(canvas, colors.background, width, height)
            }
        }
        val drawSubject: () -> Unit = drawSubject@{
            if (usable == null || photo == null) return@drawSubject
            if (fromPhoto && usable.subject) {
                // The same photo's subject, lined up exactly over the background: the depth layer.
                if (mask == null) return@drawSubject
                val drawn = StickerArt.render(photo, StickerArt.softEdge(mask, usable.softness), usable.effects, crop = false)
                canvas.drawBitmap(drawn.bitmap, photoMatrix, Paint(Paint.FILTER_BITMAP_FLAG))
            } else if (!fromPhoto) {
                val art = files.load(files.drawn(usable.id)) ?: return@drawSubject
                val target = width * design.subjectScale
                val artScale = target / art.width
                val cx = width * design.subjectX
                var cy = height * design.subjectY
                if (design.clearClock) cy = maxOf(cy, clockBottom + art.height * artScale / 2)
                canvas.drawBitmap(art, Matrix().apply {
                    setScale(artScale, artScale)
                    postTranslate(cx - target / 2, cy - art.height * artScale / 2)
                }, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        val onImage = fromPhoto && (photo != null || design.background == LockBackground.GENERATED)
        // Text goes below Android's clock when asked, and always in white or black, whichever reads on the picture.
        val textTop = if (design.clearClock) maxOf(design.textY, (clockBottom + width * 0.2f * (0.5f + design.textSize)) / height) else design.textY
        val light = onImage && isLight(bitmap, height * textTop - width * 0.16f, height * textTop + width * 0.18f)
        val ink = if (light) 0xFF111111.toInt() else Color.WHITE
        val main = if (design.textColor != 0) design.textColor
            else if (design.style.colour != null) design.style.colour
            else if (depthText != null && onImage) tintFrom(bitmap, depthText.bandTop, depthText.bandBottom)
            else if (onImage) ink else colors.accent
        val second = if (onImage) ink else colors.ink
        val third = if (onImage) (ink and 0x00FFFFFF) or 0xE6000000.toInt() else colors.muted
        if (!design.depth) drawSubject()
        if (depthText != null) depthText.draw(canvas, main, if (onImage) ink else colors.ink, design.shadow, design.style)
        else drawText(canvas, now, settings.name, locale, width, height, textTop, 0.5f + design.textSize, main, second, third,
            typeface, design.shadow, design.style)
        if (design.depth) drawSubject()
        return bitmap
    }

    /** Fades [colour] over the top of the picture so decoration does not fight Android's clock. */
    private fun clearBand(canvas: Canvas, colour: Int, width: Int, height: Int) {
        val solid = (colour and 0x00FFFFFF) or 0xE6000000.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height * (CLOCK_BOTTOM + 0.05f), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height * (CLOCK_BOTTOM + 0.05f), intArrayOf(solid, solid, colour and 0x00FFFFFF),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        })
    }

    private fun isLight(picture: Bitmap, top: Float, bottom: Float): Boolean {
        val y = top.toInt().coerceIn(0, picture.height - 1)
        val band = Bitmap.createBitmap(picture, 0, y, picture.width, (bottom - top).toInt().coerceIn(1, picture.height - y))
        val average = Bitmap.createScaledBitmap(band, 1, 1, true).getPixel(0, 0)
        return (0.299f * Color.red(average) + 0.587f * Color.green(average) + 0.114f * Color.blue(average)) / 255f > 0.62f
    }

    /**
     * Scale and offset (x, y) that put the top of the subject's [box] (photo pixels: left, top,
     * right, bottom) at [targetTop] on the screen, centred across, while the photo still covers
     * the whole screen. It zooms in up to [MAX_ZOOM] times when a shift alone cannot do it.
     */
    fun fitSubject(photoWidth: Int, photoHeight: Int, box: IntArray, width: Int, height: Int, targetTop: Float): FloatArray {
        val cover = maxOf(width / photoWidth.toFloat(), height / photoHeight.toFloat())
        val top = box[1].toFloat()
        var scale = cover
        // Subject top too high: zoom until it can come down far enough with the photo's top still at the screen edge.
        if (top > 0 && top * scale < targetTop) scale = targetTop / top
        // Subject top too low: zoom until the photo below it still reaches the bottom of the screen.
        val below = photoHeight - top
        if (below * scale < height - targetTop) scale = (height - targetTop) / below
        scale = scale.coerceIn(cover, cover * MAX_ZOOM)
        val y = (targetTop - top * scale).coerceIn(height - photoHeight * scale, 0f)
        val centre = (box[0] + box[2]) / 2f
        val x = (width / 2f - centre * scale).coerceIn(width - photoWidth * scale, 0f)
        return floatArrayOf(scale, x, y)
    }

    private const val MAX_ZOOM = 1.8f

    // A light tint of the photo where the text sits, or a deep one over light photos, as iOS does.
    private fun tintFrom(picture: Bitmap, top: Float, bottom: Float): Int {
        val y = top.toInt().coerceIn(0, picture.height - 1)
        val band = Bitmap.createBitmap(picture, 0, y, picture.width, (bottom - top).toInt().coerceIn(1, picture.height - y))
        val average = Bitmap.createScaledBitmap(band, 1, 1, true).getPixel(0, 0)
        val light = (0.299f * Color.red(average) + 0.587f * Color.green(average) + 0.114f * Color.blue(average)) / 255f
        return if (light < 0.6f) mix(average, Color.WHITE, 0.82f) else mix(average, Color.BLACK, 0.7f)
    }

    /** The iOS-style text: a small date line and giant centred text just below Android's clock. */
    private class IosText(
        val small: String, val smallPaint: Paint, val smallY: Float,
        val big: String, val bigPaint: Paint, val bigY: Float,
        val bandTop: Float, val bandBottom: Float, val overlapY: Float, val width: Int
    ) {
        fun draw(canvas: Canvas, main: Int, second: Int, shadow: Boolean, style: LockStyle) {
            smallPaint.color = second
            bigPaint.color = main
            if (shadow) smallPaint.setShadowLayer(width * 0.02f, 0f, width * 0.006f, 0x99000000.toInt())
            if (shadow && style == LockStyle.PLAIN) bigPaint.setShadowLayer(width * 0.02f, 0f, width * 0.006f, 0x99000000.toInt())
            canvas.drawText(small, width / 2f, smallY, smallPaint)
            TextFx.draw(canvas, big, width / 2f, bigY, bigPaint, style, main)
        }

        companion object {
            fun measure(now: LocalDateTime, locale: Locale, design: LockDesign, typeface: Typeface, width: Int, height: Int): IosText {
                val top = height * (CLOCK_BOTTOM + 0.015f)
                val small = now.format(DateTimeFormatter.ofPattern(DatePatterns.SPOKEN_DATE, locale))
                val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    this.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    textSize = width * 0.045f
                }
                val smallY = top + smallPaint.textSize
                val big = now.format(DateTimeFormatter.ofPattern(design.big.pattern, locale))
                val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    this.typeface = typeface
                    textSize = width * (0.25f + design.textSize * 0.4f)
                }
                while (bigPaint.measureText(big) > width * 0.9f && bigPaint.textSize > 8) bigPaint.textSize *= 0.94f
                // Ink bounds, so the giant text sits snug under the date whatever the font.
                val ink = android.graphics.Rect().also { bigPaint.getTextBounds(big, 0, big.length, it) }
                val bigY = smallY + width * 0.02f - ink.top
                val inkTop = bigY + ink.top
                val inkBottom = bigY + ink.bottom
                // The head covers the lower part of the text and leaves the top 45% clear to read.
                return IosText(small, smallPaint, smallY, big, bigPaint, bigY, inkTop, inkBottom,
                    inkTop + (inkBottom - inkTop) * 0.45f, width)
            }
        }
    }

    private fun drawText(
        canvas: Canvas, now: LocalDateTime, name: String, locale: Locale, width: Int, height: Int,
        y: Float, size: Float, main: Int, second: Int, third: Int, typeface: Typeface, shadow: Boolean,
        style: LockStyle = LockStyle.PLAIN
    ) {
        val left = width * 0.08f
        val maxWidth = width * 0.84f
        val base = height * y
        val day = text(now.format(DateTimeFormatter.ofPattern(DatePatterns.WEEKDAY, locale)), width * 0.16f * size, main, typeface, maxWidth)
        val date = text(now.format(DateTimeFormatter.ofPattern(DatePatterns.SPOKEN_DATE, locale)), width * 0.05f, second,
            Typeface.create("sans-serif-medium", Typeface.NORMAL), maxWidth)
        val line = text(HomeText.lockLine(now, name, locale), width * 0.04f, third, Typeface.DEFAULT, maxWidth)
        if (shadow) listOf(day, date, line).forEach { it.second.setShadowLayer(width * 0.02f, 0f, width * 0.006f, 0x99000000.toInt()) }
        if (style != LockStyle.PLAIN) day.second.clearShadowLayer()
        TextFx.draw(canvas, day.first, left, base, day.second, style, main)
        canvas.drawText(date.first, left, base + width * 0.09f, date.second)
        canvas.drawText(line.first, left, base + width * 0.16f, line.second)
    }

    // Shrinks the text until it fits the width.
    private fun text(value: String, size: Float, color: Int, typeface: Typeface, maxWidth: Float): Pair<String, Paint> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.typeface = typeface
            textSize = size
        }
        while (paint.measureText(value) > maxWidth && paint.textSize > 8) paint.textSize *= 0.92f
        return value to paint
    }

    /** The layout's signature shapes, drawn over its background colour. Theme wallpapers use it too. */
    fun motif(context: Context, canvas: Canvas, layout: HomeLayout, colors: Palette, width: Int, height: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (layout) {
            HomeLayout.ARCHES -> LineArt(context, colors.secondary, height).apply {
                measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                layout(0, 0, width, height)
                draw(canvas)
            }
            HomeLayout.EDITORIAL -> {
                paint.color = colors.secondary
                for (row in 0 until 2) canvas.drawRect(0f, h * 0.5f + row * w * 0.03f, w, h * 0.5f + row * w * 0.03f + w * 0.012f, paint)
                paint.color = colors.accent
                canvas.drawRect(w * 0.72f, h * 0.44f, w, h * 0.5f - w * 0.02f, paint)
            }
            HomeLayout.MONO -> {
                paint.color = colors.ink
                canvas.drawRect(w * 0.08f, h * 0.53f, w * 0.18f, h * 0.53f + w * 0.012f, paint)
            }
            HomeLayout.NIGHT -> {
                paint.color = colors.secondary
                canvas.drawRect(w * 0.05f - 1, h * 0.7f, w * 0.05f + 1, h * 0.9f, paint)
                paint.color = colors.ink
                for (dot in 0 until 3) canvas.drawCircle(w * 0.05f, h * 0.72f + dot * h * 0.08f, w * 0.012f, paint)
            }
            HomeLayout.DIAL -> {
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = colors.secondary
                val cx = w * 0.5f
                val cy = h * 0.82f
                val radius = w * 0.34f
                for (tick in 0 until 60) {
                    val angle = Math.toRadians(tick * 6.0)
                    val inner = radius * if (tick % 5 == 0) 0.86f else 0.92f
                    paint.strokeWidth = w * if (tick % 5 == 0) 0.005f else 0.0025f
                    canvas.drawLine(cx + inner * cos(angle).toFloat(), cy + inner * sin(angle).toFloat(),
                        cx + radius * cos(angle).toFloat(), cy + radius * sin(angle).toFloat(), paint)
                }
            }
            HomeLayout.LUXE -> {
                // Art-deco: a fan of fine gold rays from the bottom, and a ruled line with a diamond in the middle.
                paint.color = (colors.accent and 0x00FFFFFF) or 0x40000000
                paint.strokeWidth = w * 0.003f
                for (ray in 0..12) {
                    val angle = Math.toRadians(180.0 + ray * 15.0)
                    canvas.drawLine(w / 2, h, w / 2 + w * 1.2f * cos(angle).toFloat(), h + w * 1.2f * sin(angle).toFloat(), paint)
                }
                paint.color = colors.accent
                val y = h * 0.52f
                canvas.drawRect(w * 0.12f, y - w * 0.002f, w * 0.42f, y + w * 0.002f, paint)
                canvas.drawRect(w * 0.58f, y - w * 0.002f, w * 0.88f, y + w * 0.002f, paint)
                val d = w * 0.035f
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(w / 2, y - d); lineTo(w / 2 + d, y); lineTo(w / 2, y + d); lineTo(w / 2 - d, y); close()
                }, paint)
            }
            HomeLayout.CARDS, HomeLayout.SOFT -> {
                paint.color = colors.surface
                paint.setShadowLayer(w * 0.03f, 0f, w * 0.015f, if (colors.dark) 0x66000000 else 0x337D8AA3)
                canvas.drawRoundRect(RectF(w * 0.05f, h * 0.53f, w * 0.95f, h * 0.53f + w * 0.3f), w * 0.07f, w * 0.07f, paint)
            }
        }
    }
}
