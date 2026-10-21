package com.qoder.swiftpic.ui.viewer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.qoder.swiftpic.swiftPic
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr),
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    ScaleGestureDetector.OnScaleGestureListener,
    Drawable.Callback {

    private var bitmap: Bitmap? = null
    private var drawable: Drawable? = null
    private var imageWidth = 0
    private var imageHeight = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    private var scale = 1f
    private var baseScale = 1f
    private var minScale = 0.4f
    private var maxScale = 4f
    private var tx = 0f
    private var ty = 0f

    private val scaleDetector = ScaleGestureDetector(context, this)
    private val gestureDetector = GestureDetector(context, this).apply {
        setIsLongpressEnabled(true)
        setOnDoubleTapListener(this@ZoomImageView)
    }
    private val flingScroller = OverScroller(context)
    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!flingScroller.computeScrollOffset()) return
            val newTx = flingScroller.currX.toFloat()
            val newTy = flingScroller.currY.toFloat()
            if (abs(newTx - tx) < 0.01f && abs(newTy - ty) < 0.01f) return
            tx = newTx
            ty = newTy
            applyTransform()
            invalidate()
            if (!flingScroller.isFinished) postOnAnimation(this)
        }
    }

    private var longPressTriggered = false
    private var animator: ValueAnimator? = null

    var onTap: ((Float, Float) -> Unit)? = null
    var onLongPressStart: (() -> Unit)? = null
    var onLongPressEnd: (() -> Unit)? = null
    var onEdgePage: ((Int) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val edgeThreshold = 110f * density
    private val narrowPageThreshold = 130f * density
    private var edgeAccum = 0f
    private var edgeDirection = 0
    private var edgeTriggered = false
    private var lastNotifiedRatio = Float.NaN

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        applyTransform()
    }

    fun setBitmap(newBitmap: Bitmap) {
        if (bitmap === newBitmap && drawable == null) return
        stopDrawable()
        val dimensionsChanged = newBitmap.width != imageWidth || newBitmap.height != imageHeight
        bitmap = newBitmap
        drawable = null
        imageWidth = newBitmap.width
        imageHeight = newBitmap.height
        if (dimensionsChanged) resetToFit() else applyTransform()
        invalidate()
    }

    fun setFrameBitmap(newBitmap: Bitmap) {
        val dimensionsChanged = newBitmap.width != imageWidth || newBitmap.height != imageHeight
        bitmap = newBitmap
        if (dimensionsChanged) {
            drawable = null
            imageWidth = newBitmap.width
            imageHeight = newBitmap.height
            resetToFit()
        } else {
            applyTransform()
        }
        invalidate()
    }

    fun setAnimatedDrawable(newDrawable: Drawable, width: Int, height: Int) {
        stopDrawable()
        bitmap = null
        drawable = newDrawable
        newDrawable.callback = this
        imageWidth = width.coerceAtLeast(1)
        imageHeight = height.coerceAtLeast(1)
        newDrawable.setBounds(0, 0, imageWidth, imageHeight)
        resetToFit()
        invalidate()
    }

    fun clearImage() {
        stopDrawable()
        bitmap = null
        drawable = null
        imageWidth = 0
        imageHeight = 0
        animator?.cancel()
        animator = null
        invalidate()
    }

    fun hasImage(): Boolean = imageWidth > 0 && imageHeight > 0

    fun startAnimation() {
        (drawable as? AnimatedImageDrawable)?.start()
    }

    fun stopAnimation() {
        (drawable as? AnimatedImageDrawable)?.stop()
    }

    private fun stopDrawable() {
        (drawable as? AnimatedImageDrawable)?.stop()
        drawable?.callback = null
        drawable = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (imageWidth > 0 && imageHeight > 0) resetToFit()
    }

    private fun resetToFit() {
        cancelFling()
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            scale = 1f
            tx = 0f
            ty = 0f
            applyTransform()
            return
        }
        val fit = min(
            width.toFloat() / imageWidth.toFloat(),
            height.toFloat() / imageHeight.toFloat()
        )
        // Always fill the tighter screen dimension, even when the source is small.
        baseScale = fit.coerceAtLeast(0.001f)
        minScale = baseScale * 0.4f
        maxScale = max(baseScale * 64f, 1.6f)
        scale = baseScale
        tx = (width - imageWidth * scale) / 2f
        ty = (height - imageHeight * scale) / 2f
        applyTransform()
        invalidate()
    }

    private fun clampOffsets() {
        val scaledW = imageWidth * scale
        val scaledH = imageHeight * scale
        tx = if (scaledW <= width) {
            (width - scaledW) / 2f
        } else {
            tx.coerceIn(width - scaledW, 0f)
        }
        ty = if (scaledH <= height) {
            (height - scaledH) / 2f
        } else {
            ty.coerceIn(height - scaledH, 0f)
        }
    }

    private fun applyTransform() {
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx, ty)
        val ratio = if (baseScale <= 0f) 1f else scale / baseScale
        // Panning also lands here; reporting the same ratio on every move made the
        // badge re-layout on each touch event and dropped frames.
        if (lastNotifiedRatio.isNaN() || abs(ratio - lastNotifiedRatio) > 0.01f) {
            lastNotifiedRatio = ratio
            onZoomChanged?.invoke(ratio)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentBitmap = bitmap
        val currentDrawable = drawable
        if (currentBitmap != null) {
            canvas.save()
            canvas.concat(matrix)
            canvas.drawBitmap(currentBitmap, 0f, 0f, paint)
            canvas.restore()
        } else if (currentDrawable != null) {
            currentDrawable.setBounds(0, 0, imageWidth, imageHeight)
            canvas.save()
            canvas.concat(matrix)
            currentDrawable.draw(canvas)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelFling()
                longPressTriggered = false
                edgeAccum = 0f
                edgeDirection = 0
                edgeTriggered = false
                if (scale > baseScale + 0.01f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && scale > baseScale + 0.01f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (longPressTriggered) {
                    longPressTriggered = false
                    onLongPressEnd?.invoke()
                }
            }
        }
        return true
    }

    override fun onDown(e: MotionEvent): Boolean = true

    override fun onShowPress(e: MotionEvent) = Unit

    override fun onSingleTapUp(e: MotionEvent): Boolean = true

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (imageWidth <= 0 || scale <= baseScale + 0.01f || scaleDetector.isInProgress) {
            return false
        }
        parent?.requestDisallowInterceptTouchEvent(true)

        // A light drag should cover more of a zoomed photo instead of the
        // content sticking 1:1 to the finger.
        val dx = distanceX * PAN_GAIN
        val dy = distanceY * PAN_GAIN
        val oldTx = tx
        val oldTy = ty
        val targetTx = tx - dx
        tx = targetTx
        ty -= dy
        clampOffsets()
        if (abs(tx - oldTx) > 0.01f || abs(ty - oldTy) > 0.01f) {
            applyTransform()
            invalidate()
        }

        // Paging must be a deliberate, mostly-horizontal drag: panning a zoomed photo
        // is full of diagonal deltas that used to trip the edge detector.
        if (abs(dx) <= abs(dy) * HORIZONTAL_DOMINANCE) {
            resetEdge()
            return true
        }
        val scaledW = imageWidth * scale
        val direction = if (dx > 0f) 1 else -1
        if (scaledW > width) {
            val overflow = if (direction > 0) {
                (width - scaledW) - targetTx
            } else {
                targetTx
            }
            // Paging thresholds stay finger-distance based: the pan gain must not
            // make an accidental page flip easier.
            if (overflow > 0f) {
                accumulateEdge(direction, overflow / PAN_GAIN, edgeThreshold)
            } else {
                resetEdge()
            }
        } else {
            // Narrower than the screen: no horizontal pan exists, so a long
            // horizontal drag can only mean "go to the neighbouring picture".
            accumulateEdge(direction, abs(distanceX), narrowPageThreshold)
        }
        return true
    }

    private fun resetEdge() {
        edgeAccum = 0f
        edgeDirection = 0
    }

    private fun accumulateEdge(direction: Int, amount: Float, threshold: Float) {
        if (edgeTriggered) return
        if (edgeDirection != direction) {
            edgeDirection = direction
            edgeAccum = 0f
        }
        edgeAccum += amount
        if (edgeAccum >= threshold) {
            edgeTriggered = true
            edgeAccum = 0f
            onEdgePage?.invoke(direction)
        }
    }

    override fun onLongPress(e: MotionEvent) {
        if (imageWidth <= 0 || onLongPressStart == null) return
        longPressTriggered = true
        onLongPressStart?.invoke()
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        if (imageWidth <= 0 || scale <= baseScale + 0.01f || scaleDetector.isInProgress) return false
        val scaledW = imageWidth * scale
        val scaledH = imageHeight * scale
        if (scaledW <= width && scaledH <= height) return false
        val currentX = tx.roundToInt()
        val currentY = ty.roundToInt()
        val minX: Int
        val maxX: Int
        if (scaledW > width) {
            minX = (width - scaledW).roundToInt()
            maxX = 0
        } else {
            minX = currentX
            maxX = currentX
        }
        val minY: Int
        val maxY: Int
        if (scaledH > height) {
            minY = (height - scaledH).roundToInt()
            maxY = 0
        } else {
            minY = currentY
            maxY = currentY
        }
        flingScroller.fling(
            currentX,
            currentY,
            (-velocityX).roundToInt(),
            (-velocityY).roundToInt(),
            minX,
            maxX,
            minY,
            maxY
        )
        removeCallbacks(flingRunnable)
        postOnAnimation(flingRunnable)
        return true
    }

    private fun cancelFling() {
        flingScroller.abortAnimation()
        removeCallbacks(flingRunnable)
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (imageWidth <= 0) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        if (abs(scale - baseScale) > baseScale * 0.02f) {
            // Any zoom level returns to fit with one tap.
            animateTo(baseScale, (width - imageWidth * baseScale) / 2f,
                (height - imageHeight * baseScale) / 2f)
        } else {
            val targetScale = (baseScale * 2.5f).coerceIn(baseScale, maxScale)
            val imageX = (e.x - tx) / scale
            val imageY = (e.y - ty) / scale
            var targetTx = e.x - imageX * targetScale
            var targetTy = e.y - imageY * targetScale
            val scaledW = imageWidth * targetScale
            val scaledH = imageHeight * targetScale
            targetTx = if (scaledW <= width) (width - scaledW) / 2f
            else targetTx.coerceIn(width - scaledW, 0f)
            targetTy = if (scaledH <= height) (height - scaledH) / 2f
            else targetTy.coerceIn(height - scaledH, 0f)
            animateTo(targetScale, targetTx, targetTy)
        }
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        performClick()
        if (!longPressTriggered) onTap?.invoke(e.x, e.y)
        return true
    }

    fun isAtFit(): Boolean = abs(scale - baseScale) < 0.01f

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun animateTo(targetScale: Float, targetTx: Float, targetTy: Float) {
        animator?.cancel()
        cancelFling()
        val startScale = scale
        val startTx = tx
        val startTy = ty
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (220 / context.swiftPic.prefs.animationScale).toLong().coerceAtLeast(25)
            interpolator = DecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                val fraction = valueAnimator.animatedValue as Float
                scale = startScale + (targetScale - startScale) * fraction
                tx = startTx + (targetTx - startTx) * fraction
                ty = startTy + (targetTy - startTy) * fraction
                clampOffsets()
                applyTransform()
                invalidate()
            }
        }
        animator = animation
        animation.start()
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        if (imageWidth <= 0) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        animator?.cancel()
        cancelFling()
        return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        if (imageWidth <= 0) return false
        val newScale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
        if (abs(newScale - scale) < 0.0001f) return true
        val focusX = detector.focusX
        val focusY = detector.focusY
        val imageX = (focusX - tx) / scale
        val imageY = (focusY - ty) / scale
        scale = newScale
        tx = focusX - imageX * scale
        ty = focusY - imageY * scale
        clampOffsets()
        applyTransform()
        invalidate()
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) = Unit

    override fun invalidateDrawable(who: Drawable) {
        invalidate()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, whenTime: Long) {
        val delay = (whenTime - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        postDelayed(what, delay)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        removeCallbacks(what)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
        cancelFling()
    }

    private companion object {
        const val HORIZONTAL_DOMINANCE = 1.5f
        const val PAN_GAIN = 2f
    }
}
