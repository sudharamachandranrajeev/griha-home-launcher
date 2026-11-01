package dev.brahma.launcher

import android.animation.ObjectAnimator
import android.animation.StateListAnimator
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
import android.os.Build
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

enum class UiStyle(val title: String) { SOFT("Soft · raised cards and glow"), FLAT("Flat") }

/** Mixes [amount] of [other] into [color]; alpha comes from [color]. */
fun mix(color: Int, other: Int, amount: Float): Int {
    fun channel(shift: Int) = (((color shr shift) and 0xFF) * (1 - amount) + ((other shr shift) and 0xFF) * amount).toInt() shl shift
    return (color and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0)
}

/**
 * Raised surface from the soft UI kit: lit top edge, soft shadow below, a deeper shadow while
 * pressed and an accent ring while focused. [glow] tints the shadow with the accent.
 */
fun View.soft(colors: Palette, radius: Float, fill: Int = colors.surface, glow: Boolean = false) {
    val dp = context.resources.displayMetrics.density
    val top = mix(fill, Color.WHITE, if (colors.dark) 0.07f else 0.55f)
    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, fill)).apply {
        cornerRadius = radius
        setStroke(dp.toInt().coerceAtLeast(1), if (colors.dark) 0x22FFFFFF else 0xB3FFFFFF.toInt())
    }
    elevation = (if (glow) 10 else 5) * dp
    if (Build.VERSION.SDK_INT >= 28) {
        val shade = when {
            glow -> colors.accent
            colors.dark -> Color.BLACK
            else -> 0xFF7D8AA3.toInt()
        }
        outlineSpotShadowColor = shade
        outlineAmbientShadowColor = shade
    }
    stateListAnimator = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ObjectAnimator.ofFloat(this@soft, "translationZ", 6 * dp).setDuration(120))
        addState(intArrayOf(), ObjectAnimator.ofFloat(this@soft, "translationZ", 0f).setDuration(160))
    }
    foreground = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.TRANSPARENT, radius, colors.accent, (2 * dp).toInt()))
    }
}

/** Accent-tinted switch or radio button, as in the soft UI kit's toggles. */
fun CompoundButton.accent(colors: Palette) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    buttonTintList = ColorStateList(states, intArrayOf(colors.accent, colors.muted))
    if (this is android.widget.Switch) {
        thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, mix(colors.surface, Color.WHITE, 0.5f)))
        trackTintList = ColorStateList(states, intArrayOf(colors.accent, colors.muted))
    }
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
        progressTintList = ColorStateList.valueOf(colors.accent)
        thumbTintList = ColorStateList.valueOf(colors.accent)
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
