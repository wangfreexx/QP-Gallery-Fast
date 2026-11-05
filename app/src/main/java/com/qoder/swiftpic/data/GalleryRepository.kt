package com.qoder.swiftpic.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class GalleryRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val prefs = AppPrefs(context)
    private val scanMutex = Mutex()

    @Volatile
    private var cached: GalleryLibrary? = null

    @Volatile
    private var dirty = true

    /** Monotonic counter of invalidation events, including ones during an in-flight scan. */
    private val revision = AtomicInteger(0)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            dirty = true
            revision.incrementAndGet()
        }
    }

    init {
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).forEach { uri ->
            try {
                resolver.registerContentObserver(uri, true, observer)
            } catch (e: Exception) {
                Log.w(TAG, "register observer failed for $uri", e)
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                resolver.registerContentObserver(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    true,
                    observer
                )
            } catch (e: Exception) {
                Log.w(TAG, "register files observer failed", e)
            }
        }
    }

    fun isDirty(): Boolean = dirty

    fun peek(): GalleryLibrary? = cached

    fun markDirty() {
        dirty = true
        revision.incrementAndGet()
    }

    suspend fun load(force: Boolean = false): GalleryLibrary {
        val snapshot = cached
        if (!force && !dirty && snapshot != null) return snapshot
        return scanMutex.withLock {
            if (!force && !dirty) {
                cached?.let { return@withLock it }
            }
            val started = System.currentTimeMillis()
            val startRevision = revision.get()
            val library = withContext(Dispatchers.IO) { scan() }
            cached = library
            // A change notification that arrived while scan() was running must
            // not be swallowed by blindly resetting the flag here.
            if (revision.get() == startRevision) {
                dirty = false
            }
            Log.d(TAG, "scan: ${library.photos.size} photos, ${library.albums.size} albums " +
                "in ${System.currentTimeMillis() - started}ms")
            library
        }
    }

    private fun scan(): GalleryLibrary {
        if (!MediaPermissions.canReadImages(context)) {
            return GalleryLibrary.empty(false)
        }

        val volumes = externalImageVolumes()
        val canReadVideos = MediaPermissions.canReadVideos(context)
        val videoMap = HashMap<String, Uri>()
        var hadError = false

        volumes.forEach { volume ->
            try {
                if (canReadVideos) {
                    queryVideos(volume, videoMap)
                }
            } catch (e: Exception) {
                hadError = true
                Log.w(TAG, "video query failed for ${volume.name}", e)
            }
        }

        Log.d(TAG, "volumes=${volumes.map { it.name }} videos=${videoMap.size}")

        val photos = ArrayList<Photo>(2048)
        volumes.forEach { volume ->
            try {
                queryImages(volume, photos)
            } catch (e: Exception) {
                hadError = true
                Log.w(TAG, "image query failed for ${volume.name}", e)
            }
        }

        val includePaths = prefs.includePaths
        val excludePaths = prefs.excludePaths
        if (includePaths.isNotEmpty() || excludePaths.isNotEmpty()) {
            Log.d(TAG, "path filters inc=$includePaths exc=$excludePaths")
        }

        var livePairs = 0
        val paired = if (videoMap.isEmpty()) {
            photos
        } else {
            photos.map { photo ->
                if (!photo.canContainMotionPhoto) return@map photo
                val key = pairKey(photo.volumeName, photo.relativePath, photo.name)
                val video = videoMap[key]
                if (video == null) photo else {
                    livePairs++
                    photo.copy(liveVideoUri = video, liveKind = LIVE_PAIR)
                }
            }
        }
        Log.d(TAG, "livePairs=$livePairs")
        val filtered = if (includePaths.isEmpty() && excludePaths.isEmpty()) {
            paired
        } else {
            paired.filter { photo -> matchesPathFilters(photo, includePaths, excludePaths) }
        }

        val sorted = filtered.sortedWith(
            compareByDescending<Photo> { it.sortTimeMs }.thenByDescending { it.id }
        )

        val groups = LinkedHashMap<String, MutableList<Photo>>()
        sorted.forEach { photo ->
            groups.getOrPut(photo.bucketKey) { ArrayList() }.add(photo)
        }
        val albums = groups.map { (key, list) ->
            Album(
                key = key,
                name = list.first().bucketName,
                photos = list
            )
        }.sortedByDescending { it.latestTime }

        return GalleryLibrary(
            photos = sorted,
            albums = albums,
            scannedAt = System.currentTimeMillis(),
            hasImageAccess = true,
            scanError = hadError
        )
    }

    private data class VolumeSpec(val name: String, val uri: Uri)

    private fun externalImageVolumes(): List<VolumeSpec> {
        if (Build.VERSION.SDK_INT < 29) {
            return listOf(
                VolumeSpec("external", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            )
        }
        val names = runCatching {
            MediaStore.getExternalVolumeNames(context).toList()
        }.getOrElse { listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY) }
        if (names.isEmpty()) {
            return listOf(
                VolumeSpec(MediaStore.VOLUME_EXTERNAL_PRIMARY, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            )
        }
        return names.map { name ->
            VolumeSpec(name, MediaStore.Images.Media.getContentUri(name))
        }
    }

    private fun queryImages(volume: VolumeSpec, out: MutableList<Photo>) {
        val api29 = Build.VERSION.SDK_INT >= 29
        val projection = ArrayList<String>().apply {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            if (api29) add(MediaStore.MediaColumns.RELATIVE_PATH)
            else @Suppress("DEPRECATION") add(MediaStore.MediaColumns.DATA)
        }
        val selection = mediaSelection()
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        resolver.query(volume.uri, projection.toTypedArray(), selection, null, sortOrder)?.use { cursor ->
            // OEMs occasionally omit optional columns. Only _ID / name / mime
            // are truly required; everything else degrades to a default instead
            // of dropping the entire volume's photos.
            val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            if (idIdx < 0 || nameIdx < 0 || mimeIdx < 0) {
                Log.w(TAG, "essential columns missing for ${volume.name}")
                return@use
            }
            val takenIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val modifiedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val bucketIdIdx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val relIdx = if (api29) {
                cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                -1
            }
            @Suppress("DEPRECATION")
            val dataIdx = if (api29) {
                -1
            } else {
                cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            }

            while (cursor.moveToNext()) {
                val id = cursor.optLong(idIdx)
                if (id <= 0L) continue
                val name = cursor.optString(nameIdx).takeIf { it.isNotBlank() } ?: continue
                val mime = cursor.optString(mimeIdx)
                if (!mime.startsWith("image/", ignoreCase = true)) continue

                val taken = cursor.optLong(takenIdx)
                val modifiedSec = cursor.optLong(modifiedIdx)
                val modified = if (modifiedSec > 0) modifiedSec * 1000L else 0L
                val size = cursor.optLong(sizeIdx).coerceAtLeast(0L)
                val width = cursor.optInt(widthIdx).coerceAtLeast(0)
                val height = cursor.optInt(heightIdx).coerceAtLeast(0)
                val bucketId = cursor.optString(bucketIdIdx)
                val bucketNameRaw = cursor.optString(bucketNameIdx).takeIf { it.isNotBlank() }

                val relativePath: String
                val dirForPair: String
                if (api29) {
                    relativePath = cursor.optString(relIdx)
                    dirForPair = relativePath
                } else {
                    @Suppress("DEPRECATION")
                    val dataPath = cursor.optString(dataIdx)
                    val parent = if (dataPath.isNotEmpty()) File(dataPath).parent.orEmpty() else ""
                    relativePath = parent
                    dirForPair = parent
                }

                val bucketName = bucketNameRaw?.takeIf { it.isNotBlank() }
                    ?: dirForPair.trim('/').substringAfterLast('/').ifBlank { "其他" }
                val bucketKey = if (bucketId.isNotBlank()) {
                    "${volume.name}|$bucketId"
                } else {
                    "${volume.name}|${dirForPair.trim('/')}"
                }
                val uri = ContentUris.withAppendedId(volume.uri, id)

                out.add(
                    Photo(
                        id = id,
                        uri = uri,
                        name = name,
                        mime = mime,
                        dateTakenMs = taken,
                        dateModifiedMs = modified,
                        sizeBytes = size,
                        width = width,
                        height = height,
                        bucketKey = bucketKey,
                        bucketName = bucketName,
                        relativePath = relativePath,
                        volumeName = volume.name
                    )
                )
            }
        }
    }

    private fun queryVideos(volume: VolumeSpec, out: MutableMap<String, Uri>) {
        if (Build.VERSION.SDK_INT < 29) return
        val collection = MediaStore.Video.Media.getContentUri(volume.name)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = mediaSelection()
        var candidates = 0
        resolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val durationIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            if (idIdx < 0 || nameIdx < 0 || mimeIdx < 0 || relIdx < 0) {
                Log.w(TAG, "video columns missing for ${volume.name}")
                return@use
            }

            while (cursor.moveToNext()) {
                val name = cursor.optString(nameIdx).takeIf { it.isNotBlank() } ?: continue
                val mime = cursor.optString(mimeIdx)
                if (!mime.startsWith("video/", ignoreCase = true)) continue
                val duration = cursor.optLong(durationIdx)
                val size = cursor.optLong(sizeIdx)
                if (size <= 0) continue
                if (duration > MAX_LIVE_VIDEO_MS) continue
                val rel = cursor.optString(relIdx)
                val videoId = cursor.optLong(idIdx)
                if (videoId <= 0L) continue
                val uri = ContentUris.withAppendedId(collection, videoId)
                candidates++
                pairKeys(volume.name, rel, name).forEach { key -> out.putIfAbsent(key, uri) }
            }
        }
        Log.d(TAG, "videos[${volume.name}] candidates=$candidates map=${out.size}")
    }

    private fun mediaSelection(): String? {
        val clauses = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 29) {
            clauses.add("${MediaStore.MediaColumns.IS_PENDING} = 0")
        }
        if (Build.VERSION.SDK_INT >= 30) {
            clauses.add("${MediaStore.MediaColumns.IS_TRASHED} = 0")
        }
        return if (clauses.isEmpty()) null else clauses.joinToString(" AND ")
    }

    private fun matchesPathFilters(
        photo: Photo,
        includePaths: Set<String>,
        excludePaths: Set<String>
    ): Boolean {
        val dir = photo.relativePath.trim('/')
        val included = includePaths.isEmpty() || includePaths.any { path ->
            dir.equals(path, ignoreCase = true) || dir.startsWith("$path/", ignoreCase = true)
        }
        if (!included) return false
        val excluded = excludePaths.any { path ->
            dir.equals(path, ignoreCase = true) || dir.startsWith("$path/", ignoreCase = true)
        }
        return !excluded
    }

    private fun pairKey(volume: String, dir: String, name: String): String {
        val cleanDir = dir.trim('/').lowercase(Locale.ROOT)
        val base = name.substringBeforeLast('.', name).lowercase(Locale.ROOT)
        return "$volume|$cleanDir|$base"
    }

    /**
     * Vendors name the paired video differently from the still. Register the
     * common variants so IMG_001.jpg can match IMG_001.jpg.mp4, MVIMG_001.mp4
     * or IMG_001_1.mp4, without loosening the match to arbitrary names.
     */
    private fun pairKeys(volume: String, dir: String, name: String): List<String> {
        val result = LinkedHashSet<String>()
        result.add(pairKey(volume, dir, name))
        val cleanDir = dir.trim('/').lowercase(Locale.ROOT)
        val lower = name.lowercase(Locale.ROOT)
        val base = lower.substringBeforeLast('.', lower)
        val inner = base.substringBeforeLast('.', base)
        if (inner != base) result.add("$volume|$cleanDir|$inner")
        listOf(base, inner).forEach { candidate ->
            val withoutSuffix = PAIR_SUFFIX.replace(candidate, "")
            val withoutMv = withoutSuffix.removePrefix("mv")
            if (withoutMv.length >= 4) result.add("$volume|$cleanDir|$withoutMv")
        }
        return result.toList()
    }

    private fun Cursor.optString(index: Int): String =
        if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

    private fun Cursor.optLong(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private fun Cursor.optInt(index: Int): Int =
        if (index >= 0 && !isNull(index)) getInt(index) else 0

    private companion object {
        const val TAG = "SwiftPicRepo"
        const val MAX_LIVE_VIDEO_MS = 15_000L
        val PAIR_SUFFIX = Regex("""[_-]\d+$""")
    }
}
