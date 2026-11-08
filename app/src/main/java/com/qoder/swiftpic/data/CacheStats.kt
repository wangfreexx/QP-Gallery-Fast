package com.qoder.swiftpic.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Byte counters for bitmap caches that live outside the two LruCaches
 * (thumbnail / full image), so the settings screen can report one honest
 * number instead of only the LruCache totals.
 *
 * Deliberately excluded: the wallpaper bitmap and gallery_snapshot.json live
 * in filesDir and are user data that "clear cache" must never delete.
 * GIF mmap buffers are off-heap file mappings, not Java heap cache.
 */
object CacheStats {

    private const val TAG = "SwiftPicCacheStats"
    private val regionTiles = AtomicLong(0L)
    private val gifFrames = AtomicLong(0L)

    fun swapRegionTile(old: Bitmap?, new: Bitmap?) {
        val delta = (new?.allocationByteCount?.toLong() ?: 0L) -
            (old?.allocationByteCount?.toLong() ?: 0L)
        val total = regionTiles.addAndGet(delta)
        if (total < 0L) regionTiles.set(0L)
    }

    fun addGifFrame(bitmap: Bitmap) {
        gifFrames.addAndGet(bitmap.allocationByteCount.toLong())
    }

    fun removeGifFrame(bitmap: Bitmap) {
        val total = gifFrames.addAndGet(-bitmap.allocationByteCount.toLong())
        if (total < 0L) gifFrames.set(0L)
    }

    /** Region-decode tiles + GIF frame bitmaps (all Java heap). */
    fun transientBytes(): Long = (regionTiles.get() + gifFrames.get()).coerceAtLeast(0L)

    /** Everything under cacheDir; filesDir holds user data, not cache. */
    fun diskCacheBytes(context: Context): Long = try {
        val dir = context.cacheDir
        if (!dir.isDirectory) 0L
        else dir.walk().filter { it.isFile }.sumOf { it.length() }
    } catch (e: Exception) {
        Log.w(TAG, "disk cache size failed", e)
        0L
    }

    fun clearDiskCache(context: Context) {
        try {
            val dir = context.cacheDir
            if (dir.isDirectory) dir.deleteRecursively()
            dir.mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "clear disk cache failed", e)
        }
    }
}
