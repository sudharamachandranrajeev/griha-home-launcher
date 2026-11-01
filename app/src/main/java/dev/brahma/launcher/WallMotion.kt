package dev.brahma.launcher

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Home and all apps pass every touch and scroll here; it answers with light or motion. */
interface TouchBackdrop {
    /** Every touch on the screen, in the screen's own coordinates. Never consumes it. */
    fun touch(event: MotionEvent, view: View)
    /** How far the screen is scrolled, 0 at the top to 1 at the end. */
    fun scrolled(fraction: Float)
}

/**
 * Which picture sits behind a screen. Saved as text: "theme:LAYOUT:KIND" for a wallpaper drawn
 * for a layout, "file:NAME" for an imported picture.
 */
object WallChoice {
    fun of(source: WallpaperSource): String = when (source) {
        is WallpaperSource.Theme -> "theme:${source.layout.name}:${source.kind.name}"
        is WallpaperSource.Imported -> "file:${source.file.name}"
    }

    /** A picture shipped in a downloaded theme, as "pack:<theme id>/<path in the theme>". */
    fun ofThemeFile(themeId: String, file: java.io.File) = "pack:$themeId/${file.parentFile?.name}/${file.name}"

    fun source(choice: String?, files: List<java.io.File>, themes: java.io.File? = null): WallpaperSource? {
        val parts = choice?.split(':') ?: return null
        return when (parts.firstOrNull()) {
            "pack" -> {
                val path = parts.getOrNull(1) ?: return null
                if (!Regex("[a-z0-9-]+/wallpapers/[a-z0-9-]+\\.(jpg|png|webp)").matches(path) || themes == null) return null
                java.io.File(themes, path).takeIf { it.isFile }?.let { WallpaperSource.Imported(it) }
            }
            "theme" -> {
                val layout = HomeLayout.entries.find { it.name == parts.getOrNull(1) } ?: return null
                val kind = WallpaperKind.entries.find { it.name == parts.getOrNull(2) } ?: return null
                WallpaperSource.Theme(layout, kind)
            }
            "file" -> files.find { it.name == parts.getOrNull(1) }?.let { WallpaperSource.Imported(it) }
            else -> null
        }
    }

    fun title(choice: String?) = when {
        choice == null -> "None"
        choice.startsWith("pack:") -> "Theme picture · ${choice.removePrefix("pack:").substringBefore('/')}"
        choice.startsWith("theme:") -> choice.split(':').let { parts ->
            "${HomeLayout.entries.find { it.name == parts.getOrNull(1) }?.title ?: "Theme"} · " +
                (WallpaperKind.entries.find { it.name == parts.getOrNull(2) }?.title ?: "")
        }
        else -> "Your picture"
    }
}

/**
 * Android's own wallpaper behind home: Griha can only tell it where the screen is scrolled and
 * where it was tapped. Static pictures may drift a little; live wallpapers can answer taps.
 */
class SystemWallpaperMotion(private val manager: WallpaperManager, private val enabled: Boolean) : TouchBackdrop {
    override fun touch(event: MotionEvent, view: View) {
        if (!enabled || event.actionMasked != MotionEvent.ACTION_UP) return
        val token = view.windowToken ?: return
        val spot = IntArray(2).also { view.getLocationOnScreen(it) }
        manager.sendWallpaperCommand(token, WallpaperManager.COMMAND_TAP, spot[0] + event.x.toInt(), spot[1] + event.y.toInt(), 0, null)
    }

    private var token: android.os.IBinder? = null

    fun attach(view: View) { token = view.windowToken }

    override fun scrolled(fraction: Float) {
        if (!enabled) return
        val window = token ?: return
        manager.setWallpaperOffsetSteps(0f, 0.25f)
        manager.setWallpaperOffsets(window, 0.5f, fraction.coerceIn(0f, 1f))
    }
}

/**
 * A wallpaper Griha draws itself, behind home or all apps, that answers every touch in the
 * finish's own way. It drifts with scrolling and leans toward a held finger; a tap sends out the
 * finish's light (a soft bloom, an accent ring, a warm glow, glass ripples, embers); a swipe
 * pushes it and it springs back. All of it is subtle and costs nothing when the screen is still.
 */
