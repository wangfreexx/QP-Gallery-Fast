package com.qoder.swiftpic.ui.viewer

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.util.Log
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

    val isPrepared: Boolean
        get() = prepared && !released

    init {
        textureView.visibility = View.GONE
        textureView.surfaceTextureListener = this
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
                it.setVolume(volume(), volume())
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
        } catch (t: Throwable) {
            prepared = false
            releasePlayer()
            callbacks.onError(t)
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
            runCatching { if (mp.isPlaying) mp.pause() }
            runCatching { mp.seekTo(0) }
        }
        textureView.visibility = View.GONE
        callbacks.onPlayingChanged(false)
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        Log.d(TAG, "muted=$muted")
        player?.let { it.setVolume(volume(), volume()) }
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
            mp.setVolume(volume(), volume())
            mp.start()
            textureView.visibility = View.VISIBLE
            applyTransform()
            callbacks.onPlayingChanged(true)
        } catch (t: Throwable) {
            callbacks.onError(t)
        }
    }

    private fun attachSurface() {
        val texture = textureView.surfaceTexture ?: return
        if (surface == null) {
            surface = Surface(texture)
        }
        runCatching { player?.setSurface(surface) }
        applyTransform()
        maybeStart()
    }

    private fun releasePlayer() {
        pendingStart = false
        val mp = player
        player = null
        prepared = false
        if (mp != null) {
            runCatching { mp.setSurface(null) }
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        surface?.release()
        surface = null
    }

    private fun volume(): Float = if (muted) 0f else 1f

    private companion object {
        const val TAG = "SwiftPicLive"
    }

    private fun applyTransform() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val scale = min(viewWidth / videoWidth, viewHeight / videoHeight)
        val dx = (viewWidth - videoWidth * scale) / 2f
        val dy = (viewHeight - videoHeight * scale) / 2f
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
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
            runCatching { if (it.isPlaying) it.pause() }
            runCatching { it.setSurface(null) }
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
