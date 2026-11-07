package com.qoder.swiftpic.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AlbumDigest(
    val key: String,
    val name: String,
    val count: Int,
    val cover: Photo?
)

data class GallerySnapshot(
    val photoCount: Int,
    val cover: Photo?,
    val favoritesCount: Int,
    val favoritesCover: Photo?,
    val albums: List<AlbumDigest>
)

/**
 * A tiny on-disk snapshot of the previous scan, used only to paint the home
 * screen before MediaStore has been re-scanned on a cold start. It stores one
 * cover per album (a few KB), never the whole photo list, so restoring it is
 * far cheaper than scanning tens of thousands of rows.
 */
class GallerySnapshotStore(context: Context) {

    private val file = File(context.filesDir, SNAPSHOT_FILE)
    private val tmp = File(context.filesDir, "$SNAPSHOT_FILE.tmp")

    fun save(library: GalleryLibrary, favorites: List<Photo>) {
        try {
            val root = JSONObject()
            root.put("v", VERSION)
            root.put("photoCount", library.photos.size)
            library.photos.firstOrNull()?.let { root.put("cover", it.toJson()) }
            root.put("favoritesCount", favorites.size)
            favorites.firstOrNull()?.let { root.put("favoritesCover", it.toJson()) }
            val albums = JSONArray()
            library.albums.forEach { album ->
                val obj = JSONObject()
                    .put("key", album.key)
                    .put("name", album.name)
                    .put("count", album.count)
                album.cover?.let { obj.put("cover", it.toJson()) }
                albums.put(obj)
            }
            root.put("albums", albums)
            tmp.writeText(root.toString())
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "save snapshot failed", e)
            tmp.delete()
        }
    }

    fun restore(): GallerySnapshot? {
        return try {
            if (!file.isFile) return null
            val root = JSONObject(file.readText())
            if (root.optInt("v") != VERSION) return null
            val array = root.optJSONArray("albums") ?: return null
            val albums = ArrayList<AlbumDigest>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val key = obj.optString("key")
                if (key.isBlank()) continue
                albums.add(
                    AlbumDigest(
                        key = key,
                        name = obj.optString("name"),
                        count = obj.optInt("count"),
                        cover = obj.optJSONObject("cover")?.toPhoto()
                    )
                )
            }
            if (albums.isEmpty()) return null
            GallerySnapshot(
                photoCount = root.optInt("photoCount"),
                cover = root.optJSONObject("cover")?.toPhoto(),
                favoritesCount = root.optInt("favoritesCount"),
                favoritesCover = root.optJSONObject("favoritesCover")?.toPhoto(),
                albums = albums
            )
        } catch (e: Exception) {
            Log.w(TAG, "restore snapshot failed", e)
            null
        }
    }

    private fun Photo.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("uri", uri.toString())
        .put("name", name)
        .put("mime", mime)
        .put("taken", dateTakenMs)
        .put("modified", dateModifiedMs)
        .put("size", sizeBytes)
        .put("width", width)
        .put("height", height)
        .put("bucketKey", bucketKey)
        .put("bucketName", bucketName)
        .put("relativePath", relativePath)
        .put("volume", volumeName)

    private fun JSONObject.toPhoto(): Photo? {
        val rawUri = optString("uri")
        if (rawUri.isBlank()) return null
        return Photo(
            id = optLong("id"),
            uri = Uri.parse(rawUri),
            name = optString("name"),
            mime = optString("mime"),
            dateTakenMs = optLong("taken"),
            dateModifiedMs = optLong("modified"),
            sizeBytes = optLong("size"),
            width = optInt("width"),
            height = optInt("height"),
            bucketKey = optString("bucketKey"),
            bucketName = optString("bucketName"),
            relativePath = optString("relativePath"),
            volumeName = optString("volume")
        )
    }

    private companion object {
        const val TAG = "SwiftPicSnapshot"
        const val SNAPSHOT_FILE = "gallery_snapshot.json"
        const val VERSION = 1
    }
}
