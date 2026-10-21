package com.qoder.swiftpic.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import com.qoder.swiftpic.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object WallpaperBackground {

    private const val TAG = "SwiftPicBg"
    private const val CUSTOM_FILE = "custom_background.jpg"
    private const val CUSTOM_MAX_DIMENSION = 1920

    private val cache = LruCache<String, Bitmap>(1)

    fun customFile(context: Context): File = File(context.filesDir, CUSTOM_FILE)

    /** Changes whenever the preference or the picked image changes. */
    fun stamp(context: Context): String {
        val prefs = AppPrefs(context)
        val file = customFile(context)
        val stamp = if (file.exists()) file.length().toString() else "none"
        return "${prefs.backgroundMode.ordinal}|$stamp|${prefs.backgroundBlur}"
    }

    /** Copies the picked image into app storage so the background survives SAF revocation. */
    fun setCustomImage(context: Context, uri: Uri): Boolean {
        return runCatching {
            val bitmap = decodeScaled(context, uri, CUSTOM_MAX_DIMENSION) ?: return false
            val file = customFile(context)
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            bitmap.recycle()
            cache.evictAll()
            Log.d(TAG, "custom background stored ${file.length()} bytes")
            true
        }.onFailure { Log.w(TAG, "setCustomImage failed", it) }.getOrDefault(false)
    }

    private fun decodeScaled(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width.coerceAtLeast(1)
            val height = info.size.height.coerceAtLeast(1)
            val scale = min(1f, maxDimension.toFloat() / max(width, height))
            if (scale < 1f) {
                decoder.setTargetSize(
                    max(1, (width * scale).roundToInt()),
                    max(1, (height * scale).roundToInt())
                )
            }
        }
    }

    fun load(context: Context, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val key = "${stamp(context)}|$width" + "x$height"
        cache.get(key)?.let { return it }
        return runCatching {
            val mode = AppPrefs(context).backgroundMode
            val bitmap = when (mode) {
                BackgroundMode.CUSTOM -> loadCustom(context, width, height)
                    ?: readSystemWallpaper(context, width, height)
                BackgroundMode.WALLPAPER -> readSystemWallpaper(context, width, height)
                BackgroundMode.THEME -> null
            }
            if (bitmap != null) cache.put(key, bitmap)
            bitmap
        }.getOrNull()
    }

    private fun loadCustom(context: Context, width: Int, height: Int): Bitmap? {
        val file = customFile(context)
        if (!file.exists()) return null
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return renderBitmap(source, width, height)
    }

    private fun readSystemWallpaper(context: Context, width: Int, height: Int): Bitmap? {
        val manager = WallpaperManager.getInstance(context)
        val source = readWallpaper(manager)
        if (source is Drawable) {
            return renderDrawable(source, width, height)
        }
        if (source is Bitmap) {
            return renderBitmap(source, width, height)
        }
        // WallpaperManager still gates the wallpaper behind READ_EXTERNAL_STORAGE, which
        // is why this app targets SDK 32; if the user denied storage, show the fallback.
        val fallback = BitmapFactory.decodeResource(context.resources, R.drawable.wallpaper_bg)
        Log.d(TAG, "wallpaper unavailable, using bundled fallback")
        return fallback?.let { renderBitmap(it, width, height) }
    }

    private fun readWallpaper(manager: WallpaperManager): Any? {
        runCatching { manager.getDrawable() }
            .onFailure { Log.w(TAG, "getDrawable failed", it) }
            .getOrNull()
            ?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
            ?.let {
                Log.d(TAG, "wallpaper drawable ${it.intrinsicWidth}x${it.intrinsicHeight}")
                return it
            }
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { manager.peekDrawable() }
                .onFailure { Log.w(TAG, "peekDrawable failed", it) }
                .getOrNull()
                ?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
                ?.let {
                    Log.d(TAG, "wallpaper peekDrawable ${it.intrinsicWidth}x${it.intrinsicHeight}")
                    return it
                }
        }
        return null
    }

    private fun renderDrawable(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = max(
            width.toFloat() / drawable.intrinsicWidth,
            height.toFloat() / drawable.intrinsicHeight
        )
        val drawWidth = (drawable.intrinsicWidth * scale).roundToInt()
        val drawHeight = (drawable.intrinsicHeight * scale).roundToInt()
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        drawable.setBounds(left, top, left + drawWidth, top + drawHeight)
        drawable.draw(canvas)
        return bitmap
    }

    private fun renderBitmap(source: Bitmap, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawWidth = (source.width * scale).roundToInt()
        val drawHeight = (source.height * scale).roundToInt()
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        val dest = Rect(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(source, null, dest, null)
        return bitmap
    }
}
