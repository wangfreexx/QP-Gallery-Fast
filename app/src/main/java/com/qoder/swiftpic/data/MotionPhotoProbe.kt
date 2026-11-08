package com.qoder.swiftpic.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class MotionPhotoProbe(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val cache = LruCache<String, LiveProbeResult>(MAX_CACHE_ENTRIES)
    private val extractMutex = Mutex()

    suspend fun probe(photo: Photo): LiveProbeResult = withContext(Dispatchers.IO) {
        val key = key(photo)
        cache.get(key)?.let { return@withContext it }
        val started = System.currentTimeMillis()
        val result = try {
            probeInternal(photo)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "probe failed for ${photo.uri}", e)
            LiveProbeResult(null)
        }
        cache.put(key, result)
        Log.d(TAG, "probe ${photo.name}: found=${result.found} in ${System.currentTimeMillis() - started}ms")
        result
    }

    fun cached(photo: Photo): LiveProbeResult? = cache.get(key(photo))

    /** Clears only the in-memory probe result cache. */
    fun clearMemoryCache() {
        cache.evictAll()
    }

    suspend fun extract(photo: Photo, part: MotionPart): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "motion").apply { mkdirs() }
        val uriHash = sha16(photo.uri.toString())
        val out = File(
            dir,
            "${photo.id}_${uriHash}_${photo.sizeBytes}_${photo.dateModifiedMs}_${part.offset}.mp4"
        )
        if (out.isFile && out.length() == part.length) return@withContext out

        extractMutex.withLock {
            if (out.isFile && out.length() == part.length) return@withLock out
            val tmp = File(dir, out.name + ".tmp")
            try {
                openAssetStream(photo.uri).use { raw ->
                    val input = if (raw is java.io.BufferedInputStream) raw else raw.buffered(64 * 1024)
                    skipFully(input, part.offset)
                    tmp.outputStream().buffered(64 * 1024).use { output ->
                        copyExactly(input, output, part.length)
                    }
                }
                if (tmp.length() != part.length) {
                    throw IOException("motion video truncated: ${tmp.length()} != ${part.length}")
                }

                // Atomic replace. tmp is complete and validated before we touch the
                // destination: on Android renameTo fails when the target exists, so
                // move any stale entry aside (it can be restored on failure) instead
                // of deleting a possibly valid file up front.
                val backup = File(dir, out.name + ".bak")
                if (backup.exists()) backup.delete()
                val hadExisting = out.exists()
                if (hadExisting && !out.renameTo(backup)) {
                    throw IOException("cannot move existing motion cache $out aside")
                }
                if (!tmp.renameTo(out)) {
                    if (hadExisting) backup.renameTo(out)
                    throw IOException("cannot install motion cache $out")
                }
                if (hadExisting) backup.delete()
                out
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                tmp.delete()
                Log.w(TAG, "extract motion video failed for ${photo.uri}", e)
                throw e
            }
        }
    }

    /** Whole cacheDir: the reported size and the clear action must use the same scope. */
    fun diskCacheBytes(): Long = CacheStats.diskCacheBytes(context)

    fun clearDiskCache() {
        clearMemoryCache()
        CacheStats.clearDiskCache(context)
    }

    fun trimCache() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                extractMutex.withLock {
                    val dir = File(context.cacheDir, "motion")
                    if (!dir.isDirectory) return@withLock
                    val now = System.currentTimeMillis()
                    var total = 0L
                    val files = dir.listFiles()?.filter { it.isFile }
                        ?.sortedByDescending { it.lastModified() } ?: return@withLock
                    files.forEach { file ->
                        if (now - file.lastModified() > 7L * 24 * 3600 * 1000) {
                            file.delete()
                        } else {
                            total += file.length()
                        }
                    }
                    val limit = 256L * 1024 * 1024
                    var kept = 0L
                    files.filter { it.exists() }.forEach { file ->
                        kept += file.length()
                        if (total > limit && kept > limit) file.delete()
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "trimCache failed", e)
            }
        }
    }

    private fun probeInternal(photo: Photo): LiveProbeResult {
        val afd = resolver.openAssetFileDescriptor(photo.uri, "r")
        val fileSize = afd?.use { descriptor ->
            when {
                descriptor.length > 0 -> descriptor.length
                else -> {
                    val stat = try {
                        descriptor.parcelFileDescriptor.statSize
                    } catch (e: Exception) {
                        Log.w(TAG, "statSize failed for ${photo.uri}", e)
                        -1L
                    }
                    if (stat > 0) stat else photo.sizeBytes
                }
            }
        } ?: photo.sizeBytes

        if (fileSize <= 0) return LiveProbeResult(null)

        val head = readHead(photo.uri, HEAD_BYTES)
        if (head.isEmpty()) return LiveProbeResult(null)
        val tailLen = minOf(fileSize, TAIL_BYTES).toInt()
        val tail = readTail(photo.uri, fileSize, tailLen)
        // A motion photo's video lives at the tail. Do not trust head-only XMP
        // metadata (e.g. MicroVideoOffset) when the tail could not be read.
        if (tail.isEmpty()) return LiveProbeResult(null)
        val tailStart = fileSize - tail.size
        val part = MotionPhotoParser.parse(head, tail, tailStart, fileSize)
        return LiveProbeResult(part)
    }

    private fun readHead(uri: Uri, max: Int): ByteArray {
        return try {
            openAssetStream(uri).use { input ->
                input.buffered(64 * 1024).readUpTo(max)
            }
        } catch (e: IOException) {
            Log.w(TAG, "readHead failed for $uri", e)
            ByteArray(0)
        }
    }

    private fun readTail(uri: Uri, fileSize: Long, tailLen: Int): ByteArray {
        return try {
            openAssetStream(uri).use { input ->
                val buffered = input.buffered(64 * 1024)
                skipFully(buffered, fileSize - tailLen)
                buffered.readUpTo(tailLen)
            }
        } catch (e: IOException) {
            Log.w(TAG, "readTail failed for $uri", e)
            ByteArray(0)
        }
    }

    private fun openAssetStream(uri: Uri): InputStream {
        val afd = resolver.openAssetFileDescriptor(uri, "r")
            ?: throw IOException("cannot open $uri")
        return try {
            afd.createInputStream()
        } catch (e: Exception) {
            try {
                afd.close()
            } catch (closeError: Exception) {
                Log.w(TAG, "close afd failed for $uri", closeError)
            }
            throw e
        }
    }

    private fun InputStream.readUpTo(max: Int): ByteArray {
        // Pre-allocate once. ByteArrayOutputStream.toByteArray() used to double
        // the peak (16 MB buffer + 16 MB copy) for every motion-photo probe.
        val out = ByteArray(max)
        var offset = 0
        while (offset < max) {
            val read = read(out, offset, max - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == max) out else out.copyOf(offset)
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw IOException("unexpected EOF while skipping")
            remaining -= read
        }
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw IOException("unexpected EOF while copying motion video")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun sha16(text: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        buildString(16) {
            for (i in 0 until 8) {
                val b = digest[i].toInt() and 0xFF
                append(HEX[b ushr 4])
                append(HEX[b and 0x0F])
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "SHA-256 unavailable", e)
        text.hashCode().toString(16).padStart(8, '0')
    }

    private fun key(photo: Photo): String =
        "${photo.uri}|${photo.sizeBytes}|${photo.dateModifiedMs}"

    private companion object {
        const val TAG = "SwiftPicMotion"
        const val HEAD_BYTES = 256 * 1024
        const val TAIL_BYTES = 16L * 1024 * 1024
        const val MAX_CACHE_ENTRIES = 256
        val HEX = "0123456789abcdef".toCharArray()
    }
}
