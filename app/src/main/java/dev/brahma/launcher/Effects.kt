package dev.brahma.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.EditText

/**
 * Launcher-wide motion. Android does not let a launcher animate other apps, so effects cover
 * every launcher screen plus the app-open transition. All of it is skipped when the user turns
 * motion effects off or Android's "Remove animations" is on.
 */
object Effects {
    fun enabled(settings: LauncherSettings) = settings.effects && ValueAnimator.areAnimatorsEnabled()

    /** Gives every tappable view in [root] the heartbeat pop; list rows add theirs as they are made. */
    fun addPops(root: View) {
        if (root is EditText || root is AbsListView) return
        if (root.isClickable || root.isLongClickable) pop(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addPops(root.getChildAt(index))
    }

    // The listener only animates and returns false, so the view's own click handling still runs.
    @SuppressLint("ClickableViewAccessibility")
    fun pop(view: View) {
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> press(touched, event)
                MotionEvent.ACTION_UP -> beat(touched)
                MotionEvent.ACTION_CANCEL -> settle(touched)
            }
            false
        }
    }

    /** Fades a new screen in from a soft blur and a slight zoom. */
    fun blendIn(root: View) {
        root.alpha = 0f
        root.scaleX = 0.97f
        root.scaleY = 0.97f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        if (Build.VERSION.SDK_INT >= 31) {
            ValueAnimator.ofFloat(18f, 0.01f).apply {
                duration = 280
                addUpdateListener {
                    val radius = it.animatedValue as Float
                    root.setRenderEffect(if (radius < 0.5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                }
            }.start()
        }
    }

    /** Floats each direct child of [group] up into place, one after another. */
    fun stagger(group: ViewGroup) {
        val rise = 16 * group.resources.displayMetrics.density
        for (index in 0 until group.childCount) {
            group.getChildAt(index).apply {
                alpha = 0f
                translationY = rise
                animate().alpha(1f).translationY(0f).setStartDelay(60L + index * 35L).setDuration(320)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    /** Options that open an app by scaling it up out of the view that was tapped. */
    fun openFrom(view: View?): Bundle? =
        view?.takeIf { it.isAttachedToWindow }?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }

    private fun press(view: View, event: MotionEvent) {
        // Tilt toward the finger: up to 8 degrees on each axis.
        val x = (event.x / view.width.coerceAtLeast(1) - 0.5f) * 2
        val y = (event.y / view.height.coerceAtLeast(1) - 0.5f) * 2
        view.cameraDistance = 12000 * view.resources.displayMetrics.density
        view.animate().scaleX(0.92f).scaleY(0.92f).rotationY(x * 8).rotationX(-y * 8).setStartDelay(0)
            .setDuration(110).setInterpolator(DecelerateInterpolator()).start()
    }

    // Double beat: swell, dip, smaller swell, rest.
    private fun beat(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        view.animate().scaleX(1.08f).scaleY(1.08f).rotationX(0f).rotationY(0f).setDuration(120)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).withEndAction {
                    view.animate().scaleX(1.03f).scaleY(1.03f).setDuration(90).withEndAction {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator()).start()
                    }.start()
                }.start()
            }.start()
    }

    private fun settle(view: View) {
        view.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f).setDuration(150).start()
    }
}

/** Moves all apps over home and back, in the way chosen in settings. */
object DrawerTransition {
    private const val DURATION = 260L

    /**
     * Shows [incoming] in [frame] in place of [outgoing]. Opening, all apps arrives on top of home;
     * closing, home is put underneath and all apps leaves off the top of it.
     */
    fun swap(frame: ViewGroup, outgoing: View, incoming: View, motion: DrawerMotion, opening: Boolean) {
        val mover = if (opening) incoming else outgoing
        // The screen that leaves drops focus at once, so no keyboard, cursor or glow outlives it.
        outgoing.findFocus()?.clearFocus()
        if (opening) frame.addView(incoming) else frame.addView(incoming, 0)
        val travel = frame.height.takeIf { it > 0 }?.toFloat() ?: frame.resources.displayMetrics.heightPixels.toFloat()
        fun away(view: View) {
            when (motion) {
                DrawerMotion.SLIDE -> view.translationY = travel
                DrawerMotion.FADE -> view.alpha = 0f
                DrawerMotion.ZOOM -> { view.alpha = 0f; view.scaleX = 0.86f; view.scaleY = 0.86f }
                DrawerMotion.NONE -> Unit
            }
        }
        // Drawn as one layer while it moves: the shadows and glow inside it move with it in one
        // piece and are released with the layer, instead of being redrawn and left behind.
        val animation = mover.animate().setDuration(DURATION).withLayer()
        if (opening) {
            away(mover)
            animation.translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(DecelerateInterpolator())
                .withEndAction { frame.removeView(outgoing) }
        } else {
            when (motion) {
                DrawerMotion.SLIDE -> animation.translationY(travel)
                DrawerMotion.FADE -> animation.alpha(0f)
                DrawerMotion.ZOOM -> animation.alpha(0f).scaleX(0.86f).scaleY(0.86f)
                DrawerMotion.NONE -> Unit
            }
            animation.setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
                frame.removeView(outgoing)
                // Repaint the whole frame so nothing of the closed screen stays on it.
                frame.invalidate()
            }
        }
        animation.start()
    }
}
