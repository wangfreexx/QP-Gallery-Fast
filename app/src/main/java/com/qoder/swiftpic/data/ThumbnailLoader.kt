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

    /** Whether the source can carry alpha; filled on the IO thread. */
    private val alphaFlags = LruCache<String, Boolean>(4096)

    fun peek(photo: Photo, sizePx: Int = DEFAULT_SIZE, preserveAlpha: Boolean = false): Bitmap? {
        val alpha = knownAlpha(photo, preserveAlpha) ?: return null
        return cache.get(key(photo, sizePx, alpha))
    }

    suspend fun load(
        photo: Photo,
        sizePx: Int = DEFAULT_SIZE,
        preserveAlpha: Boolean = false
    ): Bitmap? {
        val known = knownAlpha(photo, preserveAlpha)
        if (known != null) {
            cache.get(key(photo, sizePx, known))?.let { return it }
        }
        return withContext(Dispatchers.IO) {
            semaphore.withPermit {
                val alpha = known ?: detectAlpha(photo, preserveAlpha)
                cache.get(key(photo, sizePx, alpha))?.let { return@withPermit it }
                val bitmap = try {
                    loadInternal(photo, sizePx, alpha)
                } catch (e: Exception) {
                    Log.w(TAG, "thumbnail failed for ${photo.uri}", e)
                    null
                }
                if (bitmap != null) {
                    cache.put(key(photo, sizePx, alpha), bitmap)
                }
                bitmap
            }
        }
    }

    /**
     * Alpha detection is not limited to PNG: transparent WebP/AVIF/GIF/HEIC
     * must also take the software-decoding path instead of the opaque
     * MediaStore thumbnail.
     */
    private fun knownAlpha(photo: Photo, requested: Boolean): Boolean? {
        if (!requested) return false
        return alphaFlags.get(flagKey(photo))
    }

    private fun detectAlpha(photo: Photo, requested: Boolean): Boolean {
        if (!requested) return false
        val alpha = when (photo.mimeLower) {
            "image/jpeg" -> false
            "image/png" -> pngHasAlpha(photo)
            // Be conservative: these formats may carry an alpha channel.
            else -> true
        }
        alphaFlags.put(flagKey(photo), alpha)
        return alpha
    }

    private fun pngHasAlpha(photo: Photo): Boolean {
        return try {
            resolver.openInputStream(photo.uri)?.use { input ->
                val header = ByteArray(26)
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    if (count <= 0) break
                    read += count
                }
                if (read < header.size) return@use false
                val signature = header[0] == 0x89.toByte() &&
                    header[1] == 'P'.code.toByte() &&
                    header[2] == 'N'.code.toByte() &&
                    header[3] == 'G'.code.toByte()
                if (!signature) return@use false
                // The first chunk must be IHDR before header[25] is a color type.
                val ihdr = header[12] == 'I'.code.toByte() &&
                    header[13] == 'H'.code.toByte() &&
                    header[14] == 'D'.code.toByte() &&
                    header[15] == 'R'.code.toByte()
                if (!ihdr) return@use false
                // 0=gray, 2=RGB, 3=palette(+tRNS), 4=gray+alpha, 6=RGBA.
                // Palette PNGs are treated as potentially transparent because
                // tRNS lives in a later chunk.
                val colorType = header[25].toInt() and 0xFF
                colorType == 3 || colorType == 4 || colorType == 6
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "png alpha probe failed for ${photo.uri}", e)
            false
        }
    }

    private fun loadInternal(photo: Photo, sizePx: Int, preserveAlpha: Boolean): Bitmap? {
        if (preserveAlpha) {
            decodeFallback(photo, sizePx)?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return resolver.loadThumbnail(photo.uri, Size(sizePx, sizePx), null)
            } catch (e: Exception) {
                Log.d(TAG, "loadThumbnail failed, falling back: ${e.message}")
            }
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
        alphaFlags.evictAll()
    }

    fun memoryCacheBytes(): Int = cache.size()

    private fun key(photo: Photo, sizePx: Int, alpha: Boolean): String =
        "${photo.uri}|${photo.dateModifiedMs}|$sizePx|${if (alpha) "a" else "o"}"

    private fun flagKey(photo: Photo): String =
        "${photo.uri}|${photo.dateModifiedMs}"

    private companion object {
        const val TAG = "SwiftPicThumb"
        const val DEFAULT_SIZE = 320

        fun cacheBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return minOf(heap / 8, 64L * 1024 * 1024).toInt().coerceAtLeast(8 * 1024 * 1024)
        }
    }
}
