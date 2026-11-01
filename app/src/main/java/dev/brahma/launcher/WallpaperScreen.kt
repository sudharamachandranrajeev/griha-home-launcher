package dev.brahma.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

class WallpaperActions(
    /** Draws a small picture of [WallpaperSource]; the callback gets it on the main thread, or null if it failed. */
    val thumbnail: (WallpaperSource, Int, Int, (Bitmap?) -> Unit) -> Unit,
    val onPick: (WallpaperSource) -> Unit,
    val onImport: () -> Unit,
    val onSystemPicker: () -> Unit,
    val onDone: () -> Unit
)

/** Wallpapers drawn for every theme, the user's imported ones, and a way into Android's own picker. */
@SuppressLint("ViewConstructor")
class WallpaperScreen(
    context: Context,
    private val colors: Palette,
    private val current: HomeLayout,
    private val imported: List<File>,
    private val screenAspect: Float,
    private val actions: WallpaperActions
) : ScrollView(context) {
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val thumbWidth = context.dp(84)
    private val thumbHeight = (thumbWidth * screenAspect).toInt()

    init {
        isFillViewport = true
        setBackgroundColor(colors.background)
        body.setPadding(context.dp(24), context.dp(20), context.dp(24), context.dp(32))
        addView(body)
        body.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(context.label("Wallpapers", 28f, colors.ink, Typeface.BOLD), LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.action(context.getString(R.string.done), colors.accent, actions.onDone))
        })
        body.addView(context.label("Tap a wallpaper to use it. Each theme's set uses only that theme's colours and shapes.", 14f, colors.muted))

        section("Your theme · ${current.title}")
        body.addView(strip(WallpaperKind.entries.map { WallpaperSource.Theme(current, it) to it.title }))

        section("Your wallpapers")
        body.addView(context.label(if (imported.isEmpty()) "Import pictures, or a wallpaper pack as a ZIP of pictures. " +
            "They are copied into Griha; up to ${WallpaperFiles.MAX_COUNT} are kept."
            else "${imported.size} imported. Tap one to use or remove it.", 14f, colors.muted))
        if (imported.isNotEmpty()) body.addView(strip(imported.map { WallpaperSource.Imported(it) to "" }))
        body.addView(LinearLayout(context).apply {
            addView(context.action("Import pictures or ZIP", colors.accent, actions.onImport))
            addView(context.action("Android's picker", colors.accent, actions.onSystemPicker))
        })

        section("All themes")
        HomeLayout.entries.filter { it != current }.forEach { layout ->
            body.addView(context.label(layout.title, 15f, colors.ink, Typeface.BOLD).apply { setPadding(0, context.dp(8), 0, context.dp(4)) })
            body.addView(strip(WallpaperKind.entries.map { WallpaperSource.Theme(layout, it) to it.title }))
        }
    }

    private fun section(title: String) {
        body.addView(View(context), LinearLayout.LayoutParams(1, context.dp(16)))
        body.addView(context.label(title.uppercase(), 12f, colors.accent, Typeface.BOLD))
    }

    // One sideways-scrolling row of pictures, each drawn when the row is built.
    private fun strip(items: List<Pair<WallpaperSource, String>>) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            items.forEach { (source, title) -> addView(tile(source, title)) }
        })
    }

    private fun tile(source: WallpaperSource, title: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dp(6), context.dp(10), context.dp(6))
        val picture = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(colors.surface)
        }
        addView(FrameLayout(context).apply {
            addView(picture, FrameLayout.LayoutParams(-1, -1))
            soft(colors, context.dp(10).toFloat())
            clipToOutline = true
        }, LinearLayout.LayoutParams(thumbWidth, thumbHeight))
        if (title.isNotEmpty()) addView(context.label(title, 12f, colors.muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(thumbWidth, -2))
        contentDescription = when (source) {
            is WallpaperSource.Theme -> "${source.layout.title} ${source.kind.title} wallpaper"
            is WallpaperSource.Imported -> "Imported wallpaper"
        }
        isClickable = true
        setOnClickListener { actions.onPick(source) }
        actions.thumbnail(source, thumbWidth, thumbHeight) { bitmap -> if (bitmap != null) picture.setImageBitmap(bitmap) }
    }
}
