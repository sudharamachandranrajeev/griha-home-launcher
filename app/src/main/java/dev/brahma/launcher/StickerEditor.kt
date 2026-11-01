package dev.brahma.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.CompoundButton
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import kotlin.concurrent.thread
import kotlin.math.max

class StickerEditorActions(
    /** Saves the sticker and its edited mask (null when there is no cutout). */
    val onSave: (Sticker, Bitmap?) -> Unit,
    val onExport: (Sticker, Bitmap?) -> Unit,
    val onShare: (Sticker, Bitmap?) -> Unit,
    val onCancel: () -> Unit
)

/**
 * Edits one sticker: a live preview, a refine brush that adds to or erases the cutout, and a
 * stack of effects with colour and sliders. [mask] is edited in place; [autoMask] is ML Kit's
 * original cutout for "Reset".
 */
@SuppressLint("ViewConstructor")
class StickerEditor(
    context: Context,
    private val colors: Palette,
    private var sticker: Sticker,
    private val source: Bitmap,
    private val autoMask: Bitmap?,
    private val mask: Bitmap?,
    note: String?,
    private val actions: StickerEditorActions
) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    // Previews are drawn from a small copy so slider changes stay quick.
    private val previewScale = (PREVIEW_SIDE / max(source.width, source.height).toFloat()).coerceAtMost(1f)
    private val smallSource = scaled(source)
    private val canvasView = EditCanvas(context)
    private val panel = LinearLayout(context).apply { orientation = VERTICAL }
    private val undo = ArrayDeque<Bitmap>()
    private val effectsUndo = ArrayDeque<List<StickerEffect>>()
    private var refining = false
    private var motionTab = false
    private var adding = true
    private var brush = 0.3f
    private var generation = 0

    init {
        orientation = VERTICAL
        finishBackdrop(colors)
        setPadding(dp(16), dp(12), dp(16), 0)
        addView(LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(context.label("Sticker", 26f, colors.ink, Typeface.BOLD), LayoutParams(0, -2, 1f))
            addView(context.action(context.getString(R.string.cancel), colors.accent, actions.onCancel))
            addView(context.action(context.getString(R.string.save), colors.accent) { actions.onSave(sticker, mask) })
        })
        if (note != null) addView(context.label(note, 14f, colors.muted).apply { setPadding(0, 0, 0, dp(6)) })
        addView(canvasView, LayoutParams(-1, dp(300)))
        addView(LinearLayout(context).apply {
            addView(context.action("Effects", colors.accent) { refining = false; motionTab = false; showPanel() })
            addView(context.action("Motion", colors.accent) { refining = false; motionTab = true; showPanel() })
            if (mask != null) addView(context.action("Refine", colors.accent) { refining = true; motionTab = false; showPanel() })
            addView(View(context), LayoutParams(0, 1, 1f))
            addView(context.action("Save PNG", colors.accent) { actions.onExport(sticker, mask) })
            addView(context.action("Share", colors.accent) { actions.onShare(sticker, mask) })
        })
        addView(ScrollView(context).apply { addView(panel) }, LayoutParams(-1, 0, 1f))
        showPanel()
        redraw()
    }

    private fun showPanel() {
        panel.removeAllViews()
        canvasView.invalidate()
        if (refining) refinePanel() else if (motionTab) motionPanel() else effectsPanel()
    }

    private fun motionPanel() {
        panel.addView(context.label("How the sticker moves on home. Needs Motion effects on in Settings. Tilt follows how you hold the phone.", 14f, colors.muted))
        val group = RadioGroup(context)
        StickerMotion.entries.forEach { motion ->
            group.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = motion.title
                textSize = 16f
                minHeight = dp(48)
                isChecked = sticker.motion == motion
                accent(colors)
                setOnClickListener { sticker = sticker.copy(motion = motion) }
            })
        }
        panel.addView(group)
    }

    private fun effectsPanel() {
        val chips = LinearLayout(context)
        EffectType.entries.forEach { type ->
            chips.addView(context.action("+ ${type.title}", colors.accent) {
                val color = if (type == EffectType.SHADOW) Color.BLACK else if (type == EffectType.OUTLINE) Color.WHITE else colors.accent
                setEffects(sticker.effects + StickerEffect(type, color))
                showPanel()
            })
        }
        panel.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        panel.addView(LinearLayout(context).apply {
            addView(View(context), LayoutParams(0, 1, 1f))
            addView(context.action("Undo", colors.accent) {
                val previous = effectsUndo.removeLastOrNull() ?: return@action
                update(sticker.copy(effects = previous))
                showPanel()
            }.apply { isEnabled = effectsUndo.isNotEmpty(); alpha = if (isEnabled) 1f else 0.4f })
            addView(context.action("Remove all", colors.accent) {
                setEffects(emptyList())
                showPanel()
            }.apply { isEnabled = sticker.effects.isNotEmpty(); alpha = if (isEnabled) 1f else 0.4f })
        })
        if (sticker.effects.isEmpty()) panel.addView(context.label("No effects yet. Add one above; they stack in order.", 14f, colors.muted))
        sticker.effects.forEachIndexed { index, effect ->
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                soft(colors, dp(18).toFloat())
                setPadding(dp(14), dp(6), dp(14), dp(8))
            }
            card.addView(LinearLayout(context).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(context.label(effect.type.title, 16f, colors.ink, Typeface.BOLD), LayoutParams(0, -2, 1f))
                addView(context.action("Remove", colors.accent) {
                    setEffects(sticker.effects.filterIndexed { i, _ -> i != index })
                    showPanel()
                })
            })
            // Sliders report on finger lift, so each drag is one undo step; the panel is rebuilt so Undo lights up.
            fun change(changed: StickerEffect) {
                setEffects(sticker.effects.mapIndexed { i, e -> if (i == index) changed else e })
                showPanel()
            }
            if (effect.type in coloured) card.addView(context.swatchRow(colors, effect.color, swatches) { change(effect.copy(color = it)) })
            if (effect.type in sized) card.addView(context.slider(sizeTitle(effect.type), effect.size, colors) { change(sticker.effects[index].copy(size = it)) })
            if (effect.type in strong) card.addView(context.slider("Strength", effect.strength, colors) { change(sticker.effects[index].copy(strength = it)) })
            panel.addView(card, LayoutParams(-1, -2).apply { setMargins(dp(2), dp(6), dp(2), dp(6)) })
        }
    }

    private fun refinePanel() {
        panel.addView(Switch(context).apply {
            text = context.getString(R.string.cut_out_subject)
            textSize = 16f
            minHeight = dp(48)
            isChecked = sticker.subject
            accent(colors)
            setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> update(sticker.copy(subject = value)) }
        })
        panel.addView(context.label("Paint on the picture to add to or erase from the cutout.", 14f, colors.muted))
        panel.addView(LinearLayout(context).apply {
            addView(mode("Add", true))
            addView(mode("Erase", false))
            addView(View(context), LayoutParams(0, 1, 1f))
            addView(context.action("Undo", colors.accent) {
                val previous = undo.removeLastOrNull() ?: return@action
                mask?.let { Canvas(it).apply { drawColor(0, PorterDuff.Mode.CLEAR); drawBitmap(previous, 0f, 0f, null) } }
                redraw()
            })
            addView(context.action("Reset", colors.accent) {
                if (mask == null || autoMask == null) return@action
                pushUndo()
                Canvas(mask).apply { drawColor(0, PorterDuff.Mode.CLEAR); drawBitmap(autoMask, 0f, 0f, null) }
                redraw()
            })
        })
        panel.addView(context.slider("Brush size", brush, colors) { brush = it })
        panel.addView(context.slider("Soft edges, for hair", sticker.softness, colors) { update(sticker.copy(softness = it)) })
    }

    private fun mode(title: String, add: Boolean) = context.action(title, if (adding == add) colors.onAccent else colors.accent) {
        adding = add
        showPanel()
    }.apply {
        if (adding == add) depth(colors, dp(20).toFloat(), Role.PRIMARY)
        contentDescription = if (adding == add) "$title, chosen" else title
    }

    /** Changes the effect stack and remembers the old one for Undo. */
    private fun setEffects(effects: List<StickerEffect>) {
        if (effects == sticker.effects) return
        effectsUndo.addLast(sticker.effects)
        while (effectsUndo.size > EFFECT_STEPS) effectsUndo.removeFirst()
        update(sticker.copy(effects = effects))
    }

    private fun update(changed: Sticker) {
        sticker = changed
        redraw()
    }

    // Draws the preview off the main thread; a newer request makes older results stale.
    private fun redraw() {
        val ticket = ++generation
        val current = sticker
        val smallMask = mask?.takeIf { current.subject }?.let { scaled(it) }
        thread(name = "sticker-preview") {
            val edge = smallMask?.let { StickerArt.softEdge(it, current.softness) }
            val drawn = StickerArt.render(smallSource, edge, current.effects)
            main.post {
                if (ticket == generation) {
                    canvasView.preview = drawn
                    canvasView.invalidate()
                }
            }
        }
        canvasView.invalidate()
    }

    private fun pushUndo() {
        val copy = mask?.copy(Bitmap.Config.ARGB_8888, false) ?: return
        undo.addLast(copy)
        while (undo.size > UNDO_STEPS) undo.removeFirst()
    }

    private fun scaled(bitmap: Bitmap) = if (previewScale >= 1f) bitmap
        else Bitmap.createScaledBitmap(bitmap, (bitmap.width * previewScale).toInt().coerceAtLeast(1), (bitmap.height * previewScale).toInt().coerceAtLeast(1), true)

    private fun dp(value: Int) = context.dp(value)

    private fun sizeTitle(type: EffectType) = when (type) {
        EffectType.MONO, EffectType.POP -> "Contrast"
        else -> "Size"
    }

    /** Checkerboard preview; in refine mode, the photo dimmed with the cutout at full strength. */
    private inner class EditCanvas(context: Context) : View(context) {
        var preview: Drawn? = null
        private val fit = Matrix()
        private val inverse = Matrix()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val check = Paint().apply { color = 0x22888888 }
        private val point = FloatArray(2)
        private val toScreen = Matrix()
        private val cut = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        private var last: FloatArray? = null

        init { contentDescription = "Sticker preview" }

        override fun onDraw(canvas: Canvas) {
            val cell = dp(12).toFloat()
            var row = 0
            var y = 0f
            while (y < height) {
                var x = if (row % 2 == 0) 0f else cell
                while (x < width) {
                    canvas.drawRect(x, y, x + cell, y + cell, check)
                    x += cell * 2
                }
                y += cell
                row++
            }
            val scale = minOf(width / source.width.toFloat(), height / source.height.toFloat()) * 0.9f
            fit.setScale(scale, scale)
            fit.postTranslate((width - source.width * scale) / 2, (height - source.height * scale) / 2)
            fit.invert(inverse)
            if (refining && mask != null) {
                paint.alpha = 70
                canvas.drawBitmap(source, fit, paint)
                paint.alpha = 255
                val layer = canvas.saveLayer(null, null)
                canvas.drawBitmap(source, fit, paint)
                paint.xfermode = cut
                canvas.drawBitmap(mask, fit, paint)
                paint.xfermode = null
                canvas.restoreToCount(layer)
                return
            }
            val drawn = preview ?: return
            // Preview pixels sit in the small copy's space, offset by the drawn sticker's corner.
            toScreen.set(fit)
            toScreen.preScale(1 / previewScale, 1 / previewScale)
            toScreen.preTranslate(drawn.left.toFloat(), drawn.top.toFloat())
            canvas.drawBitmap(drawn.bitmap, toScreen, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val target = mask
            if (!refining || target == null) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pushUndo()
                    last = null
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    redraw()
                    return true
                }
            }
            point[0] = event.x
            point[1] = event.y
            inverse.mapPoints(point)
            val radius = 4 + brush * max(source.width, source.height) * 0.05f
            brushPaint.xfermode = if (adding) null else PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            brushPaint.color = Color.WHITE
            brushPaint.strokeWidth = radius * 2
            brushPaint.strokeCap = Paint.Cap.ROUND
            // Join to the previous point so fast strokes leave no gaps.
            val from = last ?: floatArrayOf(point[0], point[1])
            Canvas(target).drawLine(from[0], from[1], point[0], point[1], brushPaint)
            last = floatArrayOf(point[0], point[1])
            invalidate()
            return true
        }

        override fun performClick() = super.performClick()
    }

    private companion object {
        const val PREVIEW_SIDE = 640
        const val UNDO_STEPS = 5
        const val EFFECT_STEPS = 30
        val coloured = setOf(EffectType.OUTLINE, EffectType.GLOW, EffectType.SHADOW, EffectType.DUOTONE)
        val sized = setOf(EffectType.OUTLINE, EffectType.GLOW, EffectType.SHADOW, EffectType.MONO, EffectType.POP)
        val strong = setOf(EffectType.GLOW, EffectType.SHADOW, EffectType.DUOTONE, EffectType.MONO, EffectType.POP, EffectType.FADE)
    }
}
