package com.qoder.swiftpic.data

import java.util.Locale

object GalleryKeys {
    const val FAVORITES = "@favorites"
    const val SEARCH = "@search"
}

object GalleryFilters {

    fun favoritePhotos(photos: List<Photo>, userData: UserDataStore): List<Photo> {
        if (userData.favoriteKeys.isEmpty()) return emptyList()
        return photos.filter { userData.isFavorite(it) }
    }

    fun searchPhotos(
        photos: List<Photo>,
        userData: UserDataStore,
        query: String
    ): List<Photo> {
        val needle = query.trim().lowercase(Locale.getDefault())
        if (needle.isEmpty()) return emptyList()
        return photos.filter { photo ->
            photo.name.lowercase(Locale.getDefault()).contains(needle) ||
                photo.bucketName.lowercase(Locale.getDefault()).contains(needle) ||
                photo.relativePath.lowercase(Locale.getDefault()).contains(needle) ||
                userData.tagsFor(photo).any { it.lowercase(Locale.getDefault()).contains(needle) }
        }
    }

    fun photosFor(
        library: GalleryLibrary,
        userData: UserDataStore,
        albumKey: String?,
        query: String?,
        sortMode: SortMode
    ): List<Photo> {
        val list = when (albumKey) {
            GalleryKeys.FAVORITES -> favoritePhotos(library.photos, userData)
            GalleryKeys.SEARCH -> searchPhotos(library.photos, userData, query.orEmpty())
            null -> library.photos
            else -> library.albums.firstOrNull { it.key == albumKey }?.photos.orEmpty()
        }
        return sortPhotos(list, sortMode)
    }

    fun buildVisibleAlbums(photos: List<Photo>): List<Album> {
        val groups = LinkedHashMap<String, MutableList<Photo>>()
        photos.forEach { photo ->
            groups.getOrPut(photo.bucketKey) { ArrayList() }.add(photo)
        }
        return groups.map { (key, list) ->
            Album(key = key, name = list.first().bucketName, photos = list)
        }.sortedByDescending { it.latestTime }
    }
}
