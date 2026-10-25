package com.phoneai.app

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Small, cheap interaction animations. All of them honour Android's "remove animations" setting. */
object Motion {
    fun enabled(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Exception) { true }

    /** Press-in scale on touch; never consumes the event, so clicks still fire. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressFeedback(view: View) {
        if (!enabled(view.context)) return
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(.96f).scaleY(.96f).setDuration(80L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140L).setInterpolator(DecelerateInterpolator()).start()
            }
            false
        }
    }

    /** Fade and rise into place; [index] staggers a column of cards. */
    fun enter(view: View, index: Int = 0) {
        if (!enabled(view.context)) return
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 14f
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay((index.coerceAtMost(8) * 45).toLong())
            .setDuration(260L).setInterpolator(DecelerateInterpolator()).start()
    }
}
