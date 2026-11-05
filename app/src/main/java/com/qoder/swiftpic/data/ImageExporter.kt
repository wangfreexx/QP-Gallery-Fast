package com.qoder.swiftpic.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Saves an edited bitmap as a **new** MediaStore record. The original file is
 * never touched. Output format follows the source: transparent PNG/WebP/AVIF
 * keeps its alpha via lossless PNG, everything else is JPEG with the source
 * EXIF copied over.
 */
object ImageExporter {

    private const val TAG = "SwiftPicExport"
    private const val JPEG_QUALITY = 95
    private const val PENDING_TTL_SECONDS = 24 * 60 * 60L

    /**
     * @param sourceMime MIME type of the original photo, used to pick an output format.
     * @param sourceUri original content URI, used to copy EXIF metadata.
     * @param preserveAlpha whether the decoded bitmap actually carries an alpha channel.
     */
    fun saveJpegCopy(
        context: Context,
        bitmap: Bitmap,
        sourceName: String,
        dateTakenMs: Long,
        sourceMime: String = "image/jpeg",
        sourceUri: Uri? = null,
        preserveAlpha: Boolean = false
    ): Uri? {
        val format = chooseFormat(sourceMime, preserveAlpha)
        val isPng = format == Bitmap.CompressFormat.PNG
        val extension = if (isPng) "png" else "jpg"
        val mimeType = if (isPng) "image/png" else "image/jpeg"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "SwiftPic" }
        val displayName = "${base}_edit_$stamp.$extension"

        val temp = File(context.cacheDir, "export_${UUID.randomUUID()}.tmp")
        var inserted: Uri? = null
        var ownedBitmap: Bitmap? = null
        return try {
            // HARDWARE bitmaps cannot be read back by Bitmap.compress().
            val work = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)?.also { ownedBitmap = it }
                    ?: throw IOException("cannot convert hardware bitmap")
            } else {
                bitmap
            }
            val compressed = FileOutputStream(temp).use { output ->
                work.compress(format, JPEG_QUALITY, output)
            }
            if (!compressed || temp.length() <= 0L) throw IOException("compress failed")

            // Re-encoding loses all metadata; copy the useful tags back for JPEG.
            if (!isPng && sourceUri != null) {
                copyExif(context, sourceUri, temp, work.width, work.height)
            }

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (dateTakenMs > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMs)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SwiftPic/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    // If the process dies before publishing, the system reclaims the row.
                    put(
                        MediaStore.MediaColumns.DATE_EXPIRES,
                        System.currentTimeMillis() / 1000L + PENDING_TTL_SECONDS
                    )
                } else {
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, legacyPath(displayName))
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, values)
                ?: throw IOException("insert failed")
            inserted = uri

            resolver.openOutputStream(uri)?.use { output ->
                temp.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("open destination failed")

            if (Build.VERSION.SDK_INT >= 29) {
                val publish = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val rows = resolver.update(uri, publish, null, null)
                if (rows != 1) {
                    throw IOException("publish failed: update returned $rows")
                }
            }
            Log.d(TAG, "saved $displayName ($mimeType)")
            uri
        } catch (e: Exception) {
            Log.w(TAG, "export failed for $sourceName", e)
            inserted?.let { stale ->
                runCatching { context.contentResolver.delete(stale, null, null) }
                    .onFailure { Log.w(TAG, "rollback delete failed", it) }
            }
            null
        } finally {
            temp.delete()
            ownedBitmap?.recycle()
        }
    }

    private fun chooseFormat(sourceMime: String, preserveAlpha: Boolean): Bitmap.CompressFormat {
        if (!preserveAlpha) return Bitmap.CompressFormat.JPEG
        return when (sourceMime.lowercase(Locale.ROOT)) {
            "image/png", "image/webp", "image/avif", "image/gif" -> Bitmap.CompressFormat.PNG
            else -> Bitmap.CompressFormat.JPEG
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyPath(displayName: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "SwiftPic"
        )
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, displayName).absolutePath
    }

    /**
     * Copies EXIF from the source URI onto the freshly encoded JPEG file.
     * The decoded bitmap already has orientation applied, so ORIENTATION is
     * explicitly reset to normal to avoid double rotation.
     */
    private fun copyExif(
        context: Context,
        sourceUri: Uri,
        target: File,
        width: Int,
        height: Int
    ) {
        val source = try {
            context.contentResolver.openInputStream(sourceUri)?.use { ExifInterface(it) }
        } catch (e: Exception) {
            Log.w(TAG, "read source EXIF failed", e)
            null
        } ?: return

        val destination = try {
            ExifInterface(target)
        } catch (e: Exception) {
            Log.w(TAG, "open target EXIF failed", e)
            return
        }

        val tags = arrayOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD
        )
        try {
            tags.forEach { tag ->
                source.getAttribute(tag)?.let { destination.setAttribute(tag, it) }
            }
            destination.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            destination.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            destination.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
            destination.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "write EXIF failed", e)
        }
    }
}
