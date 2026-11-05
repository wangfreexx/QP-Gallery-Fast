package com.qoder.swiftpic.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

enum class AlbumSortMode {
    LATEST,
    NAME,
    MANUAL,
    OLDEST,
    NAME_DESC;

    companion object {
        fun fromOrdinal(value: Int): AlbumSortMode = values().getOrElse(value) { LATEST }
    }
}

/**
 * All user annotations (favorites, tags, covers, ordering) live here.
 * Every read/write is guarded by [lock]: the UI thread and background refreshes
 * both touch this store. Getters always return defensive copies.
 */
class UserDataStore(context: Context) {

    private val sp = context.getSharedPreferences("swiftpic_user_data", Context.MODE_PRIVATE)
    private val lock = Any()
    private val versionCounter = AtomicInteger(0)

    private val favoritesSet: MutableSet<String> =
        (sp.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()).toMutableSet()
    private val pinnedSet: MutableSet<String> =
        (sp.getStringSet(KEY_PINNED, emptySet()) ?: emptySet()).toMutableSet()

    private val tagsMap: MutableMap<String, MutableList<String>> = loadTags(KEY_TAGS)
    private val albumTagsMap: MutableMap<String, MutableList<String>> = loadTags(KEY_ALBUM_TAGS)
    private val coversMap: MutableMap<String, String> = loadJsonObject(KEY_COVERS)
    private val orderList: MutableList<String> = loadOrder()

    /** Pinned albums in display order: the most recently pinned comes first. */
    private val pinnedOrderList: MutableList<String> = loadKeyList(KEY_PINNED_ORDER)

    init {
        var changed = pinnedOrderList.removeAll { !pinnedSet.contains(it) }
        pinnedSet.forEach { key ->
            if (!pinnedOrderList.contains(key)) {
                pinnedOrderList.add(key)
                changed = true
            }
        }
        if (changed) savePinned()
    }

    val version: Int get() = versionCounter.get()

    val favoriteKeys: Set<String>
        get() = synchronized(lock) { favoritesSet.toSet() }

    fun isFavorite(photo: Photo): Boolean = synchronized(lock) {
        favoritesSet.contains(photo.stableKey)
    }

    fun favoriteCount(): Int = synchronized(lock) { favoritesSet.size }

    /** @return new favorite state. */
    fun toggleFavorite(photo: Photo): Boolean = synchronized(lock) {
        val key = photo.stableKey
        val added = if (favoritesSet.contains(key)) {
            favoritesSet.remove(key)
            false
        } else {
            favoritesSet.add(key)
            true
        }
        sp.edit().putStringSet(KEY_FAVORITES, HashSet(favoritesSet)).apply()
        bump()
        added
    }

    fun setFavorite(photos: Collection<Photo>, favorite: Boolean) = synchronized(lock) {
        photos.forEach { photo ->
            if (favorite) favoritesSet.add(photo.stableKey)
            else favoritesSet.remove(photo.stableKey)
        }
        sp.edit().putStringSet(KEY_FAVORITES, HashSet(favoritesSet)).apply()
        bump()
    }

    fun tagsFor(photo: Photo): List<String> = tagsFor(photo.stableKey)

    fun tagsFor(key: String): List<String> = synchronized(lock) {
        tagsMap[key]?.toList().orEmpty()
    }

    fun setTags(photos: Collection<Photo>, tags: List<String>) = synchronized(lock) {
        val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        photos.forEach { photo ->
            if (normalized.isEmpty()) tagsMap.remove(photo.stableKey)
            else tagsMap[photo.stableKey] = normalized.toMutableList()
        }
        saveTags(KEY_TAGS, tagsMap)
        bump()
    }

    fun allTags(): Set<String> = synchronized(lock) {
        val result = HashSet<String>()
        tagsMap.values.forEach { result.addAll(it) }
        result
    }

    fun albumTags(albumKey: String): List<String> = synchronized(lock) {
        albumTagsMap[albumKey]?.toList().orEmpty()
    }

