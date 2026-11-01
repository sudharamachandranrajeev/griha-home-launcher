package dev.brahma.launcher

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What a surface is for; each finish draws the four roles in its own way. */
enum class Role {
    /** A panel that holds other things. */
    CARD,
    /** A small button, chip or knob. */
    KEY,
    /** Something you type in or a track: pressed into the surface. */
    FIELD,
    /** The main action, filled with the accent colour. */
    PRIMARY
}

/** One blurred shadow, highlight or glow, like a CSS box-shadow: offset, blur, spread and colour in pixels. */
class Shade(val dx: Float, val dy: Float, val blur: Float, val color: Int, val spread: Float = 0f)

/** Everything one surface needs: its fill, edges, the light around it and how it reacts to a touch. */
class DepthSpec(val radius: Float) {
    /** Fill from top to bottom; colours may be see-through (glass). */
    var fill = intArrayOf(Color.WHITE, Color.WHITE)
    var rimTop = Color.TRANSPARENT
    var rimBottom = Color.TRANSPARENT
    var rimWidth = 0f
    /** Rim colour while glowing; transparent keeps the normal rim. */
    var glowRim = Color.TRANSPARENT
    /** Drop shadows and highlights at rest. */
    var outer = emptyList<Shade>()
    /** Light around the surface while it glows: always for [glowing], else while focused or pressed. */
    var glow = emptyList<Shade>()
    var glowing = false
    /** How much glow a press adds, 0 to 1. */
    var pressGlow = 0f
    /** Shading inside the face at rest (fields, glass edges). */
    var inner = emptyList<Shade>()
    /** Shading inside the face while pressed. */
    var pressInner = emptyList<Shade>()
    /** How much of the outer shadow a press takes away, 0 to 1. */
    var pressLift = 0.5f
    /** A darker band under the face: the side of a raised key. A press pushes the face down over it. */
    var lip = 0f
    var lipColor = Color.TRANSPARENT
    /** Alpha of the sheen over the top of the face, 0 for none. */
    var gloss = 0
    /** Colour laid over the face while pressed. */
    var pressOverlay = Color.TRANSPARENT
    var focusRing = Color.TRANSPARENT
    /** Gap between the drawable's bounds and the face, for knobs that sit inside a track. */
    var inset = 0f
    /** A dot in the middle of the face, for checked radio buttons; transparent for none. */
    var dot = Color.TRANSPARENT
    var intrinsicWidth = -1
    var intrinsicHeight = -1

    fun reach() = (outer + glow).maxOfOrNull { it.blur * 1.6f + max(abs(it.dx), abs(it.dy)) + it.spread } ?: 0f
}

/**
 * Draws a [DepthSpec]. Blurred light is drawn once per size into half-resolution bitmaps, so a
 * frame only copies bitmaps and fills a few shapes. Shadows and glow spill past the bounds; the
 * view's parents are told not to clip them (see [depth]).
 */
