package dev.brahma.launcher

import android.animation.TimeAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A sticker's movement at one moment: shift as fractions of its width, turn in degrees, and size. */
class Pose {
    var dx = 0f
    var dy = 0f
    var rotation = 0f
    var scale = 1f
    /** Where it turns from, as a fraction of its height: 0.5 is the middle, 1 the bottom edge. */
    var pivotY = 0.5f
}

/** Sticker motion as plain arithmetic, so it can be unit tested and shared by home and the editor. */
object Moves {
    /**
     * Fills [out] for [motion] at [seconds]. [tiltX] and [tiltY] run from -1 to 1 (phone tipped
     * fully left or forward); [phase] staggers several stickers so they do not move in step.
     */
    fun pose(motion: StickerMotion, seconds: Float, tiltX: Float, tiltY: Float, phase: Float, out: Pose): Pose {
        val t = seconds + phase
        out.dx = 0f
        out.dy = 0f
        out.rotation = 0f
        out.scale = 1f
        out.pivotY = 0.5f
        when (motion) {
            StickerMotion.NONE -> Unit
            StickerMotion.FLOAT -> {
                out.dy = -0.04f * wave(t, 3.2f)
                out.rotation = 1.5f * wave(t, 6.4f)
            }
            StickerMotion.SWAY -> {
                out.rotation = 6f * wave(t, 2.8f)
                out.pivotY = 1f
            }
            StickerMotion.BREATHE -> out.scale = 1f + 0.05f * wave(t, 2.4f)
            StickerMotion.WANDER -> {
                out.dx = 0.06f * wave(t, 7f) + 0.03f * wave(t, 3.1f)
                out.dy = 0.05f * cos(2 * PI.toFloat() * t / 5.3f)
                out.rotation = 3f * wave(t, 4.7f)
            }
            // The subject slides against the tilt and sits slightly larger, so it seems to stand off the screen.
            StickerMotion.TILT -> {
                out.dx = -0.08f * tiltX.coerceIn(-1f, 1f)
                out.dy = 0.08f * tiltY.coerceIn(-1f, 1f)
                out.rotation = -2f * tiltX.coerceIn(-1f, 1f)
                out.scale = 1.04f
            }
        }
        return out
    }

    private fun wave(t: Float, period: Float) = sin(2 * PI.toFloat() * t / period)
}

/**
 * Stickers on home. Home draws them behind its text and buttons; in arrange mode it lifts them on
 * top, a finger drags the sticker under it and two fingers resize it. Places are fractions of the
 * visible screen ([viewport]), kept per home [layout]. When [moving], stickers play their motion
 * while home is on screen.
 */
