package dev.brahma.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ImageView
import android.widget.TextView

/** Family name that selects the bundled Dancing Script font (OFL) instead of a system family. */
const val SCRIPT = "script"

// Small view helpers shared by the home and settings screens, which are built in code.
fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

fun Context.label(value: CharSequence, size: Float, color: Int, style: Int = Typeface.NORMAL, family: String = "sans-serif") =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = if (family == SCRIPT) resources.getFont(R.font.dancing_script) else Typeface.create(family, style)
    }

fun Context.action(value: CharSequence, color: Int, callback: () -> Unit) = label(value, 14f, color, Typeface.BOLD).apply {
    minHeight = dp(48)
    minWidth = dp(48)
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(8), 0, dp(8), 0)
    isFocusable = true
    setOnClickListener { callback() }
}

fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke != null) setStroke(strokeWidth, stroke)
}

enum class IconStyle(val title: String) { ORIGINAL("Original colours"), GREY("Grey"), MONO("Monochrome") }

/**
 * How surfaces are drawn across the launcher. Names are saved in settings, so they must not change;
 * each finish follows one of the reference boards.
 */
enum class UiStyle(
    val title: String,
    /** Corner size against the layout's own: the styleguide is crisp, the soft boards are pills. */
    val corners: Float,
    /** Letter spacing of section headings: the soft and carved boards use spaced capitals. */
    val headingSpacing: Float,
    val headingCaps: Boolean
) {
    SOFT("Soft · ivory and cobalt, raised and pressed", 1.1f, 0.18f, true),
    LAYERED("Layered · product styleguide, stacked shadows", 0.5f, 0f, false),
    TACTILE("Tactile · white keys with a warm amber light", 1.25f, 0.04f, false),
    GLASS("Glass · frosted panes with coloured rims", 1.2f, 0.02f, false),
    CARVED("Carved · charcoal and copper, ember glow", 0.65f, 0.12f, true),
    FLAT("Flat · the layout's classic look", 1f, 0.08f, true)
}

/** Mixes [amount] of [other] into [color]; alpha comes from [color]. */
fun mix(color: Int, other: Int, amount: Float): Int {
    fun channel(shift: Int) = (((color shr shift) and 0xFF) * (1 - amount) + ((other shr shift) and 0xFF) * amount).toInt() shl shift
    return (color and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0)
}

/** A raised surface in the palette's finish; the accent colour as [fill] makes it the primary action. */
fun View.soft(colors: Palette, radius: Float, fill: Int = colors.surface, glow: Boolean = false) =
    depth(colors, radius, if (fill == colors.accent) Role.PRIMARY else Role.CARD, fill, glow)

/**
 * Accent switches and radio buttons. Outside the flat finish they get drawn parts: a knob that
 * stands up from a pressed-in track, which fills and glows with the accent when on.
 */
fun CompoundButton.accent(colors: Palette) {
    // The label follows the palette, not the window theme, so it stays readable on any theme's page.
    setTextColor(colors.ink)
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    if (colors.finish == UiStyle.FLAT) {
        buttonTintList = ColorStateList(states, intArrayOf(colors.accent, colors.muted))
        if (this is android.widget.Switch) {
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, mix(colors.surface, Color.WHITE, 0.5f)))
            trackTintList = ColorStateList(states, intArrayOf(colors.accent, colors.muted))
        }
        return
    }
    val density = resources.displayMetrics.density
    val size = (28 * density).toInt()
    val knob = if (colors.dark) mix(colors.surface, Color.WHITE, 0.55f) else Color.WHITE
    fun choice(on: android.graphics.drawable.Drawable, off: android.graphics.drawable.Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), on)
        addState(intArrayOf(), off)
    }
    if (this is android.widget.Switch) {
        thumbDrawable = colors.depthDrawable(density, size / 2f, Role.KEY, knob, width = size, height = size, inset = 3 * density)
        trackDrawable = choice(colors.depthDrawable(density, size / 2f, Role.PRIMARY, width = size * 2, height = size),
            colors.depthDrawable(density, size / 2f, Role.FIELD, colors.surface, width = size * 2, height = size))
        thumbTintList = null
        trackTintList = null
        switchMinWidth = size * 2
    } else {
        val box = (24 * density).toInt()
        buttonDrawable = choice(colors.depthDrawable(density, box / 2f, Role.FIELD, colors.surface, width = box, height = box, dot = colors.accent),
            colors.depthDrawable(density, box / 2f, Role.FIELD, colors.surface, width = box, height = box))
        buttonTintList = null
        compoundDrawablePadding = (8 * density).toInt()
    }
    unclipAncestors()
}