class DepthDrawable(private val spec: DepthSpec) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val face = RectF()
    private val shape = RectF()
    private var builtFor = Rect()
    private var outerBitmap: Bitmap? = null
    private var glowBitmap: Bitmap? = null
    private var innerBitmap: Bitmap? = null
    private var pressInnerBitmap: Bitmap? = null
    private var reach = 0f
    private var press = 0f
    private var focus = 0f
    private var pressTarget = 0f
    private var focusTarget = 0f
    private var animator: ValueAnimator? = null
    private var alphaLevel = 255

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        build()
        val sink = spec.lip * press
        val radius = radiusFor(shape)
        val alpha = alphaLevel / 255f

        outerBitmap?.let { draw(canvas, it, shape, reach, alpha * (1 - press * spec.pressLift)) }
        val glowAmount = if (spec.glowing) 1f else max(focus, press * spec.pressGlow)
        if (glowAmount > 0f) glowBitmap?.let { draw(canvas, it, shape, reach, alpha * glowAmount) }

        face.set(shape.left, shape.top + sink, shape.right, shape.bottom - spec.lip + sink)
        if (spec.lip > 0f) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(spec.lipColor, alpha)
            canvas.drawRoundRect(shape.left, shape.top + spec.lip, shape.right, shape.bottom, radius, radius, paint)
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, face.top, 0f, face.bottom, spec.fill, null, Shader.TileMode.CLAMP)
        paint.alpha = alphaLevel
        canvas.drawRoundRect(face, radius, radius, paint)
        paint.shader = null

        innerBitmap?.let { draw(canvas, it, face, 0f, alpha) }
        if (press > 0f) pressInnerBitmap?.let { draw(canvas, it, face, 0f, alpha * press) }
        if (spec.gloss > 0) {
            paint.shader = LinearGradient(0f, face.top, 0f, face.top + face.height() * 0.6f,
                Color.argb((spec.gloss * alpha).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(face, radius, radius, paint)
            paint.shader = null
        }
        if (press > 0f && Color.alpha(spec.pressOverlay) > 0) {
            paint.color = withAlpha(spec.pressOverlay, alpha * press)
            canvas.drawRoundRect(face, radius, radius, paint)
        }
        if (Color.alpha(spec.dot) > 0) {
            val r = min(face.width(), face.height()) * 0.24f
            paint.shader = RadialGradient(face.centerX(), face.centerY() - r * 0.4f, r * 1.4f,
                mix(spec.dot, Color.WHITE, 0.35f), spec.dot, Shader.TileMode.CLAMP)
            canvas.drawCircle(face.centerX(), face.centerY(), r, paint)
            paint.shader = null
        }
        if (spec.rimWidth > 0f) {
            val half = spec.rimWidth / 2
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = spec.rimWidth
            var top = spec.rimTop
            var bottom = spec.rimBottom
            if (glowAmount > 0f && Color.alpha(spec.glowRim) > 0) {
                top = blend(top, spec.glowRim, glowAmount)
                bottom = blend(bottom, spec.glowRim, glowAmount)
            }
            paint.shader = LinearGradient(0f, face.top, 0f, face.bottom, withAlpha(top, alpha), withAlpha(bottom, alpha), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(face.left + half, face.top + half, face.right - half, face.bottom - half, radius - half, radius - half, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
        }
        if (focus > 0f && Color.alpha(spec.focusRing) > 0) {
            val gap = spec.rimWidth.coerceAtLeast(1f) * 2.5f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = gap * 0.8f
            paint.color = withAlpha(spec.focusRing, alpha * focus)
            canvas.drawRoundRect(face.left - gap, face.top - gap, face.right + gap, face.bottom + gap, radius + gap, radius + gap, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun radiusFor(box: RectF) = min(spec.radius, min(box.width(), box.height()) / 2)

    private fun draw(canvas: Canvas, bitmap: Bitmap, box: RectF, margin: Float, alpha: Float) {
        if (alpha <= 0f) return
        bitmapPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(bitmap, null, RectF(box.left - margin, box.top - margin, box.right + margin, box.bottom + margin), bitmapPaint)
    }

    // ---- Cached light ----

    private fun build() {
        if (builtFor == bounds) return
        builtFor = Rect(bounds)
        shape.set(bounds)
        shape.inset(spec.inset, spec.inset)
        reach = ceil(spec.reach())
        outerBitmap = light(spec.outer)
        glowBitmap = light(spec.glow)
        val faceBox = RectF(0f, 0f, shape.width(), shape.height() - spec.lip)
        innerBitmap = shading(spec.inner, faceBox)
        pressInnerBitmap = shading(spec.pressInner, faceBox)
    }

    /** Shadows outside the shape; the shape itself is cut out so see-through faces stay clean. */
    private fun light(shades: List<Shade>): Bitmap? {
        if (shades.isEmpty() || shape.isEmpty) return null
        val width = ceil((shape.width() + reach * 2) * SCALE).toInt().coerceAtLeast(1)
        val height = ceil((shape.height() + reach * 2) * SCALE).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(SCALE, SCALE)
        canvas.translate(reach, reach)
        val box = RectF(0f, 0f, shape.width(), shape.height())
        val radius = radiusFor(box)
        val brush = Paint(Paint.ANTI_ALIAS_FLAG)
        shades.forEach { shade ->
            brush.color = shade.color
            brush.maskFilter = if (shade.blur > 0f) BlurMaskFilter(shade.blur, BlurMaskFilter.Blur.NORMAL) else null
            val spread = shade.spread
            canvas.drawRoundRect(box.left - spread + shade.dx, box.top - spread + shade.dy, box.right + spread + shade.dx,
                box.bottom + spread + shade.dy, radius + spread, radius + spread, brush)
        }
        brush.maskFilter = null
        brush.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        canvas.drawRoundRect(box, radius, radius, brush)
        return bitmap
    }

    /** Inner shadows: a blurred frame around a hole the shape of the face, clipped to the face. */
    private fun shading(shades: List<Shade>, box: RectF): Bitmap? {
        if (shades.isEmpty() || box.isEmpty) return null
        val bitmap = Bitmap.createBitmap(ceil(box.width() * SCALE).toInt().coerceAtLeast(1),
            ceil(box.height() * SCALE).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(SCALE, SCALE)
        val radius = radiusFor(box)
        canvas.clipPath(Path().apply { addRoundRect(box, radius, radius, Path.Direction.CW) })
        val brush = Paint(Paint.ANTI_ALIAS_FLAG)
        shades.forEach { shade ->
            val pad = shade.blur * 3 + abs(shade.dx) + abs(shade.dy)
            val hole = RectF(box).apply { offset(shade.dx, shade.dy); inset(shade.spread, shade.spread) }
            val frame = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad, Path.Direction.CW)
                addRoundRect(hole, radius, radius, Path.Direction.CW)
            }
            brush.color = shade.color
            brush.maskFilter = if (shade.blur > 0f) BlurMaskFilter(shade.blur, BlurMaskFilter.Blur.NORMAL) else null
            canvas.drawPath(frame, brush)
        }
        return bitmap
    }

    // ---- State ----

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val pressed = if (android.R.attr.state_pressed in state) 1f else 0f
        val focused = if (android.R.attr.state_focused in state) 1f else 0f
        if (pressed == pressTarget && focused == focusTarget) return false
        pressTarget = pressed
        focusTarget = focused
        val fromPress = press
        val fromFocus = focus
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            // Pressing in is quick; letting go eases back, like a soft key.
            duration = if (pressed > fromPress) 90 else 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                press = fromPress + (pressTarget - fromPress) * t
                focus = fromFocus + (focusTarget - fromFocus) * t
                invalidateSelf()
            }
            start()
        }
        return true
    }

    override fun jumpToCurrentState() {
        animator?.cancel()
        press = pressTarget
        focus = focusTarget
        invalidateSelf()
    }

    override fun getOutline(outline: Outline) {
        val box = RectF(bounds).apply { inset(spec.inset, spec.inset) }
        outline.setRoundRect(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt(), radiusFor(box))
    }

    override fun getIntrinsicWidth() = spec.intrinsicWidth
    override fun getIntrinsicHeight() = spec.intrinsicHeight
    override fun setAlpha(alpha: Int) { alphaLevel = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** Blur hides detail, so the light is drawn at half size and scaled up. */
        const val SCALE = 0.5f
    }
}

private fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt() shl 24)

