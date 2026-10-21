package com.qoder.swiftpic.data

import android.content.Context
import android.net.Uri
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeader
import com.bumptech.glide.gifdecoder.GifHeaderParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

class GifSource private constructor(
    val bytes: ByteArray,
    val header: GifHeader,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val sampleSize: Int
) {
    companion object {
        private const val MAX_BYTES = 64 * 1024 * 1024
        private const val MAX_DECODE_DIMENSION = 1600

        suspend fun load(context: Context, uri: Uri): GifSource? = withContext(Dispatchers.IO) {
            val bytes = readBytes(context, uri) ?: return@withContext null
            val header = try {
                GifHeaderParser().apply { setData(bytes) }.parseHeader()
            } catch (_: Throwable) {
                return@withContext null
            }
            if (header.status != GifDecoder.STATUS_OK) return@withContext null
            val width = header.width
            val height = header.height
            val frames = header.numFrames
            if (width <= 0 || height <= 0 || frames <= 0) return@withContext null

            var sampleSize = 1
            while (max(width, height) / sampleSize > MAX_DECODE_DIMENSION) {
                sampleSize *= 2
            }
            GifSource(bytes, header, width, height, frames, sampleSize)
        }

        private fun readBytes(context: Context, uri: Uri): ByteArray? {
            return try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream(256 * 1024)
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            throw IOException("GIF too large")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
