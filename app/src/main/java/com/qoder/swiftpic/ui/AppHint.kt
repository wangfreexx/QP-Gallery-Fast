package com.qoder.swiftpic.ui

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.qoder.swiftpic.R

/** Translucent in-app replacement for the opaque system toast. */
object AppHint {

    private const val TAG_HINT = "swiftpic_hint"

    fun show(activity: Activity, message: CharSequence, durationMs: Long = 1800L) {
        val root = activity.window.decorView as? ViewGroup ?: return
        root.findViewWithTag<View>(TAG_HINT)?.let { root.removeView(it) }

        val density = activity.resources.displayMetrics.density
        val text = TextView(activity).apply {
            tag = TAG_HINT
            this.text = message
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(
                (14 * density).toInt(),
                (8 * density).toInt(),
                (14 * density).toInt(),
                (8 * density).toInt()
            )
            background = ContextCompat.getDrawable(activity, R.drawable.bg_pill_dark)
            elevation = 6f * density
            alpha = 0f
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (96 * density).toInt()
        }
        root.addView(text, params)
        text.animate().alpha(1f).setDuration(140L).start()
        text.postDelayed({
            text.animate().alpha(0f).setDuration(180L)
                .withEndAction { root.removeView(text) }
                .start()
        }, durationMs)
    }
}
