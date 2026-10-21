package com.qoder.swiftpic.ui.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val imageBox = RectF()
    private val crop = RectF()
    private var aspect: Float? = null
    private var active = Handle.NONE
    private var lastX = 0f
    private var lastY = 0f

    private val density = resources.displayMetrics.density
    private val minSize = 48f * density
    private val touchSlop = 28f * density
    private val handleLength = 18f * density
    private val borderWidth = 2f * density

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }

    private enum class Handle { NONE, MOVE, TL, TR, BL, BR }

    fun setImageBox(left: Float, top: Float, right: Float, bottom: Float) {
        if (right - left < 2f || bottom - top < 2f) return
        imageBox.set(left, top, right, bottom)
        crop.set(imageBox)
        applyAspectCentered()
        invalidate()
    }

    fun setAspectRatio(ratio: Float?) {
        aspect = ratio
        if (imageBox.width() <= 0f) return
        crop.set(imageBox)
        applyAspectCentered()
        invalidate()
    }

    fun resetCrop() {
        if (imageBox.width() <= 0f) return
        crop.set(imageBox)
        applyAspectCentered()
        invalidate()
    }

    fun cropInBitmap(bitmapWidth: Int, bitmapHeight: Int): Rect {
        if (imageBox.width() <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return Rect(0, 0, bitmapWidth, bitmapHeight)
        }
        val sx = bitmapWidth / imageBox.width()
        val sy = bitmapHeight / imageBox.height()
        val left = ((crop.left - imageBox.left) * sx).toInt().coerceIn(0, bitmapWidth - 1)
        val top = ((crop.top - imageBox.top) * sy).toInt().coerceIn(0, bitmapHeight - 1)
        val right = ((crop.right - imageBox.left) * sx).toInt().coerceIn(left + 1, bitmapWidth)
        val bottom = ((crop.bottom - imageBox.top) * sy).toInt().coerceIn(top + 1, bitmapHeight)
        return Rect(left, top, right, bottom)
    }

    private fun applyAspectCentered() {
        val ratio = aspect ?: return
        var width = imageBox.width()
        var height = width / ratio
        if (height > imageBox.height()) {
            height = imageBox.height()
            width = height * ratio
        }
        val cx = imageBox.centerX()
        val cy = imageBox.centerY()
        crop.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageBox.width() <= 0f) return
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(imageBox, Path.Direction.CW)
            addRect(crop, Path.Direction.CW)
        }
        canvas.drawPath(path, dimPaint)
        canvas.drawRect(crop, borderPaint)

        val thirdW = crop.width() / 3f
        val thirdH = crop.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(
                crop.left + thirdW * i, crop.top,
                crop.left + thirdW * i, crop.bottom, gridPaint
            )
            canvas.drawLine(
                crop.left, crop.top + thirdH * i,
                crop.right, crop.top + thirdH * i, gridPaint
            )
        }

        drawCorner(canvas, crop.left, crop.top, 1f, 1f)
        drawCorner(canvas, crop.right, crop.top, -1f, 1f)
        drawCorner(canvas, crop.left, crop.bottom, 1f, -1f)
        drawCorner(canvas, crop.right, crop.bottom, -1f, -1f)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        canvas.drawLine(x, y, x + handleLength * dx, y, handlePaint)
        canvas.drawLine(x, y, x, y + handleLength * dy, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageBox.width() <= 0f) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = hitHandle(x, y)
                if (active == Handle.NONE && crop.contains(x, y)) active = Handle.MOVE
                if (active == Handle.NONE) return false
                lastX = x
                lastY = y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (active == Handle.NONE) return false
                when (active) {
                    Handle.MOVE -> moveCrop(x - lastX, y - lastY)
                    else -> resizeCrop(active, x, y)
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = Handle.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun hitHandle(x: Float, y: Float): Handle {
        val corners = arrayOf(
            Triple(Handle.TL, crop.left, crop.top),
            Triple(Handle.TR, crop.right, crop.top),
            Triple(Handle.BL, crop.left, crop.bottom),
            Triple(Handle.BR, crop.right, crop.bottom)
        )
        for ((handle, cx, cy) in corners) {
            if (abs(x - cx) <= touchSlop && abs(y - cy) <= touchSlop) return handle
        }
        return Handle.NONE
    }

    private fun moveCrop(dx: Float, dy: Float) {
        var offsetX = dx
        var offsetY = dy
        if (crop.left + offsetX < imageBox.left) offsetX = imageBox.left - crop.left
        if (crop.right + offsetX > imageBox.right) offsetX = imageBox.right - crop.right
        if (crop.top + offsetY < imageBox.top) offsetY = imageBox.top - crop.top
        if (crop.bottom + offsetY > imageBox.bottom) offsetY = imageBox.bottom - crop.bottom
        crop.offset(offsetX, offsetY)
    }

    private fun resizeCrop(handle: Handle, x: Float, y: Float) {
        val anchorX = if (handle == Handle.TR || handle == Handle.BR) crop.left else crop.right
        val anchorY = if (handle == Handle.BL || handle == Handle.BR) crop.top else crop.bottom
        val signX = if (handle == Handle.TR || handle == Handle.BR) 1f else -1f
        val signY = if (handle == Handle.BL || handle == Handle.BR) 1f else -1f

        val maxWidth = if (signX > 0) imageBox.right - anchorX else anchorX - imageBox.left
        val maxHeight = if (signY > 0) imageBox.bottom - anchorY else anchorY - imageBox.top
        val ratio = aspect

        var width: Float
        var height: Float
        if (ratio != null) {
            width = abs(x - anchorX).coerceAtLeast(minSize)
            height = width / ratio
            val rawHeight = abs(y - anchorY)
            if (rawHeight > height) {
                height = rawHeight
                width = height * ratio
            }
            val scale = min(1f, min(maxWidth / width, maxHeight / height))
            width *= scale
            height *= scale
            if (width < minSize || height < minSize) {
                height = minSize.coerceAtMost(maxHeight)
                width = height * ratio
                if (width > maxWidth) {
                    width = maxWidth
                    height = width / ratio
                }
            }
        } else {
            width = abs(x - anchorX).coerceAtLeast(minSize)
            height = abs(y - anchorY).coerceAtLeast(minSize)
            width = width.coerceAtMost(maxWidth)
            height = height.coerceAtMost(maxHeight)
        }

        val left = if (signX > 0) anchorX else anchorX - width
        val top = if (signY > 0) anchorY else anchorY - height
        crop.set(left, top, left + width, top + height)
    }
}
