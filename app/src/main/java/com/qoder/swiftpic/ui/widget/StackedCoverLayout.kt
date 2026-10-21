package com.qoder.swiftpic.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Lays out a front cover plus up to two covers stacked toward the top-right.
 * Children order: back2, back1, front.
 */
class StackedCoverLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var extraLayers = 0

    fun configure(extra: Int) {
        val bounded = extra.coerceIn(0, 2)
        if (bounded == extraLayers) return
        extraLayers = bounded
        getChildAt(0)?.visibility = if (bounded >= 2) View.VISIBLE else View.INVISIBLE
        getChildAt(1)?.visibility = if (bounded >= 1) View.VISIBLE else View.INVISIBLE
        requestLayout()
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return
        val tile = min(width, height)
        val front = getChildAt(2)
        when (extraLayers) {
            0 -> {
                front?.layout(0, 0, width, height)
            }
            1 -> {
                // Diagonal stack up/right. Each back card is slightly larger than the
                // one in front so it covers that card's rounded top-right corner; equal
                // or smaller backs would leave dark wedges where the arcs fail to meet.
                layoutLayer(getChildAt(1), 0.06f, 0.075f, 0.81f, tile)
                layoutLayer(front, 0.02f, 0.14f, 0.78f, tile)
            }
            else -> {
                layoutLayer(getChildAt(0), 0.105f, 0.01f, 0.84f, tile)
                layoutLayer(getChildAt(1), 0.06f, 0.075f, 0.81f, tile)
                layoutLayer(front, 0.02f, 0.14f, 0.78f, tile)
            }
        }
    }

    private fun layoutLayer(view: View?, xFraction: Float, yFraction: Float, sizeFraction: Float, tile: Int) {
        view ?: return
        val size = (tile * sizeFraction).toInt().coerceAtLeast(1)
        val x = (tile * xFraction).toInt()
        val y = (tile * yFraction).toInt()
        view.layout(x, y, x + size, y + size)
    }
}
