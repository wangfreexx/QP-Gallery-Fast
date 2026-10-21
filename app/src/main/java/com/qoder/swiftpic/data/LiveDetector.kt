package com.qoder.swiftpic.data

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.qoder.swiftpic.swiftPic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Cheap live-photo badge detector. Google/Xiaomi motion photos carry their metadata
 * in the XMP header, so scanning the first 256 KB is enough for a grid badge; the
 * viewer still probes the real video part before playing.
 */
object LiveDetector {

    private const val HEAD_BYTES = 256 * 1024

    private val cache = LruCache<String, Boolean>(2048)
    private val gate = Semaphore(2)

    // Xiaomi writes Container:Directory (Primary + GainMap) into ordinary HDR shots too,
    // so only the motion-photo flags count as a live marker.
    private val markers = arrayOf(
        "MotionPhoto=\"1\"",
        "MicroVideo=\"1\""
    )

    fun cached(photo: Photo): Boolean? = cache.get(key(photo))

    suspend fun isLive(context: Context, photo: Photo): Boolean {
        if (photo.hasLivePair) return true
        if (!photo.canContainMotionPhoto) return false
        cache.get(key(photo))?.let { return it }
        if (context.swiftPic.motion.cached(photo)?.found == true) {
            cache.put(key(photo), true)
            return true
        }
        val detected = withContext(Dispatchers.IO) {
            gate.withPermit { scanHead(context, photo.uri) }
        }
        if (detected) android.util.Log.d("SwiftPicLive", "live ${photo.name}")
        cache.put(key(photo), detected)
        return detected
    }

    private fun scanHead(context: Context, uri: Uri): Boolean {
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.buffered(64 * 1024).readUpTo(HEAD_BYTES)
            }
        }.onFailure { android.util.Log.w("SwiftPicLive", "open failed $uri", it) }
            .getOrNull() ?: return false
        if (head.isEmpty()) return false
        return markers.any { marker -> head.indexOfAscii(marker) >= 0 }
    }

    private fun ByteArray.indexOfAscii(needle: String): Int {
        val first = needle[0].code.toByte()
        val last = size - needle.length
        var i = 0
        while (i <= last) {
            if (this[i] == first) {
                var match = true
                for (j in 1 until needle.length) {
                    if (this[i + j] != needle[j].code.toByte()) {
                        match = false
                        break
                    }
                }
                if (match) return i
            }
            i++
        }
        return -1
    }

    private fun java.io.InputStream.readUpTo(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        var remaining = max
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun key(photo: Photo): String =
        "${photo.uri}|${photo.sizeBytes}|${photo.dateModifiedMs}"
}
