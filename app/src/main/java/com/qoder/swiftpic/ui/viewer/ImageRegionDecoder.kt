package com.qoder.swiftpic.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decodes only the visible rectangle of a large still image so that zooming
 * past the base bitmap's resolution stays sharp without decoding (or holding)
 * the whole full-resolution image.
 *
 * BitmapRegionDecoder covers JPEG/PNG/WebP but not every HEIC/AVIF or gain-map
 * image, so [create] returns null for unsupported sources and callers silently
 * keep the classic single-bitmap path.
 */
class ImageRegionDecoder private constructor(
    private val decoder: BitmapRegionDecoder,
    private val stream: InputStream?,
    private val owner: Closeable?,
    val width: Int,
    val height: Int,
    private val displayWidth: Int,
    private val displayHeight: Int
) {
    private val guard = Any()

    /**
     * @param visible rectangle in **decoded bitmap** coordinates, which is what
     *   ZoomImageView uses for its matrix and drawing.
     * @return a bitmap covering exactly [visible] (possibly sub-sampled), or null.
     */
    fun decode(visible: Rect, viewWidth: Int, viewHeight: Int): Bitmap? {
        if (width <= 0 || height <= 0 || displayWidth <= 0 || displayHeight <= 0 ||
            visible.width() <= 0 || visible.height() <= 0
        ) return null
        // BitmapRegionDecoder works in source-file coordinates. ImageLoader may
        // have decoded a downscaled bitmap (setTargetSize), so convert the
        // viewport first -- otherwise the tile shows a differently scaled crop
        // of the photo and looks zoomed in/out compared with the base bitmap.
        val sx = width.toDouble() / displayWidth
        val sy = height.toDouble() / displayHeight
        val rect = Rect(
            (visible.left * sx).toInt().coerceIn(0, width),
            (visible.top * sy).toInt().coerceIn(0, height),
            (visible.right * sx).toInt().coerceIn(0, width),
            (visible.bottom * sy).toInt().coerceIn(0, height)
        )
        if (rect.width() <= 0 || rect.height() <= 0) return null

        // Aim for slightly more than one decoded pixel per screen pixel, then
        // round the sample down to a power of two as BitmapFactory requires.
        val targetPixels = (viewWidth.toLong() * viewHeight * 14L / 10L)
            .coerceAtLeast(1L)
        val regionPixels = rect.width().toLong() * rect.height()
        val rawSample = sqrt(regionPixels.toDouble() / targetPixels.toDouble())
        var sample = 1
        while (sample < 32 && sample * 2 <= rawSample) sample *= 2
        // Hard ceiling keeps a single tile well below the OOM threshold.
        while (sample < 64 && regionPixels / (sample.toLong() * sample) > MAX_TILE_PIXELS) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return synchronized(guard) {
            try {
                val result = decoder.decodeRegion(rect, options)
                if (result != null) {
                    Log.d(
                        TAG,
                        "tile src=${rect.width()}x${rect.height()} sample=$sample " +
                            "-> ${result.width}x${result.height} " +
                            "(display ${displayWidth}x${displayHeight}, source ${width}x${height})"
                    )
                }
                result
            } catch (e: Exception) {
                Log.w(TAG, "decodeRegion failed rect=$rect sample=$sample", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "decodeRegion OOM rect=$rect sample=$sample")
                null
            }
        }
    }

    fun close() {
        synchronized(guard) {
            @Suppress("DEPRECATION")
            decoder.recycle()
        }
        try {
            stream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close stream failed", e)
        }
        try {
            owner?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close descriptor failed", e)
        }
    }

    companion object {
        const val TAG = "SwiftPicRegion"
        const val MAX_TILE_PIXELS = 4_000_000L

        private fun hasExifRotation(context: Context, uri: Uri): Boolean = try {
            val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            } ?: ExifInterface.ORIENTATION_UNDEFINED
            orientation != ExifInterface.ORIENTATION_UNDEFINED &&
                orientation != ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            false
        }

        fun create(
            context: Context,
            uri: Uri,
            displayWidth: Int,
            displayHeight: Int
        ): ImageRegionDecoder? {
            return try {
                // ImageDecoder rotates the base bitmap per EXIF, but
                // BitmapRegionDecoder works in raw file coordinates. Overlaying
                // a tile on such a photo shows the region upside-down / rotated.
                if (hasExifRotation(context, uri)) return null
                val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                    ?: return null
                val input = afd.createInputStream() ?: run {
                    afd.close()
                    return null
                }
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(input, false)
                    ?: run {
                        input.close()
                        afd.close()
                        return null
                    }
                if (decoder.width <= 0 || decoder.height <= 0) {
                    @Suppress("DEPRECATION")
                    decoder.recycle()
                    input.close()
                    afd.close()
                    return null
                }
                // Fallback for rotated sources without a readable EXIF tag:
                // swapped aspect ratios cannot be mapped by a simple scale.
                if (displayWidth > 0 && displayHeight > 0) {
                    val srcAspect = decoder.width.toDouble() / decoder.height
                    val displayAspect = displayWidth.toDouble() / displayHeight
                    if (abs(srcAspect - displayAspect) > 0.05 &&
                        abs(srcAspect * displayAspect - 1.0) < 0.05
                    ) {
                        @Suppress("DEPRECATION")
                        decoder.recycle()
                        input.close()
                        afd.close()
                        return null
                    }
                }
                ImageRegionDecoder(
                    decoder, input, afd,
                    decoder.width, decoder.height,
                    displayWidth, displayHeight
                )
            } catch (e: Exception) {
                // HEIC/AVIF and some vendor formats are not region-decodable.
                Log.d(TAG, "region decoder unavailable for $uri: ${e.message}")
                null
            }
        }
    }
}
