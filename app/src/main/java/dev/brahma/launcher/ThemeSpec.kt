package dev.brahma.launcher

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * Theme engine 1: everything Griha can draw from a theme package. The feature names match
 * engine/features.json in the griha-themes repository; a theme that requires anything else is
 * refused rather than drawn wrong.
 */
object ThemeEngine {
    const val VERSION = 1
    val FEATURES = setOf(
        "palette.v1",
        "depth.shadows", "depth.inner", "depth.rim", "depth.lip", "depth.gloss", "depth.glow", "depth.outline",
        "backdrop.plain", "backdrop.light", "backdrop.grid", "backdrop.haze", "backdrop.vignette",
        "backdrop.blob", "backdrop.chrome", "decor.peel",
        "wall.picture", "wall.motion",
        "touch.bloom", "touch.ring", "touch.glow", "touch.ripple", "touch.embers",
        "headings.style", "font.system"
    )

    /** The theme in use, loaded once when settings change; null draws the built-in finish. */
    @Volatile var active: ThemeSpec? = null
}

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()

/** A theme read from its theme.json. Colours resolve against the palette in use, so "accent@0.4" follows light and dark. */
class ThemeSpec private constructor(private val json: JSONObject, val dir: File?) {
    val id: String = json.getString("id")
    val name: String = json.optString("name", id)
    val version: Int = json.optInt("version", 1)
    val engine: Int = json.optInt("engine", 1)
    val description: String = json.optString("description")
    val requires: Set<String> = strings(json.optJSONArray("requires"))
    val prefers: Set<String> = strings(json.optJSONArray("prefers"))
    private val depth: JSONObject = json.optJSONObject("depth") ?: JSONObject()
    val corners: Float = depth.optDouble("corners", 1.0).toFloat()
    private val headings: JSONObject = json.optJSONObject("headings") ?: JSONObject()
    val headingCaps: Boolean = headings.optBoolean("caps", true)
    val headingSpacing: Float = headings.optDouble("spacing", 0.08).toFloat()
    val backdrop: JSONObject = json.optJSONObject("backdrop") ?: JSONObject().put("kind", "plain")
    val peel: JSONObject? = json.optJSONObject("decor")?.optJSONObject("peel")
    /** The tap light: bloom, ring, glow, ripple, embers or none. */
    val pulse: String = json.optJSONObject("touch")?.optString("pulse", "ring") ?: "ring"

    /** Features this Griha cannot draw; empty when the theme can be used. */
    fun missing(): Set<String> = (requires - ThemeEngine.FEATURES) + (if (engine > ThemeEngine.VERSION) setOf("engine $engine") else emptySet())

    /** The theme's picture for home or all apps, if it ships one. */
    fun wallpaper(drawer: Boolean): File? {
        val folder = dir ?: return null
        val items = json.optJSONArray("wallpapers") ?: return null
        val all = (0 until items.length()).map { items.getJSONObject(it) }
        val pick = all.firstOrNull { it.optString("for") == if (drawer) "drawer" else "home" } ?: all.firstOrNull { it.optString("for") == "any" }
        return pick?.let { File(folder, it.getString("file")) }?.takeIf { it.isFile }
    }

    fun preview(): File? = dir?.let { File(it, json.optString("preview", "preview.png")) }?.takeIf { it.isFile }

    /** The palette for the phone's light or dark mode; [Palette.dark] follows the page colour, so light text sits on dark pages. */
    fun palette(darkMode: Boolean, base: UiStyle): Palette {
        val p = json.getJSONObject("palette").let { it.optJSONObject(if (darkMode) "dark" else "light") ?: it.getJSONObject("light") }
        fun raw(key: String, fallback: Int) = p.optString(key).takeIf { it.startsWith("#") }?.let { parseHex(it) } ?: fallback
        val background = raw("background", 0xFFF2F2F2.toInt())
        val surface = raw("surface", WHITE)
        val ink = raw("ink", 0xFF111111.toInt())
        return Palette(background, surface, raw("accent", 0xFF1E7BF2.toInt()), raw("secondary", 0xFF888888.toInt()), ink,
            raw("muted", 0xFF666666.toInt()), luminance(background) < 0.18f, base, this)
    }

