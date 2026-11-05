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
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object WallpaperBackground {

    private const val TAG = "SwiftPicBg"
    private const val CUSTOM_FILE = "custom_background.jpg"
    private const val CUSTOM_MAX_DIMENSION = 1920
    private const val MAX_RENDER_DIMENSION = 4096

    private val cache = LruCache<String, Bitmap>(1)

    fun customFile(context: Context): File = File(context.filesDir, CUSTOM_FILE)

    /** Changes whenever the preference, the picked image or the *system* wallpaper changes. */
    fun stamp(context: Context): String {
        val prefs = AppPrefs(context)
        val file = customFile(context)
        // Length alone is not a content fingerprint: two different images can
        // have the same byte count. lastModified disambiguates them.
        val imageStamp = if (file.exists()) "${file.lastModified()}-${file.length()}" else "none"
        // getWallpaperId() is the only reliable signal that the user changed
        // the system wallpaper while our app was alive. Without it the LruCache
        // would keep serving the old wallpaper bitmap until the process died.
        val wallpaperId = try {
            WallpaperManager.getInstance(context)
                .getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (e: Exception) {
            Log.w(TAG, "wallpaper id unavailable", e)
            -1
        }
        return "${prefs.backgroundMode.ordinal}|$imageStamp|${prefs.backgroundBlur}|$wallpaperId"
    }

    /** Copies the picked image into app storage so the background survives SAF revocation. */
    fun setCustomImage(context: Context, uri: Uri): Boolean {
        val bitmap = try {
            decodeScaled(context, uri, CUSTOM_MAX_DIMENSION)
        } catch (e: Exception) {
            Log.w(TAG, "decode custom background failed", e)
            null
        } ?: return false

        val file = customFile(context)
        val tmp = File(context.filesDir, "$CUSTOM_FILE.tmp")
        val backup = File(context.filesDir, "$CUSTOM_FILE.bak")
        return try {
            val ok = FileOutputStream(tmp).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            if (!ok || tmp.length() <= 0L) throw IOException("compress failed")
            if (backup.exists()) backup.delete()
            // Move the valid old file aside instead of deleting it, so a failed
            // publish can be rolled back without losing the user's background.
            if (file.exists() && !file.renameTo(backup)) {
                throw IOException("cannot move old background aside")
            }
            if (!tmp.renameTo(file)) {
                if (backup.exists()) backup.renameTo(file)
                throw IOException("cannot publish new background")
            }
            backup.delete()
            cache.evictAll()
            Log.d(TAG, "custom background stored ${file.length()} bytes")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setCustomImage failed", e)
            tmp.delete()
            false
        } finally {
            bitmap.recycle()
        }
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
        // Clamp pathological sizes (external displays, split screen) before
        // allocating an ARGB_8888 bitmap.
        val w = width.coerceAtMost(MAX_RENDER_DIMENSION)
        val h = height.coerceAtMost(MAX_RENDER_DIMENSION)
        val key = "${stamp(context)}|$w" + "x$h"
        cache.get(key)?.let { return it }
        return try {
            val mode = AppPrefs(context).backgroundMode
            Log.d(TAG, "load background mode=$mode size=${w}x$h key=$key")
            val bitmap = when (mode) {
                BackgroundMode.CUSTOM -> loadCustom(context, w, h)
                    ?: readSystemWallpaper(context, w, h)
                BackgroundMode.WALLPAPER -> readSystemWallpaper(context, w, h)
                BackgroundMode.THEME -> null
            }
            if (bitmap != null) {
                cache.put(key, bitmap)
                Log.d(TAG, "background ready from $mode")
            } else {
                Log.w(TAG, "no background bitmap for mode=$mode")
            }
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "load background failed", e)
            null
        }
    }

    private fun loadCustom(context: Context, width: Int, height: Int): Bitmap? {
        val file = customFile(context)
        if (!file.exists()) return null
        val source = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "decode custom background file failed", e)
            null
        } ?: return null
        return try {
            renderBitmap(source, width, height)
        } finally {
            source.recycle()
        }
    }

    private fun readSystemWallpaper(context: Context, width: Int, height: Int): Bitmap? {
        val manager = WallpaperManager.getInstance(context)
        val source = readWallpaper(manager)
        if (source is Drawable) {
            Log.d(TAG, "using system wallpaper drawable")
            return renderDrawable(source, width, height)
        }
        if (source is Bitmap) {
            Log.d(TAG, "using system wallpaper bitmap")
            return renderBitmap(source, width, height)
        }
        // WallpaperManager still gates the wallpaper behind READ_EXTERNAL_STORAGE, which
        // is why this app targets SDK 32; if the user denied storage, show the fallback.
        val fallback = try {
            BitmapFactory.decodeResource(context.resources, R.drawable.wallpaper_bg)
        } catch (e: Exception) {
            Log.w(TAG, "decode fallback failed", e)
            null
        }
        Log.d(TAG, "wallpaper unavailable, using bundled fallback")
        return fallback?.let {
            try {
                renderBitmap(it, width, height)
            } finally {
                it.recycle()
            }
        }
    }

    private fun readWallpaper(manager: WallpaperManager): Any? {
        try {
            val drawable = manager.getDrawable()
            if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                Log.d(TAG, "wallpaper drawable ${drawable.intrinsicWidth}x${drawable.intrinsicHeight}")
                return drawable
            }
        } catch (e: Exception) {
            Log.w(TAG, "getDrawable failed", e)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val drawable = manager.peekDrawable()
                if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    Log.d(TAG, "wallpaper peekDrawable ${drawable.intrinsicWidth}x${drawable.intrinsicHeight}")
                    return drawable
                }
            } catch (e: Exception) {
                Log.w(TAG, "peekDrawable failed", e)
            }
        }
        return null
    }

    private fun renderDrawable(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = max(
            width.toFloat() / drawable.intrinsicWidth.coerceAtLeast(1),
            height.toFloat() / drawable.intrinsicHeight.coerceAtLeast(1)
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
        val scale = max(
            width.toFloat() / source.width.coerceAtLeast(1),
            height.toFloat() / source.height.coerceAtLeast(1)
        )
        val drawWidth = (source.width * scale).roundToInt()
        val drawHeight = (source.height * scale).roundToInt()
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        val dest = Rect(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(source, null, dest, null)
        return bitmap
    }

    fun evictAll() {
        cache.evictAll()
    }
}
