package com.qoder.swiftpic.data

import java.util.Locale

fun sortPhotos(photos: List<Photo>, mode: SortMode): List<Photo> = when (mode) {
    SortMode.DATE_DESC -> photos.sortedWith(
        compareByDescending<Photo> { it.sortTimeMs }.thenByDescending { it.id }
    )
    SortMode.DATE_ASC -> photos.sortedWith(
        compareBy<Photo> { it.sortTimeMs }.thenBy { it.id }
    )
    SortMode.NAME_ASC -> {
        val collator = java.text.Collator.getInstance(Locale.CHINA)
        photos.sortedWith { a, b ->
            val c = collator.compare(a.name, b.name)
            if (c != 0) c else a.id.compareTo(b.id)
        }
    }
    SortMode.NAME_DESC -> {
        val collator = java.text.Collator.getInstance(Locale.CHINA)
        photos.sortedWith { a, b ->
            val c = collator.compare(b.name, a.name)
            if (c != 0) c else b.id.compareTo(a.id)
        }
    }
}
