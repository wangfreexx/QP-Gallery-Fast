package com.qoder.swiftpic.ui.viewer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.qoder.swiftpic.data.CacheStats
import com.qoder.swiftpic.data.TransparencyBackdrop
import com.qoder.swiftpic.swiftPic
import com.qoder.swiftpic.ui.widget.CheckerPattern
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
    private val checkerPaint = Paint().apply {
        shader = BitmapShader(
            CheckerPattern.bitmap(context, dark = true),
            Shader.TileMode.REPEAT,
            Shader.TileMode.REPEAT
        )
        isFilterBitmap = false
        isAntiAlias = false
    }
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
            if (!flingScroller.computeScrollOffset()) {
                Log.d(TAG, "fling stop ty=${ty.toInt()}")
                return
            }
            val newTx = flingScroller.currX.toFloat()
            val newTy = flingScroller.currY.toFloat()
            if (abs(newTx - tx) >= 0.01f || abs(newTy - ty) >= 0.01f) {
                tx = newTx
                ty = newTy
                applyTransform()
                invalidate()
            }
            if (!flingScroller.isFinished) {
                postOnAnimation(this)
            } else {
                Log.d(TAG, "fling done ty=${ty.toInt()}")
            }
        }
    }

    private var longPressTriggered = false
    private var animator: ValueAnimator? = null

    var onTap: ((Float, Float) -> Unit)? = null
    /** Returns false for the left/right paging strips, where a double tap must not zoom. */
    var doubleTapAllowed: ((Float) -> Boolean)? = null
    var onLongPressStart: (() -> Unit)? = null
    var onLongPressEnd: (() -> Unit)? = null
    var onEdgePage: ((Int) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null
    /** Requests a high-resolution tile for the visible source rectangle. */
    var onVisibleRegionChanged: ((Rect, Int, Int) -> Unit)? = null

    private var regionTile: Bitmap? = null
    private var regionRect: Rect? = null
    private val regionRequest = Runnable { requestVisibleRegion() }
    /** While the user is touching / flinging / animating, only the continuous base bitmap is drawn. */
    private var interacting = false
    private val interactionEnd = object : Runnable {
        override fun run() {
            if (!flingScroller.isFinished) {
                postDelayed(this, REGION_SETTLE_MS)
                return
            }
            interacting = false
            Log.d(TAG, "interaction settled, refresh region")
            scheduleRegionUpdate()
        }
    }

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
        cancelFling()
        if (bitmap === newBitmap && drawable == null) return
        stopDrawable()
        setRegionTile(null, null)
        removeCallbacks(regionRequest)
        removeCallbacks(interactionEnd)
        interacting = false
        val dimensionsChanged = newBitmap.width != imageWidth || newBitmap.height != imageHeight
        bitmap = newBitmap
        drawable = null
        imageWidth = newBitmap.width
        imageHeight = newBitmap.height
        if (dimensionsChanged) resetToFit() else applyTransform()
        invalidate()
    }

    fun setFrameBitmap(newBitmap: Bitmap) {
        // 与 setBitmap 一致：先停掉可能残留的动画 drawable，避免帧图与动画叠画。
        stopDrawable()
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
        setRegionTile(null, null)
        removeCallbacks(regionRequest)
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
        cancelFling()
        stopDrawable()
        setRegionTile(null, null)
        removeCallbacks(regionRequest)
        removeCallbacks(interactionEnd)
        interacting = false
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
        scheduleRegionUpdate()
    }

    /**
     * Replaces the high-resolution tile drawn on top of the base bitmap.
     * The previous tile is recycled here so holders never leak region bitmaps.
     */
    fun setRegionTile(bitmap: Bitmap?, rect: Rect?) {
        val previous = regionTile
        regionTile = bitmap
        regionRect = if (bitmap != null) rect else null
        CacheStats.swapRegionTile(previous, bitmap)
        if (previous !== bitmap) previous?.recycle()
        if (bitmap == null && previous != null) Log.d(TAG, "region tile cleared")
        invalidate()
    }

    /** Debounced: pinch and pan events otherwise fire dozens of decodes per second. */
    private fun scheduleRegionUpdate() {
        if (onVisibleRegionChanged == null) return
        removeCallbacks(regionRequest)
        val ratio = if (baseScale > 0f) scale / baseScale else 1f
        if (ratio <= REGION_THRESHOLD) {
            // Dropping the stale tile avoids a sharp/blurry patch lingering
            // while the user zooms back out.
            if (regionTile != null) setRegionTile(null, null)
            return
        }
        // No tile churn while a finger is down: panning would otherwise keep
        // moving the viewport out of the tile and cause visible seams / flicker.
        if (interacting) return
        postDelayed(regionRequest, REGION_DEBOUNCE_MS)
    }

    private fun requestVisibleRegion() {
        val visible = visibleSourceRect() ?: return
        // Overscan a little so short pans do not immediately expose the low-res base.
        val padX = visible.width() * REGION_PADDING
        val padY = visible.height() * REGION_PADDING
        val rect = Rect(
            (visible.left - padX).coerceAtLeast(0f).toInt(),
            (visible.top - padY).coerceAtLeast(0f).toInt(),
            (visible.right + padX).coerceAtMost(imageWidth.toFloat()).toInt(),
            (visible.bottom + padY).coerceAtMost(imageHeight.toFloat()).toInt()
        )
        onVisibleRegionChanged?.invoke(rect, width, height)
    }

    /** The visible part of the source image, in source-image coordinates. */
    private fun visibleSourceRect(): Rect? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || imageWidth <= 0 || imageHeight <= 0 || scale <= 0f) return null
        val left = ((0f - tx) / scale).coerceIn(0f, imageWidth.toFloat())
        val top = ((0f - ty) / scale).coerceIn(0f, imageHeight.toFloat())
        val right = ((w.toFloat() - tx) / scale).coerceIn(0f, imageWidth.toFloat())
        val bottom = ((h.toFloat() - ty) / scale).coerceIn(0f, imageHeight.toFloat())
        if (right <= left || bottom <= top) return null
        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth > 0 && imageHeight > 0 &&
            context.swiftPic.prefs.transparencyBackdrop == TransparencyBackdrop.CHECKER
        ) {
            val left = tx
            val top = ty
            canvas.drawRect(
                left,
                top,
                left + imageWidth * scale,
                top + imageHeight * scale,
                checkerPaint
            )
        }
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

        // High-resolution region layer: only drawn once the base bitmap is no
        // longer detailed enough for the current zoom level.
        val tile = regionTile
        val rect = regionRect
        val zoomRatio = if (baseScale > 0f) scale / baseScale else 1f
        val visible = visibleSourceRect()
        // A tile only covers the region that was visible when it was requested.
        // If the viewport grew (zooming out) or moved away (panning), drawing it
        // would leave a sharp/blurry seam and look like an incomplete load.
        val fullyCovers = visible != null && rect != null &&
            rect.contains(visible.left, visible.top, visible.right, visible.bottom)
        if (tile != null && !tile.isRecycled && rect != null &&
            zoomRatio > REGION_THRESHOLD && !interacting && fullyCovers
        ) {
            val dst = RectF(
                tx + rect.left * scale,
                ty + rect.top * scale,
                tx + rect.right * scale,
                ty + rect.bottom * scale
            )
            canvas.drawBitmap(tile, null, dst, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 无图时不要把事件吃掉，交给父级 ViewPager2 处理翻页。
        if (imageWidth <= 0) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelFling()
                interacting = true
                removeCallbacks(interactionEnd)
                removeCallbacks(regionRequest)
                longPressTriggered = false
                edgeAccum = 0f
                edgeDirection = 0
                edgeTriggered = false
                if (scale > baseScale + 0.01f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 双指变单指时，剩余手指的位移不应继续累积边缘翻页距离。
                edgeAccum = 0f
                edgeDirection = 0
                if (longPressTriggered) {
                    longPressTriggered = false
                    onLongPressEnd?.invoke()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                interacting = true
                if (!scaleDetector.isInProgress && scale > baseScale + 0.01f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (longPressTriggered) {
                    longPressTriggered = false
                    onLongPressEnd?.invoke()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                removeCallbacks(interactionEnd)
                postDelayed(interactionEnd, REGION_SETTLE_MS)
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
        // velocityX/Y follow the finger; the content moves with the finger, so the
        // scroller gets the same sign (negating it flipped the post-release glide).
        Log.d(TAG, "fling vy=${velocityY.toInt()} ty0=${ty.toInt()} y=$minY..$maxY")
        flingScroller.fling(
            currentX,
            currentY,
            velocityX.roundToInt(),
            velocityY.roundToInt(),
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
        val fraction = if (width > 0) e.x / width else 0.5f
        if (doubleTapAllowed?.invoke(fraction) == false) {
            // Both taps live in a paging strip: honor them as two taps instead of
            // letting GestureDetector swallow one as a "double tap".
            onTap?.invoke(e.x, e.y)
            onTap?.invoke(e.x, e.y)
            return true
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        if (abs(scale - baseScale) > baseScale * 0.02f) {
            // Any zoom level returns to fit with one tap.
            animateTo(baseScale, (width - imageWidth * baseScale) / 2f,
                (height - imageHeight * baseScale) / 2f)
        } else {
            // One double tap fills the screen: take the larger of the two fit
            // ratios so neither dimension is letterboxed, instead of stepping
            // by a fixed 2.5x which left tall/wide images still under-filled.
            val fillScale = max(
                width.toFloat() / imageWidth.toFloat(),
                height.toFloat() / imageHeight.toFloat()
            )
            // Only when the aspect ratios match (fill == fit) do we need a
            // synthetic step so a double tap still visibly zooms.
            val targetScale = (if (fillScale > baseScale * 1.05f) {
                fillScale
            } else {
                baseScale * 2f
            }).coerceIn(baseScale, maxScale)
            Log.d(TAG, "double tap fill=$fillScale target=$targetScale base=$baseScale")
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
        interacting = true
        removeCallbacks(interactionEnd)
        removeCallbacks(regionRequest)
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
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(anim: Animator) {
                    interacting = false
                    scheduleRegionUpdate()
                }
                override fun onAnimationCancel(anim: Animator) {
                    interacting = false
                }
            })
        }
        animator = animation
        animation.start()
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        if (imageWidth <= 0) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        animator?.cancel()
        cancelFling()
        interacting = true
        removeCallbacks(interactionEnd)
        removeCallbacks(regionRequest)
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
        removeCallbacks(regionRequest)
        removeCallbacks(interactionEnd)
        interacting = false
        // A detached page may never go through clearImage(); release the tile
        // here or every paged-in photo leaks a full-resolution region bitmap.
        setRegionTile(null, null)
    }

    private companion object {
        const val TAG = "SwiftPicZoom"
        const val HORIZONTAL_DOMINANCE = 1.5f
        const val PAN_GAIN = 1.4f
        const val REGION_THRESHOLD = 1.4f
        const val REGION_DEBOUNCE_MS = 90L
        const val REGION_SETTLE_MS = 120L
        const val REGION_PADDING = 0.35f
    }
}