    fun setAlbumTags(albumKey: String, tags: List<String>) = synchronized(lock) {
        val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) albumTagsMap.remove(albumKey)
        else albumTagsMap[albumKey] = normalized.toMutableList()
        saveAlbumTags()
        bump()
    }

    fun isPinned(albumKey: String): Boolean = synchronized(lock) {
        pinnedSet.contains(albumKey)
    }

    /** Pinned albums, newest pin first. */
    fun pinnedKeys(): List<String> = synchronized(lock) { pinnedOrderList.toList() }

    fun setPinned(albumKey: String, pinned: Boolean) = synchronized(lock) {
        if (pinned) {
            pinnedSet.add(albumKey)
            pinnedOrderList.remove(albumKey)
            pinnedOrderList.add(0, albumKey)
        } else {
            pinnedSet.remove(albumKey)
            pinnedOrderList.remove(albumKey)
        }
        savePinned()
        bump()
    }

    fun setPinnedOrder(keys: List<String>) = synchronized(lock) {
        pinnedOrderList.clear()
        keys.forEach { key ->
            if (pinnedSet.contains(key) && !pinnedOrderList.contains(key)) {
                pinnedOrderList.add(key)
            }
        }
        // Anything pinned before this order existed stays at the end.
        pinnedSet.forEach { key ->
            if (!pinnedOrderList.contains(key)) pinnedOrderList.add(key)
        }
        savePinned()
        bump()
    }

    fun albumCover(albumKey: String): String? = synchronized(lock) { coversMap[albumKey] }

    fun setAlbumCover(albumKey: String, photo: Photo) = synchronized(lock) {
        coversMap[albumKey] = photo.stableKey
        saveJsonObject(KEY_COVERS, coversMap)
        bump()
    }

    fun clearAlbumCover(albumKey: String) = synchronized(lock) {
        if (coversMap.remove(albumKey) != null) {
            saveJsonObject(KEY_COVERS, coversMap)
            bump()
        }
    }

    var albumSort: AlbumSortMode
        get() = synchronized(lock) {
            sp.getString(KEY_ALBUM_SORT_NAME, null)?.let { saved ->
                AlbumSortMode.values().firstOrNull { it.name == saved }
                    ?.let { return@synchronized it }
            }
            AlbumSortMode.fromOrdinal(sp.getInt(KEY_ALBUM_SORT, AlbumSortMode.LATEST.ordinal))
        }
        set(value) = synchronized(lock) {
            sp.edit()
                .putString(KEY_ALBUM_SORT_NAME, value.name)
                .putInt(KEY_ALBUM_SORT, value.ordinal)
                .apply()
            bump()
        }

    fun albumOrder(): List<String> = synchronized(lock) { orderList.toList() }

    fun setAlbumOrder(order: List<String>) = synchronized(lock) {
        orderList.clear()
        orderList.addAll(order)
        val array = JSONArray()
        orderList.forEach { array.put(it) }
        sp.edit().putString(KEY_ORDER, array.toString()).apply()
        albumSort = AlbumSortMode.MANUAL
    }

    /**
     * One-time migration from the legacy "volume|path|name" photo key to the
     * MediaStore _ID based key. Called after a successful gallery scan; if the
     * library is empty the migration is deferred to the next scan.
     */
    fun migrateLegacyKeys(photos: Collection<Photo>) {
        if (sp.getBoolean(KEY_MIGRATED_STABLE_KEYS, false)) return
        val mapping = HashMap<String, String>()
        photos.forEach { photo ->
            if (photo.id > 0L) mapping[photo.legacyStableKey] = photo.stableKey
        }
        if (mapping.isEmpty()) return
        synchronized(lock) {
            if (sp.getBoolean(KEY_MIGRATED_STABLE_KEYS, false)) return
            val migratedFavorites = favoritesSet.mapTo(HashSet()) { mapping[it] ?: it }
            val favoritesChanged = migratedFavorites != favoritesSet
            favoritesSet.clear()
            favoritesSet.addAll(migratedFavorites)

            val migratedTags = LinkedHashMap<String, MutableList<String>>()
            tagsMap.forEach { (key, value) -> migratedTags[mapping[key] ?: key] = value }
            tagsMap.clear()
            tagsMap.putAll(migratedTags)

            val migratedCovers = HashMap<String, String>()
            coversMap.forEach { (key, value) -> migratedCovers[key] = mapping[value] ?: value }
            coversMap.clear()
            coversMap.putAll(migratedCovers)

            if (favoritesChanged) {
                sp.edit().putStringSet(KEY_FAVORITES, HashSet(favoritesSet)).apply()
            }
            saveTags(KEY_TAGS, tagsMap)
            saveJsonObject(KEY_COVERS, coversMap)
            sp.edit().putBoolean(KEY_MIGRATED_STABLE_KEYS, true).apply()
            bump()
            Log.i(TAG, "migrated ${mapping.size} legacy photo keys")
        }
    }

    private fun bump() {
        versionCounter.incrementAndGet()
    }

    private fun loadTags(name: String): MutableMap<String, MutableList<String>> {
        val result = HashMap<String, MutableList<String>>()
        val raw = sp.getString(name, null) ?: return result
        try {
            val obj = JSONObject(raw)
            obj.keys().forEach { key ->
                val array = obj.optJSONArray(key) ?: return@forEach
                val list = ArrayList<String>(array.length())
                for (i in 0 until array.length()) {
                    val value = array.optString(i)
                    if (value.isNotBlank()) list.add(value)
                }
                if (list.isNotEmpty()) result[key] = list
            }
        } catch (e: Exception) {
            Log.w(TAG, "corrupt $name payload", e)
            backupCorrupt(name, raw)
        }
        return result
    }

    private fun saveTags(name: String, map: Map<String, List<String>>) {
        val obj = JSONObject()
        map.forEach { (key, tags) ->
            val array = JSONArray()
            tags.forEach { array.put(it) }
            obj.put(key, array)
        }
        sp.edit().putString(name, obj.toString()).apply()
    }

    private fun saveAlbumTags() = saveTags(KEY_ALBUM_TAGS, albumTagsMap)

    private fun loadJsonObject(name: String): MutableMap<String, String> {
        val result = HashMap<String, String>()
        val raw = sp.getString(name, null) ?: return result
        try {
            val obj = JSONObject(raw)
            obj.keys().forEach { key ->
                val value = obj.optString(key)
                if (value.isNotBlank()) result[key] = value
            }
        } catch (e: Exception) {
            Log.w(TAG, "corrupt $name payload", e)
            backupCorrupt(name, raw)
        }
        return result
    }

    private fun saveJsonObject(name: String, value: Map<String, String>) {
        val obj = JSONObject()
        value.forEach { (k, v) -> obj.put(k, v) }
        sp.edit().putString(name, obj.toString()).apply()
    }

    private fun loadOrder(): MutableList<String> {
        val result = ArrayList<String>()
        val raw = sp.getString(KEY_ORDER, null) ?: return result
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) result.add(value)
            }
        } catch (e: Exception) {
            Log.w(TAG, "corrupt $KEY_ORDER payload", e)
            backupCorrupt(KEY_ORDER, raw)
        }
        return result
    }

    private fun savePinned() {
        val array = JSONArray()
        pinnedOrderList.forEach { array.put(it) }
        sp.edit()
            .putStringSet(KEY_PINNED, HashSet(pinnedSet))
            .putString(KEY_PINNED_ORDER, array.toString())
            .apply()
    }

    private fun loadKeyList(name: String): MutableList<String> {
        val result = ArrayList<String>()
        val raw = sp.getString(name, null) ?: return result
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) result.add(value)
            }
        } catch (e: Exception) {
            Log.w(TAG, "corrupt $name payload", e)
            backupCorrupt(name, raw)
        }
        return result
    }

    private fun backupCorrupt(name: String, raw: String) {
        sp.edit().putString("${name}_corrupt_backup", raw.take(MAX_BACKUP_CHARS)).apply()
    }

    private companion object {
        const val TAG = "SwiftPicUserData"
        const val MAX_BACKUP_CHARS = 512 * 1024
        const val KEY_FAVORITES = "favorites"
        const val KEY_PINNED = "pinned_albums"
        const val KEY_PINNED_ORDER = "pinned_order"
        const val KEY_TAGS = "tags"
        const val KEY_ALBUM_TAGS = "album_tags"
        const val KEY_COVERS = "album_covers"
        const val KEY_ORDER = "album_order"
        const val KEY_ALBUM_SORT = "album_sort"
        const val KEY_ALBUM_SORT_NAME = "album_sort_name"
        const val KEY_MIGRATED_STABLE_KEYS = "migrated_stable_keys_v2"
    }
}
