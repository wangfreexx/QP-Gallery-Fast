package com.qoder.swiftpic.ui.viewer

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.qoder.swiftpic.data.LiveSource
import kotlin.math.min

class LivePhotoController(
    private val context: Context,
    private val textureView: TextureView,
    muted: Boolean,
    private val callbacks: Callbacks
) : TextureView.SurfaceTextureListener {

    interface Callbacks {
        fun onReady()
        fun onPlayingChanged(playing: Boolean)
        fun onError(throwable: Throwable)
    }

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var released = false
    private var prepared = false
    private var pendingStart = false
    private var pendingHold = false
    private var muted = muted
    private var videoWidth = 0
    private var videoHeight = 0
    /** Transform from the fitted still to the current zoom/pan state. */
    private var zoom: Matrix? = null

    val isPrepared: Boolean
        get() = prepared && !released

    init {
        textureView.visibility = View.GONE
        textureView.surfaceTextureListener = this
    }

    /** MediaPlayer calls are state-sensitive; log failures instead of swallowing Errors. */
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer op failed", e)
        }
    }

    fun prepare(source: LiveSource) {
        if (released) return
        releasePlayer()
        textureView.visibility = View.GONE
        pendingStart = false
        Log.d(TAG, "prepare ${source.javaClass.simpleName} muted=$muted")
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            when (source) {
                is LiveSource.Pair -> mp.setDataSource(context, source.uri)
                is LiveSource.Extracted -> mp.setDataSource(source.file.absolutePath)
            }
            mp.setOnPreparedListener {
                if (released) return@setOnPreparedListener
                prepared = true
                videoWidth = it.videoWidth
                videoHeight = it.videoHeight
                Log.d(TAG, "prepared ${videoWidth}x$videoHeight")
                applyVolume()
                applyTransform()
                callbacks.onReady()
                maybeStart()
            }
            mp.setOnCompletionListener {
                if (!released) stopPlayback()
            }
            mp.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                applyTransform()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                prepared = false
                pendingStart = false
                textureView.visibility = View.GONE
                callbacks.onPlayingChanged(false)
                callbacks.onError(IllegalStateException("MediaPlayer error ($what/$extra)"))
                true
            }
            if (surface != null) mp.setSurface(surface)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "prepare failed", e)
            prepared = false
            releasePlayer()
            callbacks.onError(e)
        }
    }

    fun play(hold: Boolean) {
        if (released) return
        pendingHold = hold
        pendingStart = true
        textureView.visibility = View.VISIBLE
        if (surface == null && textureView.isAvailable) {
            attachSurface()
        }
        maybeStart()
    }

    fun stopPlayback() {
        Log.d(TAG, "stop")
        pendingStart = false
        pendingHold = false
        val mp = player
        if (mp != null) {
            safe { if (mp.isPlaying) mp.pause() }
            safe { mp.seekTo(0) }
        }
        textureView.visibility = View.GONE
        callbacks.onPlayingChanged(false)
    }

    fun setMuted(muted: Boolean) {
        // Cache the state; it is applied on the next prepare if the player is not
        // in a Prepared/Started/Paused/PlaybackCompleted state yet.
        this.muted = muted
        Log.d(TAG, "muted=$muted")
        applyVolume()
    }

    /** setVolume is only legal in prepared states; guard against Idle/Initialized. */
    private fun applyVolume() {
        val mp = player ?: return
        if (released || !prepared) return
        safe { mp.setVolume(volume(), volume()) }
    }

    fun release() {
        if (released) return
        Log.d(TAG, "release")
        released = true
        pendingStart = false
        releasePlayer()
        textureView.visibility = View.GONE
        textureView.surfaceTextureListener = null
    }

    private fun maybeStart() {
        val mp = player ?: return
        if (!prepared || surface == null || !pendingStart) return
        pendingStart = false
        Log.d(TAG, "start loop=$pendingHold")
        try {
            mp.isLooping = pendingHold
            applyVolume()
            mp.start()
            textureView.visibility = View.VISIBLE
            applyTransform()
            callbacks.onPlayingChanged(true)
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            callbacks.onError(e)
        }
    }

    private fun attachSurface() {
        val texture = textureView.surfaceTexture ?: return
        if (surface == null) {
            surface = Surface(texture)
        }
        safe { player?.setSurface(surface) }
        applyTransform()
        maybeStart()
    }

    private fun releasePlayer() {
        pendingStart = false
        val mp = player
        player = null
        prepared = false
        if (mp != null) {
            safe { mp.setSurface(null) }
            safe { if (mp.isPlaying) mp.stop() }
            safe { mp.reset() }
            safe { mp.release() }
        }
        surface?.release()
        surface = null
    }

    private fun volume(): Float = if (muted) 0f else 1f

    private companion object {
        const val TAG = "SwiftPicLive"
    }

    /** Follow the still image's pinch / pan while the video is playing. */
    fun setZoomTransform(m: Matrix) {
        if (zoom == null) zoom = Matrix()
        zoom?.set(m)
        applyTransform()
    }

    private fun applyTransform() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        // TextureView already stretches the video frame to fill the whole view.
        // The transform must be a *relative* correction of that stretch: an
        // absolute pixel scale leaves the picture distorted (just smaller).
        val stretchX = viewWidth / videoWidth
        val stretchY = viewHeight / videoHeight
        val fit = min(stretchX, stretchY)
        val matrix = Matrix()
        matrix.setScale(
            fit / stretchX,
            fit / stretchY,
            viewWidth / 2f,
            viewHeight / 2f
        )
        // The overlay is laid out at fit size, so the relative zoom can be
        // concatenated directly: first fix the aspect, then zoom/pan with the still.
        zoom?.let { matrix.postConcat(it) }
        textureView.setTransform(matrix)
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        attachSurface()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        applyTransform()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        player?.let {
            safe { if (it.isPlaying) it.pause() }
            safe { it.setSurface(null) }
        }
        surface?.release()
        surface = null
        pendingStart = false
        if (!released && player != null) {
            callbacks.onPlayingChanged(false)
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
}