    /** A colour token: #RRGGBB, #AARRGGBB, or a palette name, each with an optional @opacity. */
    fun colour(token: String, palette: Palette): Int {
        val (name, alpha) = token.split('@').let { it[0].trim() to it.getOrNull(1)?.toFloatOrNull() }
        val base = when (name) {
            "background" -> palette.background
            "surface" -> palette.surface
            "accent" -> palette.accent
            "secondary" -> palette.secondary
            "ink", "onSurface" -> palette.ink
            "muted" -> palette.muted
            "white" -> WHITE
            "black" -> BLACK
            else -> if (name.startsWith("#")) parseHex(name) else palette.accent
        }
        return if (alpha == null) base else tone(base, alpha)
    }

    // ---- Depth ----

    /** A surface for [role], from the theme's numbers. A caller's own fill (an ink block, a coloured pill) is kept. */
    fun depthSpec(c: Palette, radius: Float, role: Role, fill: Int, glow: Boolean, d: Float, border: Int?): DepthSpec {
        val key = when (role) { Role.CARD -> "card"; Role.KEY -> "key"; Role.FIELD -> "field"; Role.PRIMARY -> "primary" }
        val r = depth.optJSONObject(key) ?: depth.optJSONObject("card") ?: JSONObject()
        val spec = DepthSpec(radius * corners)
        val ownFill = fill != (if (role == Role.PRIMARY) c.accent else c.surface)
        val fills = r.optJSONArray("fill")?.let { array -> (0 until array.length()).map { colour(array.getString(it), c) } }
        // A theme's fills are written for one mode; where text in this mode would be hard to read on them,
        // the surface is built from this palette's own colours instead.
        val readable = fills.isNullOrEmpty() || role == Role.PRIMARY ||
            contrast(c.ink, over(fills[fills.size / 2], c.background)) >= MIN_CONTRAST
        spec.fill = when {
            ownFill || fills.isNullOrEmpty() || !readable -> intArrayOf(mix(fill, WHITE, if (c.dark) 0.06f else 0.2f), fill)
            fills.size == 1 -> intArrayOf(fills[0], fills[0])
            else -> fills.toIntArray()
        }
        r.optJSONObject("rim")?.let { rim ->
            spec.rimWidth = rim.optDouble("width", 1.0).toFloat() * d
            spec.rimTop = night(colour(rim.optString("top", "white@0.5"), c), c)
            spec.rimBottom = night(colour(rim.optString("bottom", "black@0.1"), c), c)
        }
        depth.optJSONObject("outline")?.let { outline ->
            val roles = strings(outline.optJSONArray("roles"))
            if (roles.isEmpty() || key in roles) {
                spec.rimWidth = outline.optDouble("width", 2.0).toFloat() * d
                spec.rimTop = colour(outline.optString("colour", "ink"), c)
                spec.rimBottom = spec.rimTop
            }
        }
        if (spec.rimWidth == 0f && border != null) {
            spec.rimWidth = d
            spec.rimTop = border
            spec.rimBottom = border
        }
        spec.outer = shades(r.optJSONArray("shadows"), c, d)
        spec.inner = shades(r.optJSONArray("inner"), c, d)
        spec.pressInner = shades(r.optJSONArray("pressInner"), c, d).ifEmpty { listOf(Shade(0f, 2 * d, 4 * d, tone(BLACK, 0.14f))) }
        val glows = shades(r.optJSONArray("glow"), c, d)
        spec.glow = glows.ifEmpty { listOf(Shade(0f, 0f, 18 * d, tone(c.accent, 0.5f)), Shade(0f, 0f, 4 * d, tone(c.accent, 0.35f), d)) }
        spec.glowing = glow || (role == Role.PRIMARY && glows.isNotEmpty())
        spec.pressGlow = if (glows.isNotEmpty()) 0.5f else 0f
        spec.glowRim = tone(c.accent, 0.8f)
        spec.lip = r.optDouble("lip", 0.0).toFloat() * d
        spec.lipColor = mix(spec.fill.last(), BLACK, if (c.dark) 0.45f else 0.2f)
        spec.gloss = (r.optDouble("gloss", 0.0) * 255).toInt().coerceIn(0, 255)
        spec.focusRing = tone(c.accent, 0.85f)
        spec.pressOverlay = tone(c.ink, 0.06f)
        spec.pressLift = 0.5f
        return spec
    }

