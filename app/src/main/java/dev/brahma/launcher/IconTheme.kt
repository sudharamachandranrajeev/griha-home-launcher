package dev.brahma.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import org.xmlpull.v1.XmlPullParser

enum class IconShape(val title: String) {
    ORIGINAL("Original"), CIRCLE("Circle"), SQUIRCLE("Squircle"), ROUNDED("Rounded square"), TEARDROP("Teardrop")
}

enum class IconPlate(val title: String) { NONE("None"), SURFACE("Theme surface"), ACCENT("Theme accent") }

/** Everything that decides how an app icon looks, with the colours of the screen it is drawn on. */
class IconKit(val style: IconStyle, val shape: IconShape, val plate: IconPlate, val pack: String?, val colors: Palette) {
    /** True when the icon needs redrawing; otherwise the app's own icon is shown as it is. */
    val themed get() = shape != IconShape.ORIGINAL || plate != IconPlate.NONE || pack != null
    val key get() = listOf(style, shape, plate, pack ?: "", colors.surface, colors.accent, colors.ink).joinToString("|")
}

/** Draws app icons in a chosen shape, on a plate, from an icon pack, in the launcher's colours. */
object IconTheme {
    private const val SIZE = 144
    private val cache = LruCache<String, Bitmap>(180)

    fun clear() = cache.evictAll()

    fun drawable(context: Context, app: AppEntry, kit: IconKit): Drawable {
        val key = "${app.id}|${kit.key}"
        val bitmap = cache.get(key) ?: render(context, app, kit).also { cache.put(key, it) }
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun render(context: Context, app: AppEntry, kit: IconKit): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val shape = shape(kit.shape, SIZE.toFloat())
        val packIcon = kit.pack?.let { IconPacks.drawableFor(context, it, app) }
        val icon = packIcon ?: appIcon(context, app)
        canvas.save()
        canvas.clipPath(shape)
        val plate = when (kit.plate) {
            IconPlate.NONE -> null
            IconPlate.SURFACE -> kit.colors.surface
            IconPlate.ACCENT -> kit.colors.accent
        }
        if (plate != null) canvas.drawColor(plate)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (kit.style == IconStyle.GREY || kit.style == IconStyle.MONO) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val mono = if (kit.style == IconStyle.MONO && packIcon == null && Build.VERSION.SDK_INT >= 33) (icon as? AdaptiveIconDrawable)?.monochrome else null
        // A plate leaves a margin around the icon so it sits on the plate; without one it fills the shape.
        val inset = if (plate != null) SIZE * 0.2f else 0f
        if (mono != null) {
            val tint = if (kit.plate == IconPlate.ACCENT) kit.colors.onAccent else kit.colors.ink
            drawLayer(canvas, mono.mutate(), inset, true, PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN))
        } else if (icon is AdaptiveIconDrawable) {
            // The two layers are 108dp with only the middle 72dp meant to show, so draw them 1.5 times larger.
            icon.background?.let { drawLayer(canvas, it.mutate(), inset, true, paint.colorFilter) }
            icon.foreground?.let { drawLayer(canvas, it.mutate(), inset, true, paint.colorFilter) }
        } else {
            drawLayer(canvas, icon.mutate(), inset, false, paint.colorFilter)
        }
        canvas.restore()
        return out
    }

    private fun drawLayer(canvas: Canvas, layer: Drawable, inset: Float, adaptive: Boolean, filter: android.graphics.ColorFilter?) {
        val grow = if (adaptive) SIZE * 0.25f else 0f
        layer.setBounds((inset - grow).toInt(), (inset - grow).toInt(), (SIZE - inset + grow).toInt(), (SIZE - inset + grow).toInt())
        layer.colorFilter = filter
        layer.draw(canvas)
        layer.colorFilter = null
    }

    fun shape(shape: IconShape, size: Float) = Path().apply {
        val box = RectF(0f, 0f, size, size)
        when (shape) {
            IconShape.ORIGINAL -> addRect(box, Path.Direction.CW)
            IconShape.CIRCLE -> addCircle(size / 2, size / 2, size / 2, Path.Direction.CW)
            IconShape.SQUIRCLE -> addRoundRect(box, size * 0.34f, size * 0.34f, Path.Direction.CW)
            IconShape.ROUNDED -> addRoundRect(box, size * 0.18f, size * 0.18f, Path.Direction.CW)
            IconShape.TEARDROP -> {
                val big = size / 2
                val small = size * 0.1f
                addRoundRect(box, floatArrayOf(big, big, big, big, big, big, small, small), Path.Direction.CW)
            }
        }
    }
}

