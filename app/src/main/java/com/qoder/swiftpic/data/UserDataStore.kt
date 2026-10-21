package com.qoder.swiftpic.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

class UserDataStore(context: Context) {

    private val sp = context.getSharedPreferences("swiftpic_user_data", Context.MODE_PRIVATE)

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
        pinnedOrderList.removeAll { !pinnedSet.contains(it) }
        pinnedSet.forEach { key ->
            if (!pinnedOrderList.contains(key)) pinnedOrderList.add(key)
        }
    }

    @Volatile
    var version: Int = 0
        private set

    val favoriteKeys: Set<String> get() = favoritesSet

    fun isFavorite(photo: Photo): Boolean = favoritesSet.contains(photo.stableKey)

    fun favoriteCount(): Int = favoritesSet.size

    /** @return new favorite state. */
    fun toggleFavorite(photo: Photo): Boolean {
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
        return added
    }

    fun setFavorite(photos: Collection<Photo>, favorite: Boolean) {
        photos.forEach { photo ->
            if (favorite) favoritesSet.add(photo.stableKey)
            else favoritesSet.remove(photo.stableKey)
        }
        sp.edit().putStringSet(KEY_FAVORITES, HashSet(favoritesSet)).apply()
        bump()
    }

    fun tagsFor(photo: Photo): List<String> = tagsMap[photo.stableKey].orEmpty()

    fun tagsFor(key: String): List<String> = tagsMap[key].orEmpty()

    fun setTags(photos: Collection<Photo>, tags: List<String>) {
        val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        photos.forEach { photo ->
            if (normalized.isEmpty()) tagsMap.remove(photo.stableKey)
            else tagsMap[photo.stableKey] = normalized.toMutableList()
        }
        saveTags(KEY_TAGS, tagsMap)
        bump()
    }

    fun allTags(): Set<String> {
        val result = HashSet<String>()
        tagsMap.values.forEach { result.addAll(it) }
        return result
    }

    fun albumTags(albumKey: String): List<String> = albumTagsMap[albumKey].orEmpty()

    fun setAlbumTags(albumKey: String, tags: List<String>) {
        val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) albumTagsMap.remove(albumKey)
        else albumTagsMap[albumKey] = normalized.toMutableList()
        saveAlbumTags()
        bump()
    }

    fun isPinned(albumKey: String): Boolean = pinnedSet.contains(albumKey)

    /** Pinned albums, newest pin first. */
    fun pinnedKeys(): List<String> = pinnedOrderList.toList()

    fun setPinned(albumKey: String, pinned: Boolean) {
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

    fun setPinnedOrder(keys: List<String>) {
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

    fun albumCover(albumKey: String): String? = coversMap[albumKey]

    fun setAlbumCover(albumKey: String, photo: Photo) {
        coversMap[albumKey] = photo.stableKey
        saveJsonObject(KEY_COVERS, coversMap)
        bump()
    }

    var albumSort: AlbumSortMode
        get() = AlbumSortMode.fromOrdinal(sp.getInt(KEY_ALBUM_SORT, AlbumSortMode.LATEST.ordinal))
        set(value) {
            sp.edit().putInt(KEY_ALBUM_SORT, value.ordinal).apply()
            bump()
        }

    fun albumOrder(): List<String> = orderList.toList()

    fun setAlbumOrder(order: List<String>) {
        orderList.clear()
        orderList.addAll(order)
        val array = JSONArray()
        orderList.forEach { array.put(it) }
        sp.edit().putString(KEY_ORDER, array.toString()).apply()
        albumSort = AlbumSortMode.MANUAL
    }

    private fun bump() {
        version++
    }

    private fun loadTags(name: String): MutableMap<String, MutableList<String>> {
        val result = HashMap<String, MutableList<String>>()
        val raw = sp.getString(name, null) ?: return result
        runCatching {
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
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { key ->
                val value = obj.optString(key)
                if (value.isNotBlank()) result[key] = value
            }
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
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) result.add(value)
            }
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
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) result.add(value)
            }
        }
        return result
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_PINNED = "pinned_albums"
        const val KEY_PINNED_ORDER = "pinned_order"
        const val KEY_TAGS = "tags"
        const val KEY_ALBUM_TAGS = "album_tags"
        const val KEY_COVERS = "album_covers"
        const val KEY_ORDER = "album_order"
        const val KEY_ALBUM_SORT = "album_sort"
    }
}
