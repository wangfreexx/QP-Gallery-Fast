package com.qoder.swiftpic.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

object CheckerPattern {

    private const val TILE_DP = 8f

    private val cache = HashMap<String, Bitmap>()

    @Synchronized
    fun bitmap(context: Context, dark: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val tile = max(4, (TILE_DP * density).roundToInt())
        val key = "$tile|$dark"
        cache[key]?.let { return it }

        val size = tile * 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base = if (dark) 0xFF1B1F25.toInt() else 0xFFE7EAEF.toInt()
        val alternate = if (dark) 0xFF242932.toInt() else 0xFFD5DAE2.toInt()
        canvas.drawColor(base)
        val paint = Paint().apply { color = alternate }
        canvas.drawRect(0f, 0f, tile.toFloat(), tile.toFloat(), paint)
        canvas.drawRect(tile.toFloat(), tile.toFloat(), size.toFloat(), size.toFloat(), paint)
        cache[key] = bitmap
        return bitmap
    }

    fun drawable(context: Context, dark: Boolean): Drawable {
        return BitmapDrawable(context.resources, bitmap(context, dark)).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            isFilterBitmap = false
        }
    }
}
