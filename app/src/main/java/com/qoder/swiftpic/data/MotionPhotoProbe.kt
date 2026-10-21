package com.qoder.swiftpic.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class MotionPhotoProbe(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val cache = ConcurrentHashMap<String, LiveProbeResult>()
    private val extractMutex = Mutex()

    suspend fun probe(photo: Photo): LiveProbeResult = withContext(Dispatchers.IO) {
        val key = key(photo)
        cache[key]?.let { return@withContext it }
        val started = System.currentTimeMillis()
        val result = runCatching { probeInternal(photo) }
            .onFailure { Log.w(TAG, "probe failed for ${photo.uri}", it) }
            .getOrElse { LiveProbeResult(null) }
        cache[key] = result
        Log.d(TAG, "probe ${photo.name}: found=${result.found} in ${System.currentTimeMillis() - started}ms")
        result
    }

    fun cached(photo: Photo): LiveProbeResult? = cache[key(photo)]

    suspend fun extract(photo: Photo, part: MotionPart): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "motion").apply { mkdirs() }
        val uriHash = photo.uri.toString().hashCode()
        val out = File(
            dir,
            "${photo.id}_${uriHash}_${photo.sizeBytes}_${photo.dateModifiedMs}.mp4"
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
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                out
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }
    }

    fun diskCacheBytes(): Long =
        File(context.cacheDir, "motion").walk()
            .filter { it.isFile }
            .sumOf { it.length() }

    fun clearDiskCache() {
        File(context.cacheDir, "motion").deleteRecursively()
    }

    fun trimCache() {
        Thread {
            val dir = File(context.cacheDir, "motion")
            if (!dir.isDirectory) return@Thread
            val now = System.currentTimeMillis()
            var total = 0L
            val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                ?: return@Thread
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
        }.apply { isDaemon = true }.start()
    }

    private fun probeInternal(photo: Photo): LiveProbeResult {
        val afd = resolver.openAssetFileDescriptor(photo.uri, "r")
        val fileSize = afd?.use { descriptor ->
            when {
                descriptor.length > 0 -> descriptor.length
                else -> {
                    val stat = runCatching {
                        descriptor.parcelFileDescriptor.statSize
                    }.getOrDefault(-1L)
                    if (stat > 0) stat else photo.sizeBytes
                }
            }
        } ?: photo.sizeBytes

        if (fileSize <= 0) return LiveProbeResult(null)

        val head = readHead(photo.uri, HEAD_BYTES)
        if (head.isEmpty()) return LiveProbeResult(null)
        val tailLen = minOf(fileSize, TAIL_BYTES).toInt()
        val tail = readTail(photo.uri, fileSize, tailLen)
        val tailStart = fileSize - tail.size
        val part = MotionPhotoParser.parse(head, tail, tailStart, fileSize)
        return LiveProbeResult(part)
    }

    private fun readHead(uri: Uri, max: Int): ByteArray {
        return runCatching {
            openAssetStream(uri).use { input ->
                input.buffered(64 * 1024).readUpTo(max)
            }
        }.getOrElse { ByteArray(0) }
    }

    private fun readTail(uri: Uri, fileSize: Long, tailLen: Int): ByteArray {
        return runCatching {
            openAssetStream(uri).use { input ->
                val buffered = input.buffered(64 * 1024)
                skipFully(buffered, fileSize - tailLen)
                buffered.readUpTo(tailLen)
            }
        }.getOrElse { ByteArray(0) }
    }

    private fun openAssetStream(uri: Uri): InputStream {
        val afd = resolver.openAssetFileDescriptor(uri, "r")
            ?: throw IOException("cannot open $uri")
        return try {
            afd.createInputStream()
        } catch (t: Throwable) {
            runCatching { afd.close() }
            throw t
        }
    }

    private fun InputStream.readUpTo(max: Int): ByteArray {
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

    private fun key(photo: Photo): String =
        "${photo.uri}|${photo.sizeBytes}|${photo.dateModifiedMs}"

    private companion object {
        const val TAG = "SwiftPicMotion"
        const val HEAD_BYTES = 1024 * 1024
        const val TAIL_BYTES = 16L * 1024 * 1024
    }
}
