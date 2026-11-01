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
    val blur: LockBlur = LockBlur(),
    /** Under a theme, the text and non-photo backgrounds take the theme's colours; photos stay as they are. */
    val followTheme: Boolean = true,
    /** A light touch of the theme's colour on the subject; the photo behind is never changed. */
    val subjectLook: SubjectLook = SubjectLook.THEME,
    val subjectAmount: Float = 0.35f,
    /** Under a theme, its clock piece (date edition) stands in for the date text. */
    val themeClock: Boolean = false
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
        put("follow_theme", followTheme)
        put("subject_look", subjectLook.name)
        put("subject_amount", subjectAmount.toDouble())
        put("theme_clock", themeClock)
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
                    blur = LockBlur.decode(item.optJSONObject("blur")),
                    followTheme = item.optBoolean("follow_theme", true),
                    subjectLook = SubjectLook.entries.find { it.name == item.optString("subject_look") } ?: SubjectLook.THEME,
                    subjectAmount = item.optDouble("subject_amount", 0.35).toFloat().coerceIn(0f, 1f),
                    themeClock = item.optBoolean("theme_clock")
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
        listOf(today, settings.layout.name, dark, settings.name.trim(), design.encode(), sticker?.version ?: "",
            settings.activeTheme()?.let { "${it.id}@${it.version}" } ?: "").joinToString("|")

    fun render(
        context: Context, settings: LauncherSettings, dark: Boolean, width: Int, height: Int,
        design: LockDesign = LockDesign(), sticker: Sticker? = null
    ): Bitmap {
        val theme = settings.activeTheme()?.takeIf { !design.custom || design.followTheme }
        val colors = if (theme != null) settings.homePalette(dark) else settings.layout.palette(dark)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val locale: Locale = context.resources.configuration.locales[0]
        val now = LocalDateTime.now()
        if (!design.custom && theme != null) {
            // The theme's own lock: its picture or its page, its lettering and colours.
            val picture = theme.wallpaper(drawer = false)?.let { StickerFiles(context).load(it, maxOf(width, height)) }
            if (picture != null) {
                val scale = maxOf(width / picture.width.toFloat(), height / picture.height.toFloat())
                canvas.drawBitmap(picture, Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((width - picture.width * scale) / 2, (height - picture.height * scale) / 2)
                }, Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                ThemeBackdrop.draw(canvas, android.graphics.Rect(0, 0, width, height), colors, theme, width / 400f)
            }
            // No band is cleared: a theme's page is calm enough for Android's clock, and a cut would break its art.
            val font = theme.lockFont ?: LockFont.THIN
            val face = if (font == LockFont.SCRIPT) context.resources.getFont(R.font.dancing_script) else Typeface.create(font.family, font.style)
            val piece = clockPiece(context, settings, colors, theme, width, picture != null)
            if (piece != null) {
                canvas.drawBitmap(piece, 0f, height * (CLOCK_BOTTOM + 0.03f), Paint(Paint.FILTER_BITMAP_FLAG))
            } else if (picture != null) {
                // The theme's picture may be dark in light mode or light in dark mode, so the text reads the picture.
                val under = average(bitmap, height * 0.5f, height * 0.75f)
                val ink = if (ThemeSpec.luminance(under) < 0.3f) Color.WHITE else 0xFF111111.toInt()
                drawText(canvas, now, settings.name, locale, width, height, 0.6f, 1f, readable(theme.lockText(colors), under), ink,
                    (ink and 0x00FFFFFF) or 0xCC000000.toInt(), face, true)
            } else {
                drawText(canvas, now, settings.name, locale, width, height, 0.6f, 1f, theme.lockText(colors), colors.ink, colors.muted, face, false)
            }
            return bitmap
        }
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
        val (look, amount) = SubjectStyler.resolve(design, settings.activeTheme())
        val tint = settings.activeTheme()?.lockTint(colors) ?: colors.accent
        val styled: (Bitmap, Matrix) -> Unit = { art, at -> SubjectStyler.draw(canvas, art, at, look, amount, tint, colors) }
        val drawSubject: () -> Unit = drawSubject@{
            if (usable == null || photo == null) return@drawSubject
            if (fromPhoto && usable.subject) {
                // The same photo's subject, lined up exactly over the background: the depth layer.
                if (mask == null) return@drawSubject
                val drawn = StickerArt.render(photo, StickerArt.softEdge(mask, usable.softness), usable.effects, crop = false)
                styled(drawn.bitmap, photoMatrix)
            } else if (!fromPhoto) {
                val art = files.load(files.drawn(usable.id)) ?: return@drawSubject
                val target = width * design.subjectScale
                val artScale = target / art.width
                val cx = width * design.subjectX
                var cy = height * design.subjectY
                if (design.clearClock) cy = maxOf(cy, clockBottom + art.height * artScale / 2)
                styled(art, Matrix().apply {
                    setScale(artScale, artScale)
                    postTranslate(cx - target / 2, cy - art.height * artScale / 2)
                })
            }
        }
        val onImage = fromPhoto && (photo != null || design.background == LockBackground.GENERATED)
        // Text goes below Android's clock when asked, and always in white or black, whichever reads on the picture.
        val textTop = if (design.clearClock) maxOf(design.textY, (clockBottom + width * 0.2f * (0.5f + design.textSize)) / height) else design.textY
        val light = onImage && isLight(bitmap, height * textTop - width * 0.16f, height * textTop + width * 0.18f)
        val ink = if (light) 0xFF111111.toInt() else Color.WHITE
        val main = if (design.textColor != 0) design.textColor
            else if (design.style.colour != null) design.style.colour
            // Under a theme the date is in its colour, shaded just enough to read on the photo.
            else if (theme != null && onImage) readable(theme.lockText(colors),
                if (depthText != null) average(bitmap, depthText.bandTop, depthText.bandBottom)
                else average(bitmap, height * textTop - width * 0.16f, height * textTop + width * 0.18f))
            else if (theme != null) theme.lockText(colors)
            else if (depthText != null && onImage) tintFrom(bitmap, depthText.bandTop, depthText.bandBottom)
            else if (onImage) ink else colors.accent
        val second = if (onImage) ink else colors.ink
        val third = if (onImage) (ink and 0x00FFFFFF) or 0xE6000000.toInt() else colors.muted
        if (!design.depth) drawSubject()
        val piece = if (design.themeClock && theme != null && depthText == null) clockPiece(context, settings, colors, theme, width, onImage) else null
        if (piece != null) {
            val top = height * textTop - width * 0.2f
            canvas.drawBitmap(piece, 0f, if (design.clearClock) maxOf(top, clockBottom) else top, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        else if (depthText != null) depthText.draw(canvas, main, if (onImage) ink else colors.ink, design.shadow, design.style)
        else drawText(canvas, now, settings.name, locale, width, height, textTop, 0.5f + design.textSize, main, second, third,
            typeface, design.shadow, design.style)
        if (design.depth) drawSubject()
        return bitmap
    }

    /**
     * The theme's clock piece in its date edition, [width] pixels wide with room around it for its
     * shadows, or null when the theme has none. Views are built on the main thread, as Android expects.
     */
    private fun clockPiece(context: Context, settings: LauncherSettings, colors: Palette, theme: ThemeSpec, width: Int, onPhoto: Boolean): Bitmap? =
        onMain {
            val screen = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val scale = width / screen.toFloat()
            val preferences = context.getSharedPreferences("lock-piece", Context.MODE_PRIVATE)
            val none: () -> Unit = {}
            val actions = HomeActions({ _, _ -> }, {}, none, none, none, none, none, none, {}, none, {}, LuxeHooks(LuxeStore(preferences), none, none))
            val kit = HomeKit(context, colors, theme, settings, emptyList(), emptyList(), actions,
                android.text.format.DateFormat.is24HourFormat(context), context.resources.configuration.locales[0],
                tick = { it() }, battery = { null to false }, weather = { null }, icon = { View(context) },
                clock = { size, colour -> android.widget.TextClock(context).apply { textSize = size; setTextColor(colour) } }, lock = true)
            val piece = ThemeHome(kit).lockPiece(theme, onPhoto) ?: return@onMain null
            val margin = (20 * context.resources.displayMetrics.density).toInt()
            val frame = android.widget.FrameLayout(context).apply {
                setPadding(margin, margin, margin, margin)
                addView(piece, android.widget.FrameLayout.LayoutParams(-1, -2))
            }
            unclip(frame)
            frame.measure(View.MeasureSpec.makeMeasureSpec(screen, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, (frame.measuredHeight * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { scale(scale, scale); frame.draw(this) }
            bitmap
        }

    // Off screen nothing is attached, so shadows are let out of every box by hand.
    private fun unclip(view: View) {
        if (view !is android.view.ViewGroup) return
        view.clipChildren = false
        view.clipToPadding = false
        for (i in 0 until view.childCount) unclip(view.getChildAt(i))
    }

    private fun <T> onMain(block: () -> T?): T? {
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == main) return try { block() } catch (_: RuntimeException) { null }
        var result: T? = null
        val done = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(main).post {
            try { result = block() } catch (_: RuntimeException) { result = null } finally { done.countDown() }
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    /** Fades [colour] over the top of the picture so decoration does not fight Android's clock. */
    private fun clearBand(canvas: Canvas, colour: Int, width: Int, height: Int) {
        val solid = (colour and 0x00FFFFFF) or 0xE6000000.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height * (CLOCK_BOTTOM + 0.05f), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height * (CLOCK_BOTTOM + 0.05f), intArrayOf(solid, solid, colour and 0x00FFFFFF),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /** The picture's average colour between [top] and [bottom]. */
    private fun average(picture: Bitmap, top: Float, bottom: Float): Int {
        val y = top.toInt().coerceIn(0, picture.height - 1)
        val band = Bitmap.createBitmap(picture, 0, y, picture.width, (bottom - top).toInt().coerceIn(1, picture.height - y))
        return Bitmap.createScaledBitmap(band, 1, 1, true).getPixel(0, 0) or 0xFF000000.toInt()
    }

    /** [colour], lightened or deepened in steps until it reads on [under]. */
    fun readable(colour: Int, under: Int): Int {
        val towards = if (ThemeSpec.luminance(under) < 0.3f) Color.WHITE else Color.BLACK
        var shade = colour or 0xFF000000.toInt()
        var step = 0
        while (ThemeSpec.contrast(shade, under) < ThemeSpec.MIN_CONTRAST && step < 10) {
            step++
            shade = mix(colour or 0xFF000000.toInt(), towards, step * 0.1f)
        }
        return shade
    }

    private fun isLight(picture: Bitmap, top: Float, bottom: Float): Boolean {
        val average = average(picture, top, bottom)
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