@SuppressLint("ViewConstructor")
class StickerLayer(
    context: Context,
    items: List<Pair<Sticker, Bitmap>>,
    private val layout: String,
    private val arranging: Boolean,
    private val moving: Boolean,
    private val viewport: View,
    colors: Palette,
    private val onDone: (List<Sticker>) -> Unit
) : FrameLayout(context), SensorEventListener {
    private val placed = items.mapIndexed { index, (sticker, bitmap) -> Placed(sticker, sticker.place(layout), ImageView(context).apply {
        setImageBitmap(bitmap)
        scaleType = ImageView.ScaleType.FIT_XY
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }, bitmap.height / bitmap.width.toFloat(), index * 1.7f) }
    private var selected: Placed? = null
    private var lastX = 0f
    private var lastY = 0f
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colors.accent
        strokeWidth = context.dp(2).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(context.dp(8).toFloat(), context.dp(6).toFloat()), 0f)
    }
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val item = selected ?: return false
            item.scale = (item.scale * detector.scaleFactor).coerceIn(0.1f, 1.2f)
            position(item)
            return true
        }
    })
    private val animates = moving && !arranging && placed.any { it.sticker.motion != StickerMotion.NONE }
    private val tilts = animates && placed.any { it.sticker.motion == StickerMotion.TILT }
    private val pose = Pose()
    private var tiltX = 0f
    private var tiltY = 0f
    private var neutral: FloatArray? = null
    private val clock = TimeAnimator().apply {
        setTimeListener { _, total, _ -> move(total / 1000f) }
    }
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gravity = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private class Placed(val sticker: Sticker, place: Place, val view: ImageView, val aspect: Float, val phase: Float) {
        var x = place.x
        var y = place.y
        var scale = place.scale
        var left = 0f
        var top = 0f
    }

    private val reposition = OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) placed.forEach(::position)
    }

    init {
        setWillNotDraw(!arranging)
        placed.forEach { item ->
            addView(item.view)
            position(item)
        }
        if (arranging) {
            setBackgroundColor((colors.background and 0x00FFFFFF) or 0x55000000)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
                soft(colors, context.dp(24).toFloat())
                addView(context.label("Drag a sticker to move it. Pinch to resize.", 14f, colors.ink).apply { gravity = Gravity.CENTER })
                addView(context.action(context.getString(R.string.done), colors.accent) { finish() }.apply { setTypeface(typeface, Typeface.BOLD) })
            }, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = context.dp(96) })
        }
    }

    fun finish() = onDone(placed.map { it.sticker.placed(layout, Place(it.x, it.y, it.scale)) })

    // Places use the visible screen, not the whole scrolling home, so a spot means the same on every layout.
    private fun screenWidth() = (viewport.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
    private fun screenHeight() = (viewport.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()

    private fun position(item: Placed) {
        val width = screenWidth() * item.scale
        val height = width * item.aspect
        item.view.layoutParams = LayoutParams(width.toInt(), height.toInt())
        item.left = item.x * screenWidth() - width / 2
        item.top = item.y * screenHeight() - height / 2
        item.view.translationX = item.left
        item.view.translationY = item.top
        invalidate()
    }

    private fun move(seconds: Float) {
        placed.forEach { item ->
            val view = item.view
            val width = view.layoutParams.width.toFloat()
            Moves.pose(item.sticker.motion, seconds, tiltX, tiltY, item.phase, pose)
            view.pivotX = width / 2
            view.pivotY = view.layoutParams.height * pose.pivotY
            view.translationX = item.left + pose.dx * width
            view.translationY = item.top + pose.dy * width
            view.rotation = pose.rotation
            view.scaleX = pose.scale
            view.scaleY = pose.scale
        }
    }

    // Motion and the tilt sensor run only while home is on screen, to spare the battery.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewport.addOnLayoutChangeListener(reposition)
        if (windowVisibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        viewport.removeOnLayoutChangeListener(reposition)
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (!animates || clock.isStarted) return
        neutral = null
        if (tilts) gravity?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        clock.start()
    }

    private fun stop() {
        clock.cancel()
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Gravity along the screen's x and y axes, measured from how the phone was held when home
        // appeared, and smoothed so hand shake does not jitter the sticker.
        val rawX = event.values[0] / SensorManager.GRAVITY_EARTH
        val rawY = event.values[1] / SensorManager.GRAVITY_EARTH
        val start = neutral ?: floatArrayOf(rawX, rawY).also { neutral = it }
        val x = (rawX - start[0]) * 2f
        val y = (rawY - start[1]) * 2f
        tiltX += (x - tiltX) * 0.15f
        tiltY += (y - tiltY) * 0.15f
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onDraw(canvas: Canvas) {
        val item = selected ?: return
        val view = item.view
        canvas.drawRect(view.translationX, view.translationY, view.translationX + view.layoutParams.width,
            view.translationY + view.layoutParams.height, outline)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!arranging) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selected = placed.lastOrNull { item ->
                    val view = item.view
                    event.x in view.translationX..(view.translationX + view.layoutParams.width) &&
                        event.y in view.translationY..(view.translationY + view.layoutParams.height)
                }
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && event.pointerCount == 1) selected?.let { item ->
                item.x = (item.x + (event.x - lastX) / screenWidth()).coerceIn(0f, 1f)
                item.y = (item.y + (event.y - lastY) / screenHeight()).coerceIn(0f, 1.5f)
                lastX = event.x
                lastY = event.y
                position(item)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Restart the drag from the finger that stays down.
                val stay = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(stay)
                lastY = event.getY(stay)
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick() = super.performClick()
}
