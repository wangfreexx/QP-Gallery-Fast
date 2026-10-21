package com.qoder.swiftpic.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageExporter {

    fun saveJpegCopy(
        context: Context,
        bitmap: Bitmap,
        sourceName: String,
        dateTakenMs: Long
    ): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "SwiftPic" }
        val displayName = "${base}_edit_$stamp.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SwiftPic/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (dateTakenMs > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMs)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    throw IOException("compress failed")
                }
            } ?: throw IOException("open destination failed")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
    }
}
