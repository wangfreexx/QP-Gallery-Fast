package com.qoder.swiftpic.data

import android.net.Uri

const val LIVE_NONE = 0
const val LIVE_PAIR = 1
const val LIVE_EMBEDDED = 2

data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val dateTakenMs: Long,
    val dateModifiedMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val bucketKey: String,
    val bucketName: String,
    val relativePath: String,
    val volumeName: String,
    val liveVideoUri: Uri? = null,
    val liveKind: Int = LIVE_NONE
) {
    val sortTimeMs: Long
        get() = if (dateTakenMs > 0) dateTakenMs else dateModifiedMs

    val mimeLower: String
        get() = mime.lowercase()

    val isGif: Boolean
        get() = mimeLower == "image/gif"

    val hasLivePair: Boolean
        get() = liveKind == LIVE_PAIR && liveVideoUri != null

    val canContainMotionPhoto: Boolean
        get() = mimeLower == "image/jpeg" ||
            mimeLower == "image/heic" ||
            mimeLower == "image/heif"

    val isAnimatedCandidate: Boolean
        get() = mimeLower == "image/webp" || mimeLower == "image/png" ||
            mimeLower == "image/avif"

    val displayPath: String
        get() {
            val dir = relativePath.trim('/')
            return if (dir.isEmpty()) name else "$dir/$name"
        }

    val baseName: String
        get() = name.substringBeforeLast('.', name).lowercase()

    val stableKey: String
        get() = "$volumeName|${relativePath.trim('/')}|$name"
}

data class Album(
    val key: String,
    val name: String,
    val photos: List<Photo>
) {
    val cover: Photo?
        get() = photos.firstOrNull()
    val count: Int
        get() = photos.size
    val latestTime: Long
        get() = photos.firstOrNull()?.sortTimeMs ?: 0L
}

data class GalleryLibrary(
    val photos: List<Photo>,
    val albums: List<Album>,
    val scannedAt: Long,
    val hasImageAccess: Boolean,
    val scanError: Boolean = false
) {
    companion object {
        fun empty(hasAccess: Boolean) = GalleryLibrary(
            photos = emptyList(),
            albums = emptyList(),
            scannedAt = System.currentTimeMillis(),
            hasImageAccess = hasAccess
        )
    }
}

data class MotionPart(
    val offset: Long,
    val length: Long
)

data class LiveProbeResult(
    val part: MotionPart?
) {
    val found: Boolean
        get() = part != null
}

sealed class LiveSource {
    data class Pair(val uri: Uri) : LiveSource()
    data class Extracted(val file: java.io.File) : LiveSource()
}
