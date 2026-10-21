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

class GalleryRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val prefs = AppPrefs(context)
    private val scanMutex = Mutex()

    @Volatile
    private var cached: GalleryLibrary? = null

    @Volatile
    private var dirty = true

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            dirty = true
        }
    }

    init {
        runCatching {
            resolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        }
        runCatching {
            resolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        }
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                resolver.registerContentObserver(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    true,
                    observer
                )
            }
        }
    }

    fun isDirty(): Boolean = dirty

    fun peek(): GalleryLibrary? = cached

    fun markDirty() {
        dirty = true
    }

    suspend fun load(force: Boolean = false): GalleryLibrary {
        val snapshot = cached
        if (!force && !dirty && snapshot != null) return snapshot
        return scanMutex.withLock {
            if (!force && !dirty) {
                cached?.let { return@withLock it }
            }
            val started = System.currentTimeMillis()
            val library = withContext(Dispatchers.IO) { scan() }
            cached = library
            dirty = false
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
            runCatching {
                if (canReadVideos) {
                    queryVideos(volume, videoMap)
                }
            }.onFailure {
                hadError = true
                Log.w(TAG, "video query failed for ${volume.name}", it)
            }
        }

        Log.d(TAG, "volumes=${volumes.map { it.name }} videos=${videoMap.size}")

        val photos = ArrayList<Photo>(2048)
        volumes.forEach { volume ->
            runCatching {
                queryImages(volume, photos)
            }.onFailure {
                hadError = true
                Log.w(TAG, "image query failed for ${volume.name}", it)
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
            scanError = hadError && photos.isEmpty()
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
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val takenIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val modifiedIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val bucketIdIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val relIdx = if (api29) {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                -1
            }
            @Suppress("DEPRECATION")
            val dataIdx = if (api29) -1 else cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: continue
                val mime = cursor.getString(mimeIdx).orEmpty()
                if (!mime.startsWith("image/", ignoreCase = true)) continue

                val taken = cursor.getLong(takenIdx)
                val modifiedSec = cursor.getLong(modifiedIdx)
                val modified = if (modifiedSec > 0) modifiedSec * 1000L else 0L
                val size = cursor.getLong(sizeIdx)
                val width = if (cursor.isNull(widthIdx)) 0 else cursor.getInt(widthIdx)
                val height = if (cursor.isNull(heightIdx)) 0 else cursor.getInt(heightIdx)
                val bucketId = cursor.getString(bucketIdIdx).orEmpty()
                val bucketNameRaw = cursor.getString(bucketNameIdx)

                val relativePath: String
                val dirForPair: String
                if (api29) {
                    relativePath = cursor.getString(relIdx).orEmpty()
                    dirForPair = relativePath
                } else {
                    @Suppress("DEPRECATION")
                    val dataPath = cursor.getString(dataIdx).orEmpty()
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
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val relIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: continue
                val mime = cursor.getString(mimeIdx).orEmpty()
                if (!mime.startsWith("video/", ignoreCase = true)) continue
                val duration = cursor.getLong(durationIdx)
                val size = cursor.getLong(sizeIdx)
                if (size <= 0) continue
                if (duration > MAX_LIVE_VIDEO_MS) continue
                val rel = cursor.getString(relIdx).orEmpty()
                val key = pairKey(volume.name, rel, name)
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIdx))
                candidates++
                out.putIfAbsent(key, uri)
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

    private companion object {
        const val TAG = "SwiftPicRepo"
        const val MAX_LIVE_VIDEO_MS = 15_000L
    }
}