/** [color] with [alpha] from 0 to 1. */
fun tone(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

/** Mixes two colours including alpha. */
private fun blend(from: Int, to: Int, amount: Float): Int {
    val a = (Color.alpha(from) * (1 - amount) + Color.alpha(to) * amount).toInt()
    return (mix(from, to, amount) and 0x00FFFFFF) or (a shl 24)
}

/**
 * The look of each finish, after the reference boards: Layered (product styleguide: stacked soft
 * shadows and an accent glow), Soft (neumorphic: light from the top left, raised and pressed),
 * Tactile (thick keys with a warm glow), Glass (clear with coloured rims and a sheen) and Carved
 * (dark neo-skeuomorphic bevels with an ember glow).
 */
object Depth {
    /** The warm light of the tactile keys. */
    const val AMBER = 0xFFFFA23A.toInt()

    /** Mint, lavender and peach rims from the glass board; surfaces take them in turn. */
    val glassTints = intArrayOf(0xFF7FD8BE.toInt(), 0xFFA18CFF.toInt(), 0xFFFFAE8A.toInt())

    fun spec(colors: Palette, radius: Float, role: Role, fill: Int, glow: Boolean, density: Float,
             border: Int? = null, tint: Int? = null): DepthSpec {
        val d = density
        val dark = colors.dark
        val primary = role == Role.PRIMARY
        val small = role == Role.KEY
        val glowColor = if (colors.finish == UiStyle.TACTILE) AMBER else tint ?: colors.accent
        val spec = DepthSpec(radius * colors.finish.corners)
        spec.glowing = glow || primary && colors.finish != UiStyle.FLAT
        spec.focusRing = tone(colors.accent, 0.85f)
        spec.pressOverlay = tone(if (dark) Color.WHITE else Color.BLACK, 0.05f)
        when (colors.finish) {
            UiStyle.FLAT -> {
                spec.fill = intArrayOf(fill, fill)
                val edge = border ?: if (primary) fill else tone(colors.secondary, 0.55f)
                spec.rimTop = edge
                spec.rimBottom = edge
                spec.rimWidth = d
                spec.glowing = false
                spec.glowRim = colors.accent
                spec.pressOverlay = tone(if (dark) Color.WHITE else Color.BLACK, 0.08f)
            }
            UiStyle.LAYERED -> {
                val ink = if (dark) Color.BLACK else 0xFF1B2A4A.toInt()
                spec.fill = if (primary) intArrayOf(mix(fill, Color.WHITE, 0.14f), mix(fill, Color.BLACK, 0.06f))
                else intArrayOf(mix(fill, Color.WHITE, if (dark) 0.04f else 0.3f), fill)
                spec.rimWidth = d
                if (primary) {
                    spec.rimTop = tone(Color.WHITE, 0.35f)
                    spec.rimBottom = tone(Color.BLACK, 0.12f)
                } else {
                    spec.rimTop = border ?: tone(if (dark) Color.WHITE else Color.BLACK, if (dark) 0.10f else 0.06f)
                    spec.rimBottom = border ?: tone(if (dark) Color.WHITE else Color.BLACK, if (dark) 0.04f else 0.10f)
                }
                val k = if (dark) 4f else 1f
                spec.outer = when (role) {
                    Role.FIELD -> listOf(Shade(0f, d, d, tone(ink, 0.04f * k)))
                    Role.KEY -> listOf(Shade(0f, d, 2 * d, tone(ink, 0.10f * k)), Shade(0f, 3 * d, 6 * d, tone(ink, 0.09f * k)))
                    else -> listOf(Shade(0f, d, 2 * d, tone(ink, 0.08f * k)), Shade(0f, 4 * d, 10 * d, tone(ink, 0.08f * k)),
                        Shade(0f, 12 * d, 24 * d, tone(ink, 0.07f * k)))
                }
                if (role == Role.FIELD) spec.inner = listOf(Shade(0f, d, 3 * d, tone(Color.BLACK, if (dark) 0.35f else 0.07f)))
                spec.glow = listOf(Shade(0f, 6 * d, 18 * d, tone(glowColor, 0.45f)), Shade(0f, 0f, 3 * d, tone(glowColor, 0.30f), d))
                spec.glowRim = tone(glowColor, 0.75f)
                spec.pressInner = listOf(Shade(0f, 2 * d, 4 * d, tone(Color.BLACK, if (dark) 0.4f else 0.10f)))
                spec.pressLift = 0.6f
            }
            UiStyle.SOFT -> {
                val highlight = if (dark) tone(Color.WHITE, 0.07f) else tone(Color.WHITE, 0.95f)
                val shade = if (dark) tone(Color.BLACK, 0.6f) else tone(mix(colors.background, 0xFF2B3550.toInt(), 0.5f), 0.42f)
                val near = if (small) 0.5f else 1f
                val wells = listOf(Shade(4 * d * near, 4 * d * near, 7 * d * near, shade), Shade(-3 * d * near, -3 * d * near, 6 * d * near, highlight))
                spec.rimWidth = d
                spec.rimTop = tone(Color.WHITE, if (dark) 0.08f else 0.8f)
                spec.rimBottom = tone(Color.WHITE, 0f)
                if (role == Role.FIELD) {
                    spec.fill = intArrayOf(mix(fill, Color.BLACK, if (dark) 0.10f else 0.035f), mix(fill, Color.BLACK, if (dark) 0.06f else 0.015f))
                    spec.inner = wells
                    spec.rimTop = tone(Color.BLACK, if (dark) 0.3f else 0.04f)
                    spec.rimBottom = tone(Color.WHITE, if (dark) 0.06f else 0.9f)
                } else {
                    spec.fill = if (primary) intArrayOf(mix(fill, Color.WHITE, 0.22f), mix(fill, Color.BLACK, 0.08f))
                    else intArrayOf(mix(fill, Color.WHITE, if (dark) 0.05f else 0.4f), mix(fill, Color.BLACK, if (dark) 0.06f else 0.02f))
                    spec.outer = listOf(Shade(-6 * d * near, -6 * d * near, 14 * d * near, highlight),
                        Shade(8 * d * near, 10 * d * near, 18 * d * near, if (primary) tone(fill, 0.45f) else shade))
                    if (primary) spec.rimTop = tone(Color.WHITE, 0.45f)
                    spec.pressInner = wells
                    spec.pressLift = 0.9f
                }
                spec.glow = listOf(Shade(0f, 8 * d, 22 * d, tone(glowColor, 0.40f)), Shade(0f, 0f, 10 * d, tone(glowColor, 0.30f)))
                spec.glowRim = tone(glowColor, 0.6f)
            }
            UiStyle.TACTILE -> {
                spec.rimWidth = 1.5f * d
                if (role == Role.FIELD) {
                    spec.fill = intArrayOf(mix(fill, Color.BLACK, if (dark) 0.14f else 0.05f), mix(fill, Color.BLACK, if (dark) 0.08f else 0.02f))
                    spec.inner = listOf(Shade(0f, 3 * d, 6 * d, tone(Color.BLACK, if (dark) 0.6f else 0.16f)))
                    spec.rimTop = tone(Color.BLACK, if (dark) 0.35f else 0.06f)
                    spec.rimBottom = tone(Color.WHITE, if (dark) 0.06f else 0.9f)
                } else {
                    spec.fill = if (primary) intArrayOf(mix(fill, Color.WHITE, 0.25f), fill)
                    else intArrayOf(mix(fill, Color.WHITE, if (dark) 0.07f else 0.7f), mix(fill, Color.BLACK, if (dark) 0.08f else 0.03f))
                    spec.lip = (if (small) 2.5f else 3.5f) * d
                    spec.lipColor = mix(fill, Color.BLACK, if (dark) 0.5f else 0.16f)
                    spec.rimTop = tone(Color.WHITE, if (dark) 0.14f else 1f)
                    spec.rimBottom = tone(Color.BLACK, if (dark) 0.25f else 0.04f)
                    val k = if (dark) 3f else 1f
                    spec.outer = listOf(Shade(0f, 12 * d, 22 * d, tone(Color.BLACK, 0.16f * k)), Shade(0f, 3 * d, 5 * d, tone(Color.BLACK, 0.12f * k)),
                        Shade(0f, -d, 2 * d, tone(Color.WHITE, if (dark) 0.05f else 0.9f)))
                    spec.pressLift = 0.55f
                    spec.pressGlow = 0.85f
                }
                spec.glow = listOf(Shade(0f, 0f, 22 * d, tone(glowColor, 0.7f)), Shade(0f, 0f, 6 * d, tone(glowColor, 0.5f), d))
                spec.glowRim = tone(glowColor, 0.9f)
            }
            UiStyle.GLASS -> {
                val rimTint = tint ?: if (primary) fill else colors.accent
                spec.fill = when {
                    primary -> intArrayOf(tone(mix(fill, Color.WHITE, 0.45f), 0.82f), tone(fill, 0.55f))
                    role == Role.FIELD -> if (dark) intArrayOf(tone(Color.WHITE, 0.06f), tone(Color.WHITE, 0.03f))
                        else intArrayOf(tone(mix(fill, Color.WHITE, 0.5f), 0.45f), tone(fill, 0.30f))
                    dark -> intArrayOf(tone(mix(fill, Color.WHITE, 0.25f), 0.55f), tone(fill, 0.35f))
                    else -> intArrayOf(tone(mix(fill, Color.WHITE, 0.6f), 0.78f), tone(fill, 0.45f))
                }
                spec.rimWidth = 1.5f * d
                spec.rimTop = tone(Color.WHITE, if (dark) 0.40f else 1f)
                spec.rimBottom = tone(rimTint, if (dark) 0.6f else 0.5f)
                spec.gloss = if (dark) 40 else 120
                spec.inner = listOf(Shade(0f, -5 * d, 10 * d, tone(rimTint, if (dark) 0.22f else 0.18f)))
                if (role != Role.FIELD) spec.outer = listOf(Shade(0f, 8 * d, 22 * d, tone(rimTint, if (dark) 0.28f else 0.26f)),
                    Shade(0f, 2 * d, 5 * d, tone(Color.BLACK, if (dark) 0.35f else 0.05f)))
                spec.glow = listOf(Shade(0f, 0f, 18 * d, tone(glowColor, 0.55f)), Shade(0f, 6 * d, 24 * d, tone(glowColor, 0.35f)))
                spec.glowRim = tone(glowColor, 0.9f)
                spec.pressGlow = 0.7f
                spec.pressLift = 0.3f
                spec.pressOverlay = tone(rimTint, 0.08f)
            }
            UiStyle.CARVED -> {
                val k = if (dark) 1f else 0.3f
                spec.rimWidth = d
                if (role == Role.FIELD) {
                    spec.fill = intArrayOf(mix(fill, Color.BLACK, if (dark) 0.30f else 0.07f), mix(fill, Color.BLACK, if (dark) 0.20f else 0.03f))
                    spec.inner = listOf(Shade(0f, 3 * d, 6 * d, tone(Color.BLACK, 0.75f * k)), Shade(0f, -d, 2 * d, tone(Color.WHITE, if (dark) 0.05f else 0.7f)))
                    spec.rimTop = tone(Color.BLACK, 0.5f * k)
                    spec.rimBottom = tone(Color.WHITE, if (dark) 0.08f else 0.8f)
                } else {
                    spec.fill = if (primary) intArrayOf(mix(fill, Color.WHITE, 0.2f), mix(fill, Color.BLACK, 0.28f))
                    else intArrayOf(mix(fill, Color.WHITE, if (dark) 0.08f else 0.4f), mix(fill, Color.BLACK, if (dark) 0.2f else 0.06f))
                    spec.rimTop = tone(Color.WHITE, if (primary) 0.35f else if (dark) 0.14f else 0.9f)
                    spec.rimBottom = tone(Color.BLACK, if (primary) 0.4f else 0.6f * k)
                    spec.outer = listOf(Shade(0f, 6 * d, 12 * d, tone(Color.BLACK, 0.7f * k)), Shade(0f, d, 1.5f * d, tone(Color.BLACK, 0.6f * k)),
                        Shade(0f, -d, d, tone(Color.WHITE, if (dark) 0.05f else 0.8f)))
                    if (primary) spec.gloss = 46
                    spec.pressInner = listOf(Shade(0f, 3 * d, 6 * d, tone(Color.BLACK, 0.7f * k)))
                    spec.pressLift = 0.85f
                }
                spec.glow = listOf(Shade(0f, 0f, 16 * d, tone(glowColor, 0.65f)), Shade(0f, 0f, 4 * d, tone(glowColor, 0.45f), d))
                spec.glowRim = tone(glowColor, 0.95f)
            }
        }
        return spec
    }
}

/**
 * Gives this view a surface in the palette's finish. [fill] defaults to the card colour; the
 * accent colour as fill makes it the primary action. [border] is the edge in the flat finish.
 */
fun View.depth(colors: Palette, radius: Float, role: Role = Role.CARD, fill: Int = if (role == Role.PRIMARY) colors.accent else colors.surface,
               glow: Boolean = false, border: Int? = null, tint: Int? = null) {
    val spec = Depth.spec(colors, radius, role, fill, glow, resources.displayMetrics.density, border, tint)
    background = DepthDrawable(spec)
    elevation = 0f
    stateListAnimator = null
    foreground = null
    if (spec.reach() > 0f) unclipAncestors()
}

/** A drawable in the palette's finish for knobs, tracks and buttons drawn by other widgets. */
fun Palette.depthDrawable(density: Float, radius: Float, role: Role, fill: Int = if (role == Role.PRIMARY) accent else surface,
                          glow: Boolean = false, width: Int = -1, height: Int = -1, inset: Float = 0f, dot: Int = Color.TRANSPARENT) =
    DepthDrawable(Depth.spec(this, radius, role, fill, glow, density).apply {
        intrinsicWidth = width
        intrinsicHeight = height
        this.inset = inset
        this.dot = dot
    })

/** Lets shadows and glow spill out of this view: its parents up to the scrolling container stop clipping. */
fun View.unclipAncestors() {
    fun run() {
        var parent = parent
        while (parent is ViewGroup && parent !is ScrollView && parent !is HorizontalScrollView && parent !is AbsListView &&
            parent.id != android.R.id.content) {
            parent.clipChildren = false
            parent.clipToPadding = false
            parent = parent.parent
        }
    }
    if (isAttachedToWindow) run() else addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            view.removeOnAttachStateChangeListener(this)
            run()
        }
        override fun onViewDetachedFromWindow(view: View) = Unit
    })
}

