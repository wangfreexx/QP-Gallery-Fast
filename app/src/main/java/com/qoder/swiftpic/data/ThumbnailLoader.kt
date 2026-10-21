package com.qoder.swiftpic.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class ThumbnailLoader(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val semaphore = Semaphore(4)

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun peek(photo: Photo, sizePx: Int = DEFAULT_SIZE): Bitmap? {
        return cache.get(key(photo, sizePx))
    }

    suspend fun load(photo: Photo, sizePx: Int = DEFAULT_SIZE): Bitmap? {
        cache.get(key(photo, sizePx))?.let { return it }
        return withContext(Dispatchers.IO) {
            semaphore.withPermit {
                cache.get(key(photo, sizePx))?.let { return@withPermit it }
                val bitmap = runCatching { loadInternal(photo, sizePx) }
                    .onFailure { Log.w(TAG, "thumbnail failed for ${photo.uri}", it) }
                    .getOrNull()
                if (bitmap != null) {
                    cache.put(key(photo, sizePx), bitmap)
                }
                bitmap
            }
        }
    }

    private fun loadInternal(photo: Photo, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                return resolver.loadThumbnail(photo.uri, Size(sizePx, sizePx), null)
            }.onFailure { Log.d(TAG, "loadThumbnail failed, falling back", it) }
        } else {
            @Suppress("DEPRECATION")
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            @Suppress("DEPRECATION")
            val thumb = MediaStore.Images.Thumbnails.getThumbnail(
                resolver,
                photo.id,
                MediaStore.Images.Thumbnails.MINI_KIND,
                options
            )
            if (thumb != null) return thumb
        }
        return decodeFallback(photo, sizePx)
    }

    private fun decodeFallback(photo: Photo, sizePx: Int): Bitmap? {
        val source = ImageDecoder.createSource(resolver, photo.uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            if (w > 0 && h > 0) {
                val scale = sizePx.toFloat() / max(w, h).toFloat()
                if (scale < 1f) {
                    decoder.setTargetSize(
                        max(1, (w * scale).roundToInt()),
                        max(1, (h * scale).roundToInt())
                    )
                }
            }
        }
    }

    fun evictAll() {
        cache.evictAll()
    }

    fun memoryCacheBytes(): Int = cache.size()

    private fun key(photo: Photo, sizePx: Int): String =
        "${photo.uri}|${photo.dateModifiedMs}|$sizePx"

    private companion object {
        const val TAG = "SwiftPicThumb"
        const val DEFAULT_SIZE = 320

        fun cacheBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return minOf(heap / 8, 64L * 1024 * 1024).toInt().coerceAtLeast(8 * 1024 * 1024)
        }
    }
}
