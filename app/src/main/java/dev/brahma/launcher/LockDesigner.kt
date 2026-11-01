package dev.brahma.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch

class LockDesignerActions(
    val onChange: (LockDesign) -> Unit,
    /** Draws a preview of the design; the callback gets the picture on the main thread. */
    val preview: (LockDesign, (Bitmap) -> Unit) -> Unit,
    val onPickPhoto: () -> Unit,
    val onRemovePhoto: () -> Unit,
    val onApply: () -> Unit,
    val onDone: () -> Unit
)

/** The lock designer: a live preview with a guide for Android's clock, and every choice below it. */
@SuppressLint("ViewConstructor")
class LockDesigner(
    context: Context,
    private val colors: Palette,
    private var design: LockDesign,
    private val stickers: List<Sticker>,
    private val screenAspect: Float,
    private val actions: LockDesignerActions
) : ScrollView(context) {
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val picture = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        contentDescription = "Lock screen preview"
    }
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var ticket = 0

    init {
        isFillViewport = true
        setBackgroundColor(colors.background)
        body.setPadding(dp(24), dp(20), dp(24), dp(32))
        addView(body)
        body.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(context.label("Lock screen", 28f, colors.ink, Typeface.BOLD), LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.action(context.getString(R.string.done), colors.accent, actions.onDone))
        })
        val height = dp(420)
        body.addView(FrameLayout(context).apply {
            addView(picture, FrameLayout.LayoutParams(-1, -1))
            addView(ClockGuide(context, colors.accent), FrameLayout.LayoutParams(-1, -1))
            soft(colors, dp(12).toFloat())
            clipToOutline = true
        }, LinearLayout.LayoutParams((height / screenAspect).toInt(), height).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(12), 0, dp(12))
        })
        body.addView(context.label("Android always draws its own clock and unlock on top, in about the dashed area. " +
            "Keep faces and text clear of it.", 13f, colors.muted))
        body.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(20), 0, dp(20), 0)
            soft(colors, dp(26).toFloat(), colors.accent, glow = true)
            addView(context.label("Apply to lock screen", 16f, colors.onAccent, Typeface.BOLD))
            contentDescription = "Apply to lock screen"
            setOnClickListener { actions.onApply() }
        }, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(12), 0, dp(4))
        })
        body.addView(controls)
        build()
        refresh()
    }

    private fun change(changed: LockDesign, rebuild: Boolean = false) {
        design = changed
        actions.onChange(changed)
        if (rebuild) build()
        refresh()
    }

    private fun refresh() {
        val mine = ++ticket
        actions.preview(design) { bitmap -> if (mine == ticket) picture.setImageBitmap(bitmap) }
    }

    private fun build() {
        val keep = scrollY
        controls.removeAllViews()
        toggle("Use my design", design.custom) { change(design.copy(custom = it), rebuild = true) }
        if (!design.custom) {
            controls.addView(context.label("Off: the lock screen follows your home layout and colours.", 14f, colors.muted))
            post { scrollTo(0, keep) }
            return
        }
        section("Look")
        toggle("iOS-style depth: giant date behind the subject", design.ios) { on ->
            val photo = design.background == LockBackground.PHOTO || design.background == LockBackground.BLURRED
            change(if (on) design.copy(ios = true, depth = true, font = LockFont.HEAVY, background = if (photo) design.background else LockBackground.PHOTO)
                else design.copy(ios = false), rebuild = true)
        }
        if (design.ios) {
            controls.addView(context.label("Pick a sticker with a subject. The photo moves and zooms so the head overlaps the giant text; " +
                "Android's own clock stays above it.", 14f, colors.muted))
            choices(LockBig.entries.map { it.title }, design.big.ordinal) { change(design.copy(big = LockBig.entries[it])) }
        }
        section("Lock photo")
        controls.addView(context.label(if (design.photoId == null) "A photo just for the lock screen. The subject is found for the depth effect, and the rest can be restyled."
            else "Your lock photo is used for photo backgrounds, instead of a sticker's photo.", 14f, colors.muted))
        controls.addView(LinearLayout(context).apply {
            addView(context.action(if (design.photoId == null) "Add photo" else "Change photo", colors.accent, actions.onPickPhoto))
            if (design.photoId != null) addView(context.action("Remove photo", colors.accent, actions.onRemovePhoto))
        })
        toggle("Keep clear of Android's clock", design.clearClock) { change(design.copy(clearClock = it)) }
        section("Background")
        choices(LockBackground.entries.map { it.title }, design.background.ordinal) { change(design.copy(background = LockBackground.entries[it]), rebuild = true) }
        if (design.background == LockBackground.GENERATED) {
            section("Behind the subject")
            val styles = listOf("Random") + BackdropStyle.entries.map { it.title }
            choices(styles, (design.backdrop?.ordinal ?: -1) + 1) { change(design.copy(backdrop = if (it == 0) null else BackdropStyle.entries[it - 1]), rebuild = true) }
            controls.addView(context.action("Shuffle", colors.accent) { change(design.copy(seed = kotlin.random.Random.nextLong())) })
            toggle("A new one every day", design.daily) { change(design.copy(daily = it)) }
        }
        if (design.background == LockBackground.PHOTO || design.background == LockBackground.BLURRED || design.background == LockBackground.GENERATED) photoLook()
        section("Sticker")
        if (stickers.isEmpty()) controls.addView(context.label("Add a sticker in Settings first to use a photo or subject here.", 14f, colors.muted))
        val names = listOf("None") + stickers.indices.map { context.getString(R.string.sticker_name, it + 1) }
        val chosen = stickers.indexOfFirst { it.id == design.stickerId } + 1
        choices(names, chosen) { change(design.copy(stickerId = if (it == 0) null else stickers[it - 1].id)) }
        toggle("Depth effect: date behind the subject", design.depth) { change(design.copy(depth = it)) }
        section("Text effect")
        val fx = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        fx.addView(LinearLayout(context).apply {
            LockStyle.entries.forEach { style ->
                val chosen = design.style == style
                addView(context.action(style.title, if (chosen) colors.onAccent else colors.accent) { change(design.copy(style = style), rebuild = true) }.apply {
                    if (chosen) background = rounded(colors.accent, dp(20).toFloat())
                    contentDescription = if (chosen) "${style.title}, chosen" else style.title
                })
            }
        })
        controls.addView(fx)
        if (design.style.font != null) controls.addView(context.label("This effect brings its own lettering.", 14f, colors.muted))
        section("Text")
        choices(LockFont.entries.map { it.title }, design.font.ordinal) { change(design.copy(font = LockFont.entries[it])) }
        controls.addView(context.swatchRow(colors, design.textColor, listOf(0 to "Theme colour") + swatches) {
            change(design.copy(textColor = it), rebuild = true)
        })
        controls.addView(context.slider("Text size", design.textSize, colors) { change(design.copy(textSize = it)) })
        if (!design.ios) controls.addView(context.slider("Text height on screen", (design.textY - 0.35f) / 0.55f, colors) {
            change(design.copy(textY = 0.35f + it * 0.55f))
        })
        toggle("Text shadow", design.shadow) { change(design.copy(shadow = it)) }
        if (design.background != LockBackground.PHOTO && design.background != LockBackground.BLURRED) {
            section("Sticker place")
            controls.addView(context.slider("Size", (design.subjectScale - 0.2f) / 0.9f, colors) { change(design.copy(subjectScale = 0.2f + it * 0.9f)) })
            controls.addView(context.slider("Left to right", design.subjectX, colors) { change(design.copy(subjectX = it)) })
            controls.addView(context.slider("Top to bottom", design.subjectY, colors) { change(design.copy(subjectY = it)) })
        }
        post { scrollTo(0, keep) }
    }

    private fun photoLook() {
        section("Photo look")
        val chips = LinearLayout(context)
        LookPreset.entries.filter { it != LookPreset.CUSTOM }.forEach { preset ->
            val chosen = design.look.preset == preset
            chips.addView(context.action(preset.title, if (chosen) colors.onAccent else colors.accent) {
                change(design.copy(look = design.look.with(preset)), rebuild = true)
            }.apply {
                if (chosen) background = rounded(colors.accent, dp(20).toFloat())
                contentDescription = if (chosen) "${preset.title}, chosen" else preset.title
            })
        }
        controls.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        val look = design.look
        // Signed sliders show -1..1 on a 0..1 track.
        fun signed(title: String, value: Float, set: (PhotoLook, Float) -> PhotoLook) =
            controls.addView(context.slider(title, (value + 1) / 2, colors) { change(design.copy(look = set(design.look, it * 2 - 1).copy(preset = LookPreset.CUSTOM)), rebuild = true) })
        fun unsigned(title: String, value: Float, set: (PhotoLook, Float) -> PhotoLook) =
            controls.addView(context.slider(title, value, colors) { change(design.copy(look = set(design.look, it).copy(preset = LookPreset.CUSTOM)), rebuild = true) })
        signed("Exposure", look.exposure) { l, v -> l.copy(exposure = v) }
        signed("Contrast", look.contrast) { l, v -> l.copy(contrast = v) }
        signed("Saturation", look.saturation) { l, v -> l.copy(saturation = v) }
        signed("Warmth", look.warmth) { l, v -> l.copy(warmth = v) }
        unsigned("Matte fade", look.fade) { l, v -> l.copy(fade = v) }
        unsigned("Vignette", look.vignette) { l, v -> l.copy(vignette = v) }
        unsigned("Film grain", look.grain) { l, v -> l.copy(grain = v) }
        unsigned("Soft glow", look.glow) { l, v -> l.copy(glow = v) }
    }

    private fun section(title: String) {
        controls.addView(View(context), LinearLayout.LayoutParams(1, dp(16)))
        controls.addView(context.label(title.uppercase(), 12f, colors.accent, Typeface.BOLD))
    }

    private fun choices(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val group = RadioGroup(context)
        titles.forEachIndexed { index, title ->
            group.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = title
                textSize = 16f
                minHeight = dp(48)
                isChecked = index == selected
                accent(colors)
                setOnClickListener { onSelect(index) }
            })
        }
        controls.addView(group)
    }

    private fun toggle(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        controls.addView(Switch(context).apply {
            text = title
            textSize = 16f
            minHeight = dp(48)
            isChecked = checked
            accent(colors)
            setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> onToggle(value) }
        })
    }

    private fun dp(value: Int) = context.dp(value)

    /** Dashed box where Android's lock clock usually sits. */
    private class ClockGuide(context: Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            strokeWidth = context.dp(1).toFloat()
            pathEffect = DashPathEffect(floatArrayOf(context.dp(6).toFloat(), context.dp(4).toFloat()), 0f)
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = context.dp(10).toFloat()
        }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val top = height * LockScreen.CLOCK_TOP
            val bottom = height * LockScreen.CLOCK_BOTTOM
            canvas.drawRect(width * 0.06f, top, width * 0.94f, bottom, paint)
            canvas.drawText("Android's clock", width * 0.09f, top + text.textSize * 1.4f, text)
        }
    }
}
