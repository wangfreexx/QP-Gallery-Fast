package com.qoder.swiftpic.ui.viewer

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * The single exported viewer entry point.
 *
 * It deliberately does not render anything: after validating the incoming
 * content URI it forwards to the non-exported [ViewerActivity]. This keeps an
 * external caller from reaching the "browse the whole library" code path by
 * launching the viewer with no data, a file:// URI, or crafted extras.
 */
class ExternalViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val mime = intent?.type
            ?: uri?.let { runCatching { contentResolver.getType(it) }.getOrNull() }

        if (uri == null || !isAcceptable(uri, mime)) {
            Log.w(TAG, "rejected external VIEW uri=$uri mime=$mime")
            finish()
            return
        }

        val forward = Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.EXTRA_EXTERNAL_URI, uri.toString())
            // Re-attach the URI grant so the receiving activity can open the stream.
            clipData = ClipData.newUri(contentResolver, "external image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(forward)
        } catch (e: Exception) {
            Log.w(TAG, "forward to viewer failed", e)
        }
        finish()
    }

    private fun isAcceptable(uri: Uri, mime: String?): Boolean {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return false
        if (mime.isNullOrEmpty() || !mime.startsWith("image/", ignoreCase = true)) return false
        // Never accept our own providers: an external caller has no business
        // pointing us back at application-private files.
        val authority = uri.authority.orEmpty()
        if (authority == packageName || authority.startsWith("$packageName.")) return false
        return true
    }

    private companion object {
        const val TAG = "SwiftPicExternalViewer"
    }
}
