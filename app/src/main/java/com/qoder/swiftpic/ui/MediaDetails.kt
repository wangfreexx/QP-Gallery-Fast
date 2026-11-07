package com.qoder.swiftpic.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.Album
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.swiftPic
import com.qoder.swiftpic.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared "path + details" sheet used by long-press menus on albums and photos. */
object MediaDetails {

    fun showPhoto(activity: AppCompatActivity, photo: Photo) {
        activity.lifecycleScope.launch {
            val exif = withContext(Dispatchers.IO) { readExif(activity, photo) }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val tags = activity.swiftPic.userData.tagsFor(photo)
            val message = buildString {
                row(activity, R.string.detail_path, photo.displayPath)
                row(activity, R.string.detail_name, photo.name)
                row(activity, R.string.detail_size, Formats.size(activity, photo.sizeBytes))
                row(
                    activity,
                    R.string.detail_dimensions,
                    Formats.dimensions(photo.width, photo.height)
                )
                row(activity, R.string.detail_date, Formats.dateTime(photo.sortTimeMs))
                row(
                    activity,
                    R.string.detail_type,
                    photo.mime.ifBlank { activity.getString(R.string.unknown) }
                )
                row(activity, R.string.detail_id, photo.id.toString())
                row(activity, R.string.detail_volume, photo.volumeName)
                row(activity, R.string.detail_bucket, photo.bucketKey)
                row(
                    activity,
                    R.string.detail_tags,
                    if (tags.isEmpty()) activity.getString(R.string.tags_none)
                    else tags.joinToString("、")
                )
                row(activity, R.string.detail_uri, photo.uri.toString())
                exif.forEach { (label, value) -> append(label).append("：").append(value).append('\n') }
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.detail_title)
                .setMessage(message.trimEnd('\n'))
                .setPositiveButton(R.string.close, null)
                .showMotion()
        }
    }

    fun showAlbum(activity: AppCompatActivity, album: Album) {
        val paths = album.photos
            .map { it.relativePath.trim('/') }
            .filter { it.isNotEmpty() }
            .distinct()
        val pathText = when {
            paths.isEmpty() -> activity.getString(R.string.unknown)
            paths.size == 1 -> paths.first()
            else -> paths.take(5).joinToString("\n") +
                if (paths.size > 5) "\n… (+${paths.size - 5})" else ""
        }
        val totalSize = album.photos.sumOf { it.sizeBytes }
        val times = album.photos.map { it.sortTimeMs }.filter { it > 0 }
        val range = if (times.isEmpty()) {
            activity.getString(R.string.unknown)
        } else {
            Formats.dateTime(times.min()) + " ~ " + Formats.dateTime(times.max())
        }
        val tags = activity.swiftPic.userData.albumTags(album.key)
        val message = buildString {
            row(activity, R.string.detail_album_path, pathText)
            row(activity, R.string.detail_name, album.name)
            row(activity, R.string.detail_photo_count, album.count.toString())
            row(activity, R.string.detail_total_size, Formats.size(activity, totalSize))
            row(activity, R.string.detail_date_range, range)
            row(
                activity,
                R.string.detail_cover,
                album.cover?.displayPath ?: activity.getString(R.string.unknown)
            )
            row(activity, R.string.detail_bucket, album.key)
            row(
                activity,
                R.string.detail_tags,
                if (tags.isEmpty()) activity.getString(R.string.tags_none)
                else tags.joinToString("、")
            )
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.album_details)
            .setMessage(message.trimEnd('\n'))
            .setPositiveButton(R.string.close, null)
            .showMotion()
    }

    private fun StringBuilder.row(activity: AppCompatActivity, labelRes: Int, value: String) {
        append(activity.getString(labelRes)).append("：").append(value).append('\n')
    }

    private fun readExif(activity: AppCompatActivity, photo: Photo): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            activity.contentResolver.openInputStream(photo.uri)?.use { input ->
                val exif = ExifInterface(input)
                val camera = listOfNotNull(
                    exif.getAttribute(ExifInterface.TAG_MAKE)?.takeIf { it.isNotBlank() },
                    exif.getAttribute(ExifInterface.TAG_MODEL)?.takeIf { it.isNotBlank() }
                ).joinToString(" ")
                if (camera.isNotBlank()) {
                    out.add(activity.getString(R.string.exif_camera) to camera)
                }
                exif.getAttribute(ExifInterface.TAG_LENS_MODEL)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out.add(activity.getString(R.string.exif_lens) to it) }
                val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    ?: exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                iso?.takeIf { it.isNotBlank() }
                    ?.let { out.add(activity.getString(R.string.exif_iso) to it) }
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out.add(activity.getString(R.string.exif_exposure) to it) }
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out.add(activity.getString(R.string.exif_aperture) to it) }
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out.add(activity.getString(R.string.exif_focal) to it) }
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out.add(activity.getString(R.string.exif_datetime) to it) }
                @Suppress("DEPRECATION")
                val latLong = FloatArray(2)
                @Suppress("DEPRECATION")
                if (exif.getLatLong(latLong)) {
                    out.add(
                        activity.getString(R.string.exif_gps) to
                            "%.5f, %.5f".format(latLong[0], latLong[1])
                    )
                }
            }
        } catch (e: Exception) {
            // Not every format carries EXIF; the basic fields above are still useful.
        }
        return out
    }
}