/** Colours offered for effects and lock text, with names for screen readers. */
val swatches = listOf(
    0xFFFFFFFF.toInt() to "White", 0xFF111111.toInt() to "Black", 0xFF1E7BF2.toInt() to "Blue", 0xFFFF3B8D.toInt() to "Pink",
    0xFF8B5CF6.toInt() to "Purple", 0xFFFF8A00.toInt() to "Orange", 0xFFFFD60A.toInt() to "Yellow", 0xFF22C55E.toInt() to "Green",
    0xFF06B6D4.toInt() to "Cyan", 0xFFEF4444.toInt() to "Red"
)

/** A wrapping row of colour circles; the chosen one gets an accent ring. */
fun Context.swatchRow(colors: Palette, selected: Int, choices: List<Pair<Int, String>>, onPick: (Int) -> Unit): LinearLayout {
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var row: LinearLayout? = null
    choices.forEachIndexed { index, (color, name) ->
        if (index % 6 == 0) row = LinearLayout(this).also { rows.addView(it) }
        row?.addView(FrameLayout(this).apply {
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            contentDescription = if (color == selected) "$name, chosen" else name
            addView(View(this@swatchRow).apply {
                background = rounded(if (color == 0) colors.accent else color, dp(15).toFloat(),
                    if (color == selected) colors.accent else colors.muted, dp(if (color == selected) 3 else 1))
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            setOnClickListener { onPick(color) }
        })
    }
    return rows
}

/** A titled 0-to-1 slider; [onChange] fires when the finger lifts, so heavy redraws run once. */
fun Context.slider(title: String, value: Float, colors: Palette, onChange: (Float) -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    addView(label(title, 14f, colors.muted))
    addView(SeekBar(this@slider).apply {
        max = 100
        progress = (value * 100).toInt()
        contentDescription = title
        minimumHeight = dp(48)
        if (colors.finish == UiStyle.FLAT) {
            progressTintList = ColorStateList.valueOf(colors.accent)
            thumbTintList = ColorStateList.valueOf(colors.accent)
        } else {
            // A pressed-in track, an accent fill and a raised knob, as in the kits' sliders.
            val density = resources.displayMetrics.density
            val bar = (8 * density).toInt()
            val knob = (26 * density).toInt()
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
                colors.depthDrawable(density, bar / 2f, Role.FIELD, colors.surface, height = bar),
                android.graphics.drawable.ClipDrawable(android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(mix(colors.accent, Color.WHITE, 0.25f), colors.accent)).apply { cornerRadius = bar / 2f },
                    Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            minimumHeight = (48 * density).toInt()
            if (android.os.Build.VERSION.SDK_INT >= 29) { minHeight = bar; maxHeight = bar }
            thumb = colors.depthDrawable(density, knob / 2f, Role.KEY, if (colors.dark) mix(colors.surface, Color.WHITE, 0.55f) else Color.WHITE,
                width = knob, height = knob)
            splitTrack = false
            unclipAncestors()
        }
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var dragging = false
            // Keys and screen readers change the value without a drag; report those at once.
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !dragging) onChange(progress / 100f)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                onChange(bar.progress / 100f)
            }
        })
    })
}
