package com.qoder.swiftpic.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.AlbumSortMode
import com.qoder.swiftpic.data.UserDataStore

val albumSortModes = listOf(
    AlbumSortMode.LATEST,
    AlbumSortMode.OLDEST,
    AlbumSortMode.NAME,
    AlbumSortMode.NAME_DESC,
    AlbumSortMode.MANUAL
)

fun albumSortLabel(context: Context, mode: AlbumSortMode): String = context.getString(
    when (mode) {
        AlbumSortMode.LATEST -> R.string.sort_latest
        AlbumSortMode.OLDEST -> R.string.sort_oldest
        AlbumSortMode.NAME -> R.string.sort_name
        AlbumSortMode.NAME_DESC -> R.string.sort_name_desc
        AlbumSortMode.MANUAL -> R.string.sort_manual
    }
)

/** Shared by the home toolbar button and Settings so both edit the same preference. */
fun showAlbumSortDialog(context: Context, userData: UserDataStore, onChanged: () -> Unit) {
    val labels = albumSortModes.map { albumSortLabel(context, it) }.toTypedArray()
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.album_sort)
        .setSingleChoiceItems(labels, albumSortModes.indexOf(userData.albumSort)) { dialog, which ->
            userData.albumSort = albumSortModes[which]
            dialog.dismiss()
            onChanged()
        }
        .showMotion()
}
