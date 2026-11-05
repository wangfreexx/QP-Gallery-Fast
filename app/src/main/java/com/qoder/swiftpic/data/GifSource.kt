package com.qoder.swiftpic.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.util.Log
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeader
import com.bumptech.glide.gifdecoder.GifHeaderParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Holds the raw GIF data for [GifPlayer]. Large files are memory-mapped from
 * the source file descriptor, so they never occupy the Java heap -- a fixed
 * "max bytes" limit would reject perfectly normal 20-60 MB GIFs while still
 * risking OOM for a 16 MB file on a small heap.
 */
class GifSource private constructor(
    private val data: ByteBuffer,
    val header: GifHeader,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val sampleSize: Int
) {
    /** Every decoder needs its own position-zero view over the GIF data. */
    fun buffer(): ByteBuffer = data.duplicate().apply { position(0) }

    companion object {
        private const val TAG = "SwiftPicGIF"
        private const val MAX_DECODE_DIMENSION = 1600
        private const val CHUNK = 64 * 1024

        /** Address-space ceiling for mmap, far above any realistic GIF. */
        private const val MAX_MAPPED_BYTES = 256L * 1024 * 1024

        /** Heap fallback budget: at most 1/6 of the heap, clamped to 32..96 MB. */
        private fun maxHeapBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return (heap / 6).coerceIn(32L * 1024 * 1024, 96L * 1024 * 1024).toInt()
        }

        suspend fun load(context: Context, uri: Uri): GifSource? = withContext(Dispatchers.IO) {
            val data = openBuffer(context, uri) ?: return@withContext null
            val header = try {
                GifHeaderParser().apply {
                    setData(data.duplicate().apply { position(0) })
                }.parseHeader()
            } catch (e: Exception) {
                Log.w(TAG, "parse GIF header failed for $uri", e)
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
            GifSource(data.asReadOnlyBuffer(), header, width, height, frames, sampleSize)
        }

        private fun openBuffer(context: Context, uri: Uri): ByteBuffer? {
            val afd = try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")
            } catch (e: Exception) {
                Log.w(TAG, "open GIF failed for $uri", e)
                null
            } ?: return null

            return try {
                val length = afd.length
                if (length > MAX_MAPPED_BYTES) {
                    Log.w(TAG, "GIF too large to map: $length bytes for $uri")
                    return null
                }
                if (length > 0) {
                    val mapped = tryMap(afd, length)
                    if (mapped != null) {
                        Log.d(TAG, "GIF mapped ${length} bytes (off-heap) for $uri")
                        return mapped
                    }
                }
                val limit = maxHeapBytes()
                val bytes = afd.createInputStream()?.use { readToHeap(it, limit, length) }
                    ?: return null
                Log.d(TAG, "GIF read ${bytes.size} bytes into heap (limit $limit) for $uri")
                ByteBuffer.wrap(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "read GIF failed for $uri", e)
                null
            } finally {
                try {
                    afd.close()
                } catch (e: Exception) {
                    Log.w(TAG, "close GIF descriptor failed", e)
                }
            }
        }

        /** Memory-map when the provider exposes a real file descriptor. */
        private fun tryMap(afd: AssetFileDescriptor, length: Long): ByteBuffer? {
            return try {
                val fd = afd.fileDescriptor ?: return null
                val input = FileInputStream(fd)
                try {
                    input.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, length)
                } finally {
                    input.close()
                }
            } catch (e: Exception) {
                // Pipes / cursor-backed providers cannot be mapped; caller falls back.
                Log.d(TAG, "mmap unavailable: ${e.message}")
                null
            }
        }

        private fun readToHeap(input: InputStream, limit: Int, declared: Long): ByteArray? {
            if (declared > limit) {
                Log.w(TAG, "GIF too large for heap fallback: $declared bytes (limit $limit)")
                return null
            }
            if (declared > 0) {
                val bytes = ByteArray(declared.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                if (offset == 0) return null
                return if (offset == bytes.size) bytes else bytes.copyOf(offset)
            }

            // Providers reporting UNKNOWN_LENGTH.
            val chunk = ByteArray(CHUNK)
            var bytes = ByteArray(CHUNK)
            var total = 0
            while (total < limit) {
                val read = input.read(chunk, 0, min(chunk.size, limit - total))
                if (read <= 0) break
                if (total + read > bytes.size) {
                    bytes = bytes.copyOf(min(limit, max(total + read, bytes.size * 2)))
                }
                System.arraycopy(chunk, 0, bytes, total, read)
                total += read
            }
            if (total == limit && input.read() != -1) {
                Log.w(TAG, "GIF exceeds heap fallback limit $limit")
                return null
            }
            if (total == 0) return null
            return if (total == bytes.size) bytes else bytes.copyOf(total)
        }
    }
}
