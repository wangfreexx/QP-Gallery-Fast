package com.qoder.swiftpic.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.min

object HdrDetector {

    private const val MAX_HEAD_BYTES = 256 * 1024
    private val markers = arrayOf(
        "hdrgm".toByteArray(Charsets.US_ASCII),
        "GainMap".toByteArray(Charsets.US_ASCII)
    )
    private val guard = Semaphore(2)
    private val cache = LruCache<String, Boolean>(768)

    fun remember(photo: Photo, isHdr: Boolean) {
        cache.put("${photo.uri}|${photo.dateModifiedMs}", isHdr)
    }

    suspend fun isHdr(context: Context, photo: Photo): Boolean {
        val key = "${photo.uri}|${photo.dateModifiedMs}"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            guard.withPermit {
                cache.get(key)?.let { return@withPermit it }
                val result = scan(context.contentResolver, photo.uri)
                cache.put(key, result)
                result
            }
        }
    }

    private fun scan(resolver: ContentResolver, uri: Uri): Boolean {
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16 * 1024)
                val carry = ByteArray(8)
                var carryLength = 0
                var total = 0
                while (total < MAX_HEAD_BYTES) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val window = ByteArray(carryLength + read)
                    System.arraycopy(carry, 0, window, 0, carryLength)
                    System.arraycopy(buffer, 0, window, carryLength, read)
                    if (markers.any { contains(window, it) }) return true
                    carryLength = min(carry.size, window.size)
                    System.arraycopy(window, window.size - carryLength, carry, 0, carryLength)
                    total += read
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
