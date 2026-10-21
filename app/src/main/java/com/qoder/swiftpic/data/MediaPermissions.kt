package com.qoder.swiftpic.data

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The app targets SDK 32 so READ_EXTERNAL_STORAGE stays grantable: MediaStore and
 * WallpaperManager both rely on it, and the granular media permissions are not used.
 */
object MediaPermissions {

    fun canReadImages(context: Context): Boolean =
        granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)

    fun canReadVideos(context: Context): Boolean = canReadImages(context)

    fun isPartialAccess(context: Context): Boolean = false

    fun requestPermissions(): Array<String> =
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun shouldOpenSettings(activity: Activity, askedBefore: Boolean): Boolean {
        if (!askedBefore) return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    private fun granted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