class WallpaperDrawable(
    private val colors: Palette,
    private val density: Float,
    /** How much of the page colour lies over the picture, 0 to 255, so text stays readable. */
    private val dim: Int,
    private val motion: Boolean
) : Drawable(), TouchBackdrop, Choreographer.FrameCallback {
    var bitmap: Bitmap? = null
        set(value) { field = value; invalidateSelf() }

    private val fallback = FinishBackdrop(colors, density)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    // Drift in -1..1 on each axis: where the picture leans now, and where it is heading.
    private var driftX = 0f
    private var driftY = 0f
    private var aimX = 0f
    private var aimY = 0f
    private var scrollAim = 0f
    // A held finger: where it is, and how much its light is showing.
    private var fingerX = 0f
    private var fingerY = 0f
    private var held = false
    private var hold = 0f
    // A swipe's push, springing back to rest.
    private var pushX = 0f
    private var pushY = 0f
    private var tracker: VelocityTracker? = null
    private val pulses = mutableListOf<Pulse>()
    private val random = Random(SystemClock.uptimeMillis())
    private var ticking = false
    private var lastFrame = 0L
    private var tints = 0

    private class Pulse(val x: Float, val y: Float, val start: Long, val tint: Int, val sparks: List<FloatArray>)

    // ---- Input ----

    override fun touch(event: MotionEvent, view: View) {
        if (!motion) return
        val w = bounds.width().coerceAtLeast(1)
        val h = bounds.height().coerceAtLeast(1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                held = true
                follow(event.x, event.y, w, h)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                tracker?.addMovement(event)
                follow(event.x, event.y, w, h)
            }
            MotionEvent.ACTION_HOVER_ENTER -> { held = true; follow(event.x, event.y, w, h) }
            MotionEvent.ACTION_HOVER_EXIT -> held = false
            MotionEvent.ACTION_UP -> {
                val tracked = tracker
                tracked?.addMovement(event)
                tracked?.computeCurrentVelocity(1000)
                val vx = tracked?.xVelocity ?: 0f
                val vy = tracked?.yVelocity ?: 0f
                val slow = 600 * density
                if (abs(vx) < slow && abs(vy) < slow) pulse(event.x, event.y)
                else {
                    // A swipe: the picture is nudged the way the finger went, then settles.
                    pushX = (vx / (6000 * density)).coerceIn(-1f, 1f)
                    pushY = (vy / (6000 * density)).coerceIn(-1f, 1f)
                }
                release()
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        tick()
    }

    private fun release() {
        held = false
        aimX = 0f
        aimY = 0f
        tracker?.recycle()
        tracker = null
    }

    private fun follow(x: Float, y: Float, w: Int, h: Int) {
        fingerX = x
        fingerY = y
        // Lean gently toward the finger, as if the picture sits a little behind the glass.
        aimX = (x / w - 0.5f) * 2f
        aimY = (y / h - 0.5f) * 2f
    }

    override fun scrolled(fraction: Float) {
        if (!motion) return
        scrollAim = (fraction.coerceIn(0f, 1f) - 0.5f) * 2f
        tick()
    }

    /** The tap light: a theme names it; otherwise each finish has its own. */
    private val style: String = colors.theme?.pulse ?: when (colors.finish) {
        UiStyle.SOFT -> "bloom"
        UiStyle.TACTILE -> "glow"
        UiStyle.GLASS -> "ripple"
        UiStyle.CARVED -> "embers"
        UiStyle.FLAT -> "faint"
        UiStyle.LAYERED -> "ring"
    }
    /** Warm amber for the tactile finish's glow; a theme's glow takes its accent. */
    private val glowTint = if (colors.theme == null && colors.finish == UiStyle.TACTILE) Depth.AMBER else colors.accent

    private fun pulse(x: Float, y: Float) {
        if (style == "none") return
        val tint = when (style) {
            "ripple" -> Depth.glassTints[tints++ % Depth.glassTints.size]
            "glow" -> glowTint
            "bloom" -> Color.WHITE
            else -> colors.accent
        }
        val sparks = if (style == "embers") List(9) {
            floatArrayOf(random.nextFloat() * 2 - 1, -(0.4f + random.nextFloat()), 0.6f + random.nextFloat() * 0.8f)
        } else emptyList()
        pulses += Pulse(x, y, SystemClock.uptimeMillis(), tint, sparks)
        if (pulses.size > 6) pulses.removeAt(0)
    }

    // ---- Frames ----

    private fun tick() {
        if (ticking) return
        ticking = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val now = frameTimeNanos / 1_000_000
        val step = if (lastFrame == 0L) 16f else min(48f, (now - lastFrame).toFloat())
        lastFrame = now
        // Smooth easing toward the aim; time-based so it feels the same at any refresh rate.
        val ease = 1f - exp(-step / 140f)
        driftX += (aimX + pushX - driftX) * ease
        driftY += (aimY * 0.6f + scrollAim + pushY - driftY) * ease
        val spring = exp(-step / 260f)
        pushX *= spring
        pushY *= spring
        hold += ((if (held) 1f else 0f) - hold) * (1f - exp(-step / 120f))
        val clock = SystemClock.uptimeMillis()
        pulses.removeAll { clock - it.start > PULSE_MS }
        invalidateSelf()
        val moving = abs(aimX + pushX - driftX) > 0.002f || abs(aimY * 0.6f + scrollAim + pushY - driftY) > 0.002f ||
            abs(pushX) > 0.002f || abs(pushY) > 0.002f || pulses.isNotEmpty() || (hold > 0.01f && !held) || (held && hold < 0.99f)
        if (moving && callback != null) Choreographer.getInstance().postFrameCallback(this) else ticking = false
    }

    // ---- Drawing ----

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val picture = bitmap
        if (picture == null) {
            fallback.bounds = b
            fallback.draw(canvas)
        } else {
            // Cover the screen with room to drift: up to 3% of the screen each way.
            val room = if (motion) 0.03f else 0f
            val scale = max(w / picture.width, h / picture.height) * (1f + room * 2) * (1f + hold * 0.012f)
            matrix.setScale(scale, scale)
            matrix.postTranslate((w - picture.width * scale) / 2 - driftX * w * room, (h - picture.height * scale) / 2 - driftY * h * room)
            canvas.drawBitmap(picture, matrix, paint)
            canvas.drawColor((colors.background and 0x00FFFFFF) or (dim shl 24))
        }
        if (!motion) return
        // A soft light under a held finger.
        if (hold > 0.01f) {
            val r = 140 * density
            val colour = when (style) { "glow" -> glowTint; "bloom" -> Color.WHITE; else -> colors.accent }
            spot(canvas, fingerX, fingerY, r, tone(colour, 0.10f * hold))
        }
        val clock = SystemClock.uptimeMillis()
        pulses.forEach { pulse ->
            val t = ((clock - pulse.start) / PULSE_MS.toFloat()).coerceIn(0f, 1f)
            val fade = 1f - t
            val grow = 1f - (1f - t) * (1f - t)
            when (style) {
                "bloom" -> spot(canvas, pulse.x, pulse.y, (60 + 140 * grow) * density, tone(Color.WHITE, 0.32f * fade))
                "ring", "faint" -> {
                    ring(canvas, pulse.x, pulse.y, (12 + 90 * grow) * density, 2 * density, tone(pulse.tint, (if (style == "faint") 0.25f else 0.45f) * fade))
                    spot(canvas, pulse.x, pulse.y, (30 + 60 * grow) * density, tone(pulse.tint, 0.10f * fade))
                }
                "glow" -> {
                    spot(canvas, pulse.x, pulse.y, (50 + 110 * grow) * density, tone(pulse.tint, 0.40f * fade * fade))
                    spot(canvas, pulse.x, pulse.y, 26 * density, tone(Color.WHITE, 0.25f * fade))
                }
                "ripple" -> for (k in 0..1) {
                    val tk = (t - k * 0.18f).coerceAtLeast(0f)
                    if (tk <= 0f) continue
                    val g = 1f - (1f - tk) * (1f - tk)
                    ring(canvas, pulse.x, pulse.y, (16 + 120 * g) * density, (3 - k) * density, tone(pulse.tint, 0.5f * (1f - tk)))
                    ring(canvas, pulse.x, pulse.y, (14 + 120 * g) * density, density, tone(Color.WHITE, 0.45f * (1f - tk)))
                }
                "embers" -> {
                    spot(canvas, pulse.x, pulse.y, (40 + 80 * grow) * density, tone(pulse.tint, 0.35f * fade))
                    glow.shader = null
                    pulse.sparks.forEach { s ->
                        val travel = 70 * density * grow * s[2]
                        glow.color = tone(mix(pulse.tint, Color.WHITE, 0.4f), fade)
                        canvas.drawCircle(pulse.x + s[0] * travel, pulse.y + s[1] * travel, 1.6f * density * (1f + fade), glow)
                    }
                }
            }
        }
    }

    private fun spot(canvas: Canvas, x: Float, y: Float, radius: Float, colour: Int) {
        if (Color.alpha(colour) == 0 || radius <= 0f) return
        glow.style = Paint.Style.FILL
        glow.shader = RadialGradient(x, y, radius, colour, colour and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, glow)
        glow.shader = null
    }

    private fun ring(canvas: Canvas, x: Float, y: Float, radius: Float, width: Float, colour: Int) {
        if (Color.alpha(colour) == 0) return
        glow.shader = null
        glow.style = Paint.Style.STROKE
        glow.strokeWidth = width
        glow.color = colour
        canvas.drawCircle(x, y, radius, glow)
        glow.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    private companion object {
        const val PULSE_MS = 650L
    }
}
