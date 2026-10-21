package com.qoder.swiftpic.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class DecodeSpec(
    val maxDimension: Int,
    val maxPixels: Long
) {
    companion object {
        fun forScreen(screenWidth: Int, screenHeight: Int): DecodeSpec {
            val longest = max(screenWidth, screenHeight).coerceAtLeast(1)
            return DecodeSpec(
                maxDimension = min(4096, (longest * 2.2f).roundToInt()),
                maxPixels = 8_000_000L
            )
        }
    }
}

sealed class DecodedImage {
    data class Still(
        val bitmap: Bitmap,
        val hasGainMap: Boolean,
        val sourceWidth: Int,
        val sourceHeight: Int
    ) : DecodedImage() {
        val decodedWidth: Int get() = bitmap.width
        val decodedHeight: Int get() = bitmap.height
    }

    data class Animated(
        val drawable: Drawable,
        val width: Int,
        val height: Int
    ) : DecodedImage()
}

class ImageDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ImageLoader(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val semaphore = Semaphore(3)

    private val cache = object : LruCache<String, DecodedImage.Still>(cacheBytes()) {
        override fun sizeOf(key: String, value: DecodedImage.Still): Int =
            value.bitmap.allocationByteCount
    }

    suspend fun decode(photo: Photo, spec: DecodeSpec): DecodedImage {
        val cacheKey = key(photo, spec)
        cache.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            semaphore.withPermit {
                cache.get(cacheKey)?.let { return@withPermit it }
                val started = System.currentTimeMillis()
                var attemptSpec = spec
                var lastError: Throwable? = null
                repeat(2) {
                    try {
                        val result = decodeInternal(photo, attemptSpec)
                        if (result is DecodedImage.Still &&
                            result.bitmap.allocationByteCount < MAX_CACHED_BITMAP_BYTES
                        ) {
                            cache.put(cacheKey, result)
                        }
                        Log.d(
                            TAG,
                            "decode ${photo.name} ${result.let {
                                if (it is DecodedImage.Still) {
                                    "${it.decodedWidth}x${it.decodedHeight} hdr=${it.hasGainMap}"
                                } else {
                                    "animated"
                                }
                            }} in ${System.currentTimeMillis() - started}ms"
                        )
                        return@withPermit result
                    } catch (t: Throwable) {
                        lastError = t
                        if (t is OutOfMemoryError) {
                            cache.evictAll()
                            attemptSpec = DecodeSpec(
                                maxDimension = max(512, attemptSpec.maxDimension / 2),
                                maxPixels = max(1_000_000L, attemptSpec.maxPixels / 4)
                            )
                        } else {
                            throw t
                        }
                    }
                }
                throw ImageDecodeException("无法解码 ${photo.name}", lastError)
            }
        }
    }

    private fun decodeInternal(photo: Photo, spec: DecodeSpec): DecodedImage {
        val source = ImageDecoder.createSource(resolver, photo.uri)
        var sourceWidth = 0
        var sourceHeight = 0
        val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            sourceWidth = info.size.width
            sourceHeight = info.size.height
            val (tw, th) = targetSize(sourceWidth, sourceHeight, spec)
            if (tw != sourceWidth || th != sourceHeight) {
                decoder.setTargetSize(tw, th)
            }
        }

        val drawableFormat = photo.mimeLower == "image/webp" || photo.mimeLower == "image/png"
        if (drawableFormat) {
            val drawable = ImageDecoder.decodeDrawable(source, listener)
            if (drawable is AnimatedImageDrawable) {
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                return DecodedImage.Animated(drawable, w, h)
            }
            val bitmap: Bitmap = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                    ?: throw ImageDecodeException("图片没有可用的位图")
                else -> drawableToBitmap(drawable)
            }
            return still(bitmap, sourceWidth, sourceHeight)
        }

        // decodeBitmap is the path that keeps the Ultra HDR gain map attached on Android 14+.
        val bitmap = ImageDecoder.decodeBitmap(source, listener)
        return still(bitmap, sourceWidth, sourceHeight)
    }

    private fun still(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int): DecodedImage.Still {
        val hasGainMap = Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()
        return DecodedImage.Still(
            bitmap = bitmap,
            hasGainMap = hasGainMap,
            sourceWidth = if (sourceWidth > 0) sourceWidth else bitmap.width,
            sourceHeight = if (sourceHeight > 0) sourceHeight else bitmap.height
        )
    }

    private fun targetSize(width: Int, height: Int, spec: DecodeSpec): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        // A screen-sized cap crushes long screenshots: 584x19683 was decoded 121px
        // wide and stayed blurry at every zoom level. Long/panoramic images keep
        // near-native resolution instead, bounded by pixels and the max bitmap size.
        val longAspect = max(width, height).toLong() >= min(width, height).toLong() * EXTREME_ASPECT
        val maxDimension = if (longAspect) max(spec.maxDimension, EXTREME_MAX_DIMENSION) else spec.maxDimension
        val maxPixels = if (longAspect) max(spec.maxPixels, EXTREME_MAX_PIXELS) else spec.maxPixels
        val dimensionScale = maxDimension.toDouble() / max(width, height).toDouble()
        val pixelScale = sqrt(maxPixels.toDouble() / (width.toDouble() * height.toDouble()))
        val scale = min(1.0, min(dimensionScale, pixelScale))
        if (scale >= 1.0) return width to height
        val tw = max(1, (width * scale).roundToInt())
        val th = max(1, (height * scale).roundToInt())
        return tw to th
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    fun evictAll() {
        cache.evictAll()
    }

    fun memoryCacheBytes(): Int = cache.size()

    private fun key(photo: Photo, spec: DecodeSpec): String =
        "${photo.uri}|${photo.dateModifiedMs}|${spec.maxDimension}|${spec.maxPixels}"

    private companion object {
        const val TAG = "SwiftPicImage"
        const val MAX_CACHED_BITMAP_BYTES = 24 * 1024 * 1024
        const val EXTREME_ASPECT = 3L
        const val EXTREME_MAX_DIMENSION = 20_000
        const val EXTREME_MAX_PIXELS = 10_000_000L

        fun cacheBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return minOf(heap / 4, 72L * 1024 * 1024).toInt().coerceAtLeast(16 * 1024 * 1024)
        }
    }
}