/** Reads icon packs in the common launcher format (an appfilter.xml listing which drawable belongs to each app). */
object IconPacks {
    class Pack(val packageName: String, val label: String)

    // Both actions are the ones icon packs advertise for ADW- and Nova-compatible launchers.
    private val actions = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")
    private val maps = HashMap<String, Map<String, String>>()

    fun installed(context: Context): List<Pack> {
        val packages = context.packageManager
        val found = LinkedHashMap<String, Pack>()
        for (action in actions) {
            @Suppress("DEPRECATION")
            val resolved = if (Build.VERSION.SDK_INT >= 33) packages.queryIntentActivities(Intent(action), PackageManager.ResolveInfoFlags.of(0))
                else packages.queryIntentActivities(Intent(action), 0)
            for (info in resolved) {
                val name = info.activityInfo.packageName
                if (name !in found) found[name] = Pack(name, info.loadLabel(packages).toString())
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun clear() = maps.clear()

    // Icon packs are found by drawable name: that is the pack format, there is no other way to read one.
    @SuppressLint("DiscouragedApi", "UseCompatLoadingForDrawables")
    /** The pack's drawable for [app], matched by the full component first and then by package alone. */
    fun drawableFor(context: Context, pack: String, app: AppEntry): Drawable? {
        val map = maps.getOrPut(pack) { load(context, pack) }
        val name = map[app.id] ?: map[app.packageName] ?: return null
        return try {
            val resources = context.packageManager.getResourcesForApplication(pack)
            val id = resources.getIdentifier(name, "drawable", pack)
            if (id == 0) null else resources.getDrawable(id, null)
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun load(context: Context, pack: String): Map<String, String> = try {
        val resources = context.packageManager.getResourcesForApplication(pack)
        val id = resources.getIdentifier("appfilter", "xml", pack)
        if (id != 0) parse(resources.getXml(id)) else {
            val parser = android.util.Xml.newPullParser()
            context.createPackageContext(pack, 0).assets.open("appfilter.xml").use {
                parser.setInput(it, null)
                parse(parser)
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Component to drawable name for every `<item>`; a package-only entry is stored under the package name. */
    fun parse(parser: XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (component != null && !drawable.isNullOrBlank()) {
                    val key = componentKey(component)
                    if (key != null) {
                        map.putIfAbsent(key, drawable)
                        map.putIfAbsent(key.substringBefore('/'), drawable)
                    }
                }
            }
            event = parser.next()
        }
        return map
    }

    /** "ComponentInfo{pkg/pkg.Main}" or "ComponentInfo{pkg/.Main}" to "pkg/pkg.Main"; null for anything else. */
    fun componentKey(raw: String): String? {
        val inner = raw.trim().removePrefix("ComponentInfo{").removeSuffix("}")
        val slash = inner.indexOf('/')
        if (slash <= 0 || slash == inner.length - 1) return null
        val pkg = inner.substring(0, slash)
        val cls = inner.substring(slash + 1)
        return pkg + "/" + if (cls.startsWith(".")) pkg + cls else cls
    }
}

/** Shows the app's icon in the style [kit] describes. */
fun ImageView.showIcon(app: AppEntry, kit: IconKit) {
    scaleType = ImageView.ScaleType.FIT_CENTER
    scaleX = 1f
    scaleY = 1f
    colorFilter = null
    if (kit.themed || kit.style == IconStyle.MONO) {
        setImageDrawable(IconTheme.drawable(context, app, kit))
    } else {
        setImageDrawable(appIcon(context, app))
        if (kit.style == IconStyle.GREY) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
}
