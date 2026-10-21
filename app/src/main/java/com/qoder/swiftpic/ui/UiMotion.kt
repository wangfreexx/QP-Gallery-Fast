package com.qoder.swiftpic.ui

import android.content.Context
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.swiftPic

/**
 * Window transitions are set to a zero-length placeholder in the theme so these
 * view-level animations are the only motion on screen and the animation-speed
 * preference really changes how long screens and dialogs take to appear.
 */
object UiMotion {

    fun duration(context: Context, baseMs: Long): Long =
        (baseMs / context.swiftPic.prefs.animationScale).toLong().coerceAtLeast(20L)

    fun animateEnter(view: View, baseMs: Long = 180L, offsetDp: Float = 8f, fadeOnly: Boolean = false) {
        val duration = duration(view.context, baseMs)
        view.alpha = 0f
        if (!fadeOnly) {
            view.translationY = offsetDp * view.resources.displayMetrics.density
        }
        view.post {
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}

fun MaterialAlertDialogBuilder.showMotion(): AlertDialog {
    val dialog = create()
    dialog.window?.setWindowAnimations(R.style.SwiftPic_WindowInstant)
    val duration = UiMotion.duration(dialog.context, 170L)
    dialog.setOnShowListener {
        val decor = dialog.window?.decorView ?: return@setOnShowListener
        decor.animate().cancel()
        decor.alpha = 0f
        decor.scaleX = 0.95f
        decor.scaleY = 0.95f
        decor.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
    dialog.show()
    return dialog
}