/** Clips this view's content to a rounded rectangle, for pictures inside a surface. */
fun View.roundClip(radius: Float) {
    outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
    clipToOutline = true
}

/**
 * The page behind a finish, after its board: the styleguide's column grid, the soft board's
 * top-left light, the tactile board's lit studio wall, the glass board's coloured haze behind
 * the panes and the carved board's dark vignette. Over a wallpaper it is a plain see-through scrim.
 */
class FinishBackdrop(private val colors: Palette, private val density: Float, private val scrim: Int? = null) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (scrim != null) {
            canvas.drawColor((colors.background and 0x00FFFFFF) or (scrim shl 24))
            return
        }
        canvas.drawColor(colors.background)
        val dark = colors.dark
        fun glow(x: Float, y: Float, r: Float, color: Int) {
            paint.shader = RadialGradient(x, y, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(b, paint)
        }
        when (colors.finish) {
            UiStyle.LAYERED -> {
                paint.shader = null
                paint.strokeWidth = density
                paint.color = tone(colors.ink, if (dark) 0.05f else 0.045f)
                val column = 56 * density
                var x = 24 * density
                while (x < w) { canvas.drawLine(x, 0f, x, h, paint); x += column }
                paint.color = tone(colors.ink, if (dark) 0.025f else 0.02f)
                val row = 24 * density
                var y = row
                while (y < h) { canvas.drawLine(0f, y, w, y, paint); y += row }
            }
            UiStyle.SOFT -> glow(0f, 0f, max(w, h) * 0.9f, tone(Color.WHITE, if (dark) 0.04f else 0.55f))
            UiStyle.TACTILE -> {
                glow(w * 0.5f, h * 0.05f, max(w, h) * 0.8f, tone(Color.WHITE, if (dark) 0.05f else 0.7f))
                glow(w * 0.5f, h * 1.1f, max(w, h) * 0.6f, tone(Color.BLACK, if (dark) 0.25f else 0.05f))
            }
            UiStyle.GLASS -> {
                val k = if (dark) 0.22f else 0.38f
                glow(w * 0.05f, h * 0.15f, w * 0.85f, tone(Depth.glassTints[0], k))
                glow(w * 0.95f, h * 0.45f, w * 0.9f, tone(Depth.glassTints[1], k))
                glow(w * 0.3f, h * 0.9f, w * 0.85f, tone(Depth.glassTints[2], k))
            }
            UiStyle.CARVED -> {
                glow(w * 0.5f, h * 0.3f, max(w, h) * 0.75f, tone(Color.WHITE, if (dark) 0.04f else 0.25f))
                paint.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.75f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT,
                    tone(Color.BLACK, if (dark) 0.45f else 0.10f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(b, paint)
            }
            UiStyle.FLAT -> Unit
        }
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = if (scrim != null) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}

/** Fills this screen with its finish's page, or a see-through [scrim] alpha over the wallpaper. */
fun View.finishBackdrop(colors: Palette, scrim: Int? = null) {
    background = FinishBackdrop(colors, resources.displayMetrics.density, scrim)
}