    /**
     * Light from a lamp looks dimmer at night: very light highlights, written for a light page, are
     * turned down on a dark one so cards don't wear a bright halo. Dark shadows and colours stay.
     */
    private fun night(colour: Int, c: Palette): Int {
        if (!c.dark || luminance(colour or (0xFF shl 24)) < 0.6f) return colour
        val alpha = (colour ushr 24) and 0xFF
        return (colour and 0x00FFFFFF) or ((alpha * 0.18f).toInt() shl 24)
    }

    private fun shades(array: JSONArray?, c: Palette, d: Float): List<Shade> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val s = array.optJSONArray(index) ?: return@mapNotNull null
            Shade(s.optDouble(0).toFloat() * d, s.optDouble(1).toFloat() * d, s.optDouble(2).toFloat() * d,
                night(colour(s.optString(3, "black@0.2"), c), c), s.optDouble(4, 0.0).toFloat() * d)
        }
    }

    companion object {
        /** Reads a theme.json; throws [org.json.JSONException] when it is not one. */
        fun parse(text: String, dir: File? = null): ThemeSpec {
            val json = JSONObject(text)
            require(Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(json.getString("id"))) { "Bad theme id" }
            json.getJSONObject("palette").getJSONObject("light")
            return ThemeSpec(json, dir)
        }

        fun parseHex(text: String): Int {
            val hex = text.removePrefix("#")
            val value = hex.toLong(16)
            return when (hex.length) {
                6 -> (0xFF000000 or value).toInt()
                8 -> value.toInt()
                else -> throw NumberFormatException("Bad colour $text")
            }
        }

        /** Text needs at least this contrast on a surface (WCAG's figure for large text and controls). */
        const val MIN_CONTRAST = 3.0

        /** WCAG relative luminance: channels linearised from sRGB. */
        fun luminance(colour: Int): Float {
            fun channel(shift: Int): Float {
                val v = ((colour shr shift) and 0xFF) / 255.0
                return (if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)).toFloat()
            }
            return 0.2126f * channel(16) + 0.7152f * channel(8) + 0.0722f * channel(0)
        }

        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a) + 0.05
            val lb = luminance(b) + 0.05
            return maxOf(la, lb) / minOf(la, lb)
        }

        /** [top] laid over an opaque [under], as it shows on screen. */
        fun over(top: Int, under: Int): Int {
            val a = ((top ushr 24) and 0xFF) / 255f
            return (mix(under, top, a) and 0x00FFFFFF) or (0xFF shl 24)
        }

        private fun strings(array: JSONArray?) = if (array == null) emptySet() else (0 until array.length()).map { array.getString(it) }.toSet()
    }
}

