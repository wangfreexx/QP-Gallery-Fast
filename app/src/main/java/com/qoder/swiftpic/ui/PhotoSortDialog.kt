package com.qoder.swiftpic.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.AppPrefs
import com.qoder.swiftpic.data.SortMode

val photoSortModes = listOf(
    SortMode.DATE_DESC,
    SortMode.DATE_ASC,
    SortMode.NAME_ASC,
    SortMode.NAME_DESC
)

fun photoSortLabel(context: Context, mode: SortMode): String = context.getString(
    when (mode) {
        SortMode.DATE_DESC -> R.string.sort_date_desc
        SortMode.DATE_ASC -> R.string.sort_date_asc
        SortMode.NAME_ASC -> R.string.sort_name_asc
        SortMode.NAME_DESC -> R.string.sort_name_desc_photos
    }
)

/** Shared by the album toolbar and Settings so both edit the same preference. */
fun showPhotoSortDialog(context: Context, prefs: AppPrefs, onPicked: (SortMode) -> Unit) {
    val labels = photoSortModes.map { photoSortLabel(context, it) }.toTypedArray()
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.sort)
        .setSingleChoiceItems(labels, photoSortModes.indexOf(prefs.sortMode)) { dialog, which ->
            val mode = photoSortModes[which]
            prefs.sortMode = mode
            dialog.dismiss()
            onPicked(mode)
        }
        .showMotion()
}
