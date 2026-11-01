package dev.brahma.launcher

import android.view.MotionEvent
import kotlin.math.abs

/**
 * A vertical swipe that only counts when it starts with the content already scrolled to its edge, so it never
 * fights ordinary scrolling. [up] swipes need home at its end; down swipes need the list at its top.
 * It fires once, after the finger travels [distancePx] mostly straight in that direction. Moving the other way
 * first, or the content scrolling away from its edge, cancels it for the rest of the touch.
 */
class EdgeSwipe(
    private val up: Boolean,
    private val distancePx: Float,
    private val slopPx: Float,
    private val atEdge: () -> Boolean,
    private val onSwipe: () -> Unit
) {
    private var startX = 0f
    private var startY = 0f
    private var armed = false
    var fired = false
        private set

    /** Feeds one touch event; true once the swipe has fired, so the caller can keep the rest of the touch. */
    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                fired = false
                armed = atEdge()
            }
            MotionEvent.ACTION_MOVE -> if (armed && !fired) {
                val travel = if (up) startY - event.y else event.y - startY
                val sideways = abs(event.x - startX)
                when {
                    travel < -slopPx || !atEdge() -> armed = false
                    counts(travel, sideways, distancePx) -> {
                        fired = true
                        onSwipe()
                    }
                }
            }
        }
        return fired
    }

    companion object {
        /** True when [travel] pixels, mostly straight, is enough for a swipe needing [distance] pixels. */
        fun counts(travel: Float, sideways: Float, distance: Float) = travel >= distance && travel > 2 * sideways
    }
}
