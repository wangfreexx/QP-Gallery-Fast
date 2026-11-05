package com.qoder.swiftpic.data

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The app targets SDK 32 so READ_EXTERNAL_STORAGE stays grantable for
 * MediaStore and WallpaperManager. On Android 13+ the platform maps it onto
 * the granular media permissions, and Android 14 adds a "selected photos
 * only" state which must be detected separately: checkSelfPermission for
 * READ_EXTERNAL_STORAGE still returns GRANTED in that state even though
 * MediaStore only exposes a handful of photos.
 */
object MediaPermissions {

    private const val READ_MEDIA_VISUAL_USER_SELECTED =
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    fun canReadImages(context: Context): Boolean {
        if (granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) return true
        if (Build.VERSION.SDK_INT >= 33 &&
            granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        ) {
            return true
        }
        // A partial grant still allows reading the selected items.
        return isPartialAccess(context)
    }

    fun canReadVideos(context: Context): Boolean {
        if (granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) return true
        return Build.VERSION.SDK_INT >= 33 &&
            granted(context, Manifest.permission.READ_MEDIA_VIDEO)
    }

    /**
     * True when the user chose "Allow limited access" on Android 14+ (API 34+).
     * In that state READ_MEDIA_VISUAL_USER_SELECTED is granted while the full
     * READ_MEDIA_IMAGES grant is not.
     */
    fun isPartialAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return granted(context, READ_MEDIA_VISUAL_USER_SELECTED) &&
            !granted(context, Manifest.permission.READ_MEDIA_IMAGES)
    }

    fun hasFullAccess(context: Context): Boolean =
        canReadImages(context) && !isPartialAccess(context)

    /** Asking again on Android 14 re-opens the photo picker for partial grants. */
    fun requestPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun shouldOpenSettings(activity: Activity, askedBefore: Boolean): Boolean {
        if (!askedBefore) return false
        // Already fully granted: never send the user to system settings.
        if (hasFullAccess(activity)) return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