/** Draws a theme's page: plain, light, grid, haze, vignette, a flowing blob, chrome discs or a peeled panel. */
object ThemeBackdrop {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, b: Rect, c: Palette, theme: ThemeSpec, density: Float) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.drawColor(c.background)
        val spec = theme.backdrop
        fun glow(x: Float, y: Float, r: Float, colour: Int) {
            if (r <= 0f) return
            paint.color = BLACK; paint.shader = RadialGradient(x, y, r, colour, colour and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(b, paint)
            paint.shader = null
        }
        fun corner(name: String) = when (name) {
            "top-left" -> 0f to 0f
            "top" -> w / 2 to 0f
            "bottom-left" -> 0f to h
            "bottom-right" -> w to h
            "bottom" -> w / 2 to h
            else -> w to 0f
        }
        when (spec.optString("kind", "plain")) {
            "light" -> {
                val (x, y) = corner(spec.optString("from", "top-left"))
                glow(x, y, max(w, h) * 0.9f, tone(WHITE, if (c.dark) 0.06f else 0.6f))
            }
            "grid" -> {
                paint.strokeWidth = density
                paint.color = tone(c.ink, 0.045f)
                var x = 24 * density
                while (x < w) { canvas.drawLine(x, 0f, x, h, paint); x += 56 * density }
                paint.color = tone(c.ink, 0.02f)
                var y = 24 * density
                while (y < h) { canvas.drawLine(0f, y, w, y, paint); y += 24 * density }
            }
            "haze" -> {
                val tints = spec.optJSONArray("tints")?.let { a -> (0 until a.length()).map { theme.colour(a.getString(it), c) } }
                    ?: listOf(c.accent, c.secondary, mix(c.accent, WHITE, 0.4f))
                val spots = listOf(floatArrayOf(0.05f, 0.15f, 0.85f), floatArrayOf(0.95f, 0.45f, 0.9f), floatArrayOf(0.3f, 0.92f, 0.85f))
                spots.forEachIndexed { i, s -> glow(w * s[0], h * s[1], w * s[2], tone(tints[i % tints.size], if (c.dark) 0.35f else 0.3f)) }
            }
            "vignette" -> {
                glow(w / 2, h * 0.3f, max(w, h) * 0.75f, tone(WHITE, if (c.dark) 0.04f else 0.25f))
                paint.color = BLACK; paint.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.75f, intArrayOf(0, 0, tone(BLACK, if (c.dark) 0.45f else 0.1f)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(b, paint)
                paint.shader = null
            }
            "blob" -> blob(canvas, w, h, theme.colour(spec.optString("colour", "secondary"), c), spec.optString("from", "top-right"), spec.optInt("drops", 3), c)
            "chrome" -> {
                glow(w / 2, 0f, w * 0.9f, tone(WHITE, if (c.dark) 0.06f else 0.7f))
                listOf(floatArrayOf(-0.1f, 0.42f, 0.34f), floatArrayOf(1.08f, 0.66f, 0.42f), floatArrayOf(-0.05f, 1.02f, 0.5f), floatArrayOf(1.0f, 1.02f, 0.38f))
                    .forEach { (x, y, r) -> chrome(canvas, w * x, h * y, w * r, c) }
            }
            "peel" -> peel(canvas, w, h, theme, c)
        }
    }

    // Polished metal seen out of focus: soft concentric bands of light and shade.
    private fun chrome(canvas: Canvas, x: Float, y: Float, r: Float, c: Palette) {
        val light = tone(if (c.dark) 0xFF4A5468.toInt() else 0xFFF4F7FA.toInt(), 0.55f)
        val shade = tone(if (c.dark) 0xFF1C2230.toInt() else 0xFFB4BDC9.toInt(), 0.55f)
        val stops = 8
        val colours = IntArray(stops + 1) { if (it == stops) 0 else if (it % 2 == 0) light else shade }
        paint.color = BLACK; paint.shader = RadialGradient(x, y, r, colours, FloatArray(stops + 1) { it / stops.toFloat() }, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, paint)
        paint.shader = null
    }

    // A liquid edge flowing in from one side, with a few drops; drawn for 360×780 and stretched to fit.
    private fun blob(canvas: Canvas, w: Float, h: Float, colour: Int, from: String, drops: Int, c: Palette) {
        val path = Path().apply {
            moveTo(0f, 0f); lineTo(360f, 0f); lineTo(360f, 330f)
            cubicTo(352f, 372f, 326f, 392f, 302f, 368f); cubicTo(278f, 344f, 292f, 302f, 262f, 278f)
            cubicTo(230f, 252f, 190f, 286f, 146f, 258f); cubicTo(108f, 234f, 116f, 196f, 80f, 178f)
            cubicTo(50f, 163f, 18f, 170f, 0f, 186f); close()
        }
        canvas.save()
        // Mirror so the shape flows from the named corner, then stretch the 360×780 drawing to the page.
        if (from.endsWith("left")) { canvas.translate(w, 0f); canvas.scale(-1f, 1f) }
        if (from.startsWith("bottom")) { canvas.translate(0f, h); canvas.scale(1f, -1f) }
        canvas.scale(w / 360f, h / 780f)
        canvas.save()
        canvas.translate(0f, 8f)
        paint.color = tone(BLACK, if (c.dark) 0.45f else 0.12f)
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.color = BLACK; paint.shader = LinearGradient(0f, 0f, 360f, 400f, mix(colour, WHITE, 0.15f), mix(colour, BLACK, 0.12f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.color = if (c.dark) 0xFF111111.toInt() else mix(c.ink, c.background, 0.1f)
        listOf(floatArrayOf(300f, 30f, 4f), floatArrayOf(326f, 52f, 6f), floatArrayOf(282f, 62f, 3f), floatArrayOf(240f, 22f, 3f))
            .take(drops.coerceIn(0, 4)).forEach { (x, y, r) -> canvas.drawCircle(x, y, r, paint) }
        canvas.restore()
    }

    /**
     * A panel over the page whose lower corner folds back. The fold runs from 62% down the panel's
     * edge to 26% across its bottom; the flap is that corner reflected over the fold line.
     */
    private fun peel(canvas: Canvas, w: Float, h: Float, theme: ThemeSpec, c: Palette) {
        val peel = theme.peel ?: JSONObject()
        val left = peel.optString("corner", "top-right").endsWith("left")
        val panelColour = theme.colour(peel.optString("panel", "#161616"), c)
        val paper = theme.colour(peel.optString("paper", "#F7F4EF"), c)
        val bottom = h * peel.optDouble("band", 0.385).toFloat()
        val ax = if (left) 0f else w
        val ay = bottom * 0.62f
        val bx = if (left) w * 0.26f else w * 0.74f
        val px = ax
        // Reflect the cut corner (px, bottom) over the fold line A-B.
        val dx = bx - ax
        val dy = bottom - ay
        val t = ((px - ax) * dx + (bottom - ay) * dy) / (dx * dx + dy * dy)
        val fx = ax + t * dx
        val fy = ay + t * dy
        val qx = 2 * fx - px
        val qy = 2 * fy - bottom
        val panel = Path().apply {
            moveTo(0f, 0f); lineTo(w, 0f)
            if (left) { lineTo(w, bottom); lineTo(bx, bottom); lineTo(0f, ay) } else { lineTo(w, ay); lineTo(bx, bottom); lineTo(0f, bottom) }
            close()
        }
        canvas.save()
        canvas.translate(0f, 6 * w / 360f)
        paint.color = tone(BLACK, 0.3f)
        canvas.drawPath(panel, paint)
        canvas.restore()
        paint.color = BLACK; paint.shader = LinearGradient(0f, 0f, 0f, bottom, mix(panelColour, WHITE, 0.06f), mix(panelColour, BLACK, 0.08f), Shader.TileMode.CLAMP)
        canvas.drawPath(panel, paint)
        val flap = Path().apply { moveTo(ax, ay); lineTo(bx, bottom); lineTo(qx, qy); close() }
        canvas.save()
        canvas.translate(if (left) 4f else -4f, 6f)
        paint.shader = null
        paint.color = tone(BLACK, 0.35f)
        canvas.drawPath(flap, paint)
        canvas.restore()
        paint.color = BLACK; paint.shader = LinearGradient(qx, qy, (ax + bx) / 2, (ay + bottom) / 2, mix(paper, WHITE, 0.4f), mix(paper, BLACK, 0.35f), Shader.TileMode.CLAMP)
        canvas.drawPath(flap, paint)
        paint.shader = null
    }
}
