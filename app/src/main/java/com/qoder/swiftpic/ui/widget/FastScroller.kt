package com.qoder.swiftpic.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Right-edge scrubber that stays hidden until the list scrolls vertically, then
 * fades out again. Dragging it jumps through a long grid much faster than flicking.
 */
class FastScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var recycler: RecyclerView? = null
    // 记录「注册过 observer 的那个 adapter」：adapter 被替换后仍能精确注销。
    private var observedAdapter: RecyclerView.Adapter<*>? = null
    private var dragging = false
    private var thumbTop = 0f
    private var thumbHeight = 0f
    private var dragFraction = 0f
    private var lastSeekPosition = RecyclerView.NO_POSITION
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val density = resources.displayMetrics.density
    private val thumbVisualWidth = 18f * density
    private val edgeMargin = 2f * density
    private val minThumb = 36f * density

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(this@FastScroller, androidx.appcompat.R.attr.colorPrimary, 0xFFAAAAAA.toInt()),
            110
        )
    }
    private val rect = RectF()

    private val hideRunnable = Runnable { if (!dragging) fadeOut() }
    private val updateThumbRunnable = Runnable { if (!updateThumb()) hideNow() }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy == 0 && thumbHeight > 0f) return
            if (!updateThumb()) {
                hideNow()
                return
            }
            if (dy != 0 || dragging) showNow()
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                if (dragging) showNow() else scheduleHide()
            }
        }
    }

    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = scheduleThumbUpdate()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scheduleThumbUpdate()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scheduleThumbUpdate()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
            scheduleThumbUpdate()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = scheduleThumbUpdate()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
            scheduleThumbUpdate()

        private fun scheduleThumbUpdate() {
            removeCallbacks(updateThumbRunnable)
            post(updateThumbRunnable)
        }
    }

    fun attachTo(recyclerView: RecyclerView) {
        // 重新绑定前先干净地断开旧的监听，避免重复注册/泄漏。
        detach()
        recycler = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
        val adapter = recyclerView.adapter
        if (adapter != null) {
            adapter.registerAdapterDataObserver(dataObserver)
            observedAdapter = adapter
        }
        visibility = GONE
    }

    /** 解注册所有监听并复位拖拽状态；attachTo 与 onDetachedFromWindow 都会调用。 */
    fun detach() {
        recycler?.removeOnScrollListener(scrollListener)
        val adapter = observedAdapter
        if (adapter != null) {
            try {
                adapter.unregisterAdapterDataObserver(dataObserver)
            } catch (e: Exception) {
                Log.w(TAG, "unregister data observer failed", e)
            }
        }
        observedAdapter = null
        recycler = null
        removeCallbacks(hideRunnable)
        removeCallbacks(updateThumbRunnable)
        animate().cancel()
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        lastSeekPosition = RecyclerView.NO_POSITION
        dragFraction = 0f
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateThumb()
    }

    /** @return false when the content fits and no thumb is needed. */
    private fun updateThumb(): Boolean {
        val rv = recycler ?: return false
        val range = rv.computeVerticalScrollRange().toFloat()
        val extent = rv.computeVerticalScrollExtent().toFloat()
        val offset = rv.computeVerticalScrollOffset().toFloat()
        val track = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        if (range <= extent * 1.05f || extent <= 0f) {
            visibility = GONE
            return false
        }
        thumbHeight = (track * extent / range).coerceIn(minThumb.coerceAtMost(track), track)
        val maxTop = (track - thumbHeight).coerceAtLeast(1f)
        val fraction = if (dragging) {
            dragFraction
        } else if (range - extent <= 0f) {
            0f
        } else {
            offset / (range - extent)
        }
        thumbTop = paddingTop + maxTop * fraction.coerceIn(0f, 1f)
        invalidate()
        return true
    }

    private fun showNow() {
        removeCallbacks(hideRunnable)
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 0f
            Log.d(TAG, "show thumb=${thumbHeight.toInt()}px top=${thumbTop.toInt()}px")
        }
        animate().cancel()
        animate().alpha(1f).setDuration(90L).start()
        postDelayed(hideRunnable, 1500L)
    }

    private fun scheduleHide() {
        if (visibility != VISIBLE) return
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, 600L)
    }

    private fun fadeOut() {
        if (visibility != VISIBLE) return
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction { visibility = GONE }
            .start()
    }

    private fun hideNow() {
        removeCallbacks(hideRunnable)
        animate().cancel()
        visibility = GONE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE || recycler == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Log.d(TAG, "down x=${event.x} y=${event.y} w=$width h=$height vis=$visibility")
                // Only the outer strip is a grab handle so the grid stays tappable.
                if (event.x < width - thumbVisualWidth - edgeMargin) return false
                dragging = true
                activePointerId = event.getPointerId(event.actionIndex)
                lastSeekPosition = RecyclerView.NO_POSITION
                removeCallbacks(hideRunnable)
                parent?.requestDisallowInterceptTouchEvent(true)
                seekTo(event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指不改变主指针，拖动仍跟随第一根手指。
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val index = event.findPointerIndex(activePointerId)
                    seekTo(if (index >= 0) event.getY(index) else event.y)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (dragging && event.getPointerId(event.actionIndex) == activePointerId) {
                    // 主指针抬起：切到剩下的那根指针，避免 seek 位置突跳。
                    val nextIndex = if (event.actionIndex == 0) 1 else 0
                    if (nextIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(nextIndex)
                        lastSeekPosition = RecyclerView.NO_POSITION
                        seekTo(event.getY(nextIndex))
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                scheduleHide()
                return true
            }
        }
        return false
    }

    /**
     * Jumps straight to the target row instead of scrolling by a huge offset: on a
     * 20k-item grid a single scrollBy has to bind and recycle thousands of rows and
     * freezes the main thread long enough for an ANR.
     */
    private fun seekTo(y: Float) {
        val rv = recycler ?: return
        val itemCount = rv.adapter?.itemCount ?: return
        if (itemCount <= 0) return
        val track = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        val maxTop = (track - thumbHeight).coerceAtLeast(1f)
        val fraction = ((y - thumbHeight / 2f - paddingTop) / maxTop).coerceIn(0f, 1f)
        dragFraction = fraction
        thumbTop = paddingTop + maxTop * fraction
        invalidate()
        val position = (fraction * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
        if (position == lastSeekPosition) return
        lastSeekPosition = position
        (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        Log.d(
            TAG,
            "seek item=$position/$itemCount fraction=${"%.2f".format(fraction)} " +
                "y=$y h=$height thumb=$thumbHeight maxTop=$maxTop"
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (recycler == null || visibility != VISIBLE) return
        val right = width - edgeMargin
        val left = max(0f, right - thumbVisualWidth)
        val radius = min(thumbVisualWidth / 2f, thumbHeight / 2f)
        rect.set(left, thumbTop, right, thumbTop + thumbHeight)
        canvas.drawRoundRect(rect, radius, radius, thumbPaint)
    }

    override fun onDetachedFromWindow() {
        detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG = "SwiftPicScroll"
    }
}
