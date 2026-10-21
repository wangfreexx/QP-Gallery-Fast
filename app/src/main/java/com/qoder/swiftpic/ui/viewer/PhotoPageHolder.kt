package com.qoder.swiftpic.ui.viewer

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.HapticFeedbackConstants
import android.util.Log
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.DecodeSpec
import com.qoder.swiftpic.data.DecodedImage
import com.qoder.swiftpic.data.GifPlayer
import com.qoder.swiftpic.data.GifSource
import com.qoder.swiftpic.data.HdrDetector
import com.qoder.swiftpic.data.LiveSource
import com.qoder.swiftpic.data.MotionPart
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.databinding.ViewPhotoPageBinding
import com.qoder.swiftpic.swiftPic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoPageHolder(
    private val binding: ViewPhotoPageBinding,
    private val activity: ViewerActivity,
    private val callback: Callback
) : RecyclerView.ViewHolder(binding.root) {

    interface Callback {
        fun onPageState(holder: PhotoPageHolder)
        fun onTap(holder: PhotoPageHolder, xFraction: Float, yFraction: Float)
        fun onPageRequest(holder: PhotoPageHolder, delta: Int)
    }

    data class UiState(
        val loading: Boolean = true,
        val failed: Boolean = false,
        val hasGainMap: Boolean = false,
        val isGif: Boolean = false,
        val gifReady: Boolean = false,
        val gifPlaying: Boolean = false,
        val gifFrameIndex: Int = 0,
        val gifFrameCount: Int = 0,
        val isAnimatedGraphic: Boolean = false,
        val liveAvailable: Boolean = false,
        val liveBusy: Boolean = false,
        val livePlaying: Boolean = false,
        val liveMuted: Boolean = false
    )

    private val app get() = activity.swiftPic

    private lateinit var photo: Photo
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var released = false
    private var isActive = false

    var uiState: UiState = UiState()
        private set

    private var imageJob: Job? = null
    private var gifSource: GifSource? = null
    private var gifPlayer: GifPlayer? = null
    private var gifFrame: Bitmap? = null
    private var animatedDrawable: Drawable? = null
    private var userPausedGif = false
    private var userPausedAnimated = false

    private var liveProbeJob: Job? = null
    private var liveJob: Job? = null
    private var liveProbeAttempted = false
    private var livePart: MotionPart? = null
    private var liveController: LivePhotoController? = null
    private var livePendingPlay = false
    private var livePendingHold = false
    private var liveMuted = false
    private var autoPlayTriggered = false
    private val hideZoomBadge = Runnable { binding.zoomBadge.visibility = View.GONE }

    fun bind(newPhoto: Photo, position: Int, active: Boolean) {
        teardownMedia()
        scope.cancel()
        released = false
        isActive = false
        photo = newPhoto
        itemView.setTag(R.id.photoImage, position)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        userPausedGif = false
        userPausedAnimated = false
        liveProbeAttempted = false
        livePart = null
        livePendingPlay = false
        livePendingHold = false
        liveProbeJob = null
        liveJob = null
        liveMuted = app.prefs.liveMuted
        autoPlayTriggered = false

        binding.pageProgress.visibility = View.GONE
        binding.errorBox.visibility = View.GONE
        binding.liveTexture.visibility = View.GONE
        binding.zoomImage.contentDescription = photo.name

        uiState = UiState(
            loading = true,
            isGif = photo.isGif,
            liveAvailable = photo.hasLivePair,
            liveMuted = liveMuted
        )

        binding.zoomImage.onTap = { x, y ->
            val width = binding.zoomImage.width.coerceAtLeast(1)
            val height = binding.zoomImage.height.coerceAtLeast(1)
            callback.onTap(this, x / width, y / height)
        }
        binding.zoomImage.onLongPressStart = {
            if (uiState.liveAvailable && !released) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLiveHoldStart()
            }
        }
        binding.zoomImage.onLongPressEnd = {
            onLiveHoldEnd()
        }
        binding.zoomImage.onEdgePage = { delta ->
            if (!released) callback.onPageRequest(this, delta)
        }
        binding.zoomImage.onZoomChanged = { ratio -> onZoomRatio(ratio) }
        binding.zoomBadge.removeCallbacks(hideZoomBadge)
        binding.zoomBadge.visibility = View.GONE
        binding.liveTexture.setOnClickListener {
            callback.onTap(this, -1f, -1f)
        }

        val placeholder = app.thumbnails.peek(photo)
        if (placeholder != null) {
            binding.zoomImage.setBitmap(placeholder)
            binding.pageProgress.visibility = View.GONE
        } else {
            binding.zoomImage.clearImage()
            binding.pageProgress.visibility = View.VISIBLE
        }

        emitState()
        loadMedia()
        setActive(active)
        if (active) prefetchEmbeddedLive()
    }

    fun isImageAtFit(): Boolean = binding.zoomImage.isAtFit()

    private fun onZoomRatio(ratio: Float) {
        if (released) return
        val badge = binding.zoomBadge
        badge.removeCallbacks(hideZoomBadge)
        if (ratio <= 1.02f) {
            badge.visibility = View.GONE
            return
        }
        val wasHidden = badge.visibility != View.VISIBLE
        val text = String.format(java.util.Locale.US, "%.1fx", ratio)
        if (badge.text?.toString() != text) badge.text = text
        if (wasHidden) positionZoomBadge()
        badge.visibility = View.VISIBLE
        if (wasHidden) {
            Log.d("SwiftPicPage", "zoom badge $text name=${photo.name}")
        }
        badge.postDelayed(hideZoomBadge, 1400L)
    }

    /** The header name/date sits exactly where the badge used to float. */
    private fun positionZoomBadge() {
        val chrome = activity.findViewById<View>(R.id.topChrome)
        val density = activity.resources.displayMetrics.density
        val top = if (chrome != null && chrome.visibility == View.VISIBLE) chrome.bottom else 0
        binding.zoomBadge.y = top + 10f * density
    }

    fun setActive(active: Boolean) {
        if (released) return
        if (isActive == active) {
            // The pager can bind the target page before it becomes current. Re-emit
            // the state when activation is confirmed so the chrome gets the pair /
            // HDR flags of this page.
            if (active) emitState()
            return
        }
        isActive = active
        if (active) {
            onActivated()
        } else {
            onDeactivated()
        }
        emitState()
    }

    fun toggleGifPlayback() {
        if (released) return
        if (photo.isGif) {
            val player = gifPlayer ?: return
            if (player.frameCount <= 1) return
            if (player.isPlaying) {
                userPausedGif = true
                player.pause()
            } else {
                userPausedGif = false
                player.start()
            }
            return
        }
        if (!uiState.isAnimatedGraphic) return
        val drawable = animatedDrawable
        if (drawable is AnimatedImageDrawable) {
            if (uiState.gifPlaying) {
                userPausedAnimated = true
                drawable.stop()
                updateState(uiState.copy(gifPlaying = false))
            } else {
                userPausedAnimated = false
                drawable.start()
                updateState(uiState.copy(gifPlaying = true))
            }
        }
    }

    fun beginGifSeek() {
        userPausedGif = true
        gifPlayer?.pause()
    }

    fun resumeGifPlayback() {
        if (released || !photo.isGif) return
        val player = gifPlayer ?: return
        if (player.frameCount <= 1) return
        userPausedGif = false
        player.start()
    }

    fun seekGif(index: Int) {
        if (!photo.isGif || released) return
        val player = gifPlayer ?: return
        val target = index.coerceIn(0, (player.frameCount - 1).coerceAtLeast(0))
        userPausedGif = true
        player.pause()
        player.seek(target)
        updateState(uiState.copy(gifFrameIndex = target, gifPlaying = false))
    }

    fun stepGif(delta: Int) {
        if (!photo.isGif || released) return
        val player = gifPlayer ?: return
        if (player.frameCount <= 1) return
        val current = if (player.currentIndex >= 0) player.currentIndex else uiState.gifFrameIndex
        val count = player.frameCount
        val target = ((current + delta) % count + count) % count
        userPausedGif = true
        player.pause()
        player.seek(target)
        updateState(uiState.copy(gifFrameIndex = target, gifPlaying = false))
    }

    fun toggleLivePlayback() {
        if (released) return
        if (!uiState.liveAvailable) return
        if (uiState.livePlaying) {
            stopLivePlayback()
            return
        }
        if (uiState.liveBusy) return
        requestLivePlay(hold = false)
    }

    fun stopLivePlayback() {
        livePendingPlay = false
        livePendingHold = false
        liveController?.stopPlayback()
        updateState(uiState.copy(livePlaying = false, liveBusy = false))
    }

    fun toggleLiveMute() {
        liveMuted = !liveMuted
        app.prefs.liveMuted = liveMuted
        liveController?.setMuted(liveMuted)
        updateState(uiState.copy(liveMuted = liveMuted))
    }

    fun release() {
        if (released) return
        released = true
        scope.cancel()
        liveProbeJob?.cancel()
        liveJob?.cancel()
        releaseLiveController()
        teardownMedia()
        binding.zoomImage.onTap = null
        binding.zoomImage.onLongPressStart = null
        binding.zoomImage.onLongPressEnd = null
        binding.zoomImage.onZoomChanged = null
        binding.zoomBadge.removeCallbacks(hideZoomBadge)
        binding.zoomImage.contentDescription = null
        binding.liveTexture.setOnClickListener(null)
    }

    private fun loadMedia() {
        if (photo.isGif) loadGif() else loadImage()
    }

    private fun loadImage() {
        imageJob = scope.launch {
            try {
                val metrics = activity.resources.displayMetrics
                val spec = DecodeSpec.forScreen(metrics.widthPixels, metrics.heightPixels)
                val decoded = app.images.decode(photo, spec)
                if (released) return@launch
                binding.pageProgress.visibility = View.GONE
                when (decoded) {
                    is DecodedImage.Still -> {
                        binding.zoomImage.setBitmap(decoded.bitmap)
                        HdrDetector.remember(photo, decoded.hasGainMap)
                        uiState = uiState.copy(
                            loading = false,
                            failed = false,
                            hasGainMap = decoded.hasGainMap
                        )
                        if (isActive) {
                            maybeAutoPlayLive()
                            prefetchEmbeddedLive()
                        }
                    }
                    is DecodedImage.Animated -> {
                        animatedDrawable = decoded.drawable
                        binding.zoomImage.setAnimatedDrawable(
                            decoded.drawable,
                            decoded.width,
                            decoded.height
                        )
                        uiState = uiState.copy(
                            loading = false,
                            failed = false,
                            isAnimatedGraphic = true,
                            gifReady = true,
                            gifFrameCount = 0
                        )
                        if (isActive && !userPausedAnimated) {
                            (decoded.drawable as? AnimatedImageDrawable)?.start()
                            uiState = uiState.copy(gifPlaying = true)
                        }
                    }
                }
                emitState()
            } catch (t: Throwable) {
                if (released) return@launch
                binding.pageProgress.visibility = View.GONE
                uiState = uiState.copy(loading = false, failed = true)
                showError(activity.getString(R.string.image_decode_failed))
                emitState()
            }
        }
    }

    private fun loadGif() {
        imageJob = scope.launch {
            val source = try {
                GifSource.load(activity, photo.uri)
            } catch (_: Throwable) {
                null
            }
            if (released) return@launch
            if (source == null) {
                binding.pageProgress.visibility = View.GONE
                uiState = uiState.copy(
                    loading = false,
                    failed = true,
                    isGif = false,
                    gifReady = false
                )
                showError(activity.getString(R.string.gif_load_failed))
                emitState()
                return@launch
            }
            gifSource = source

            var newPlayer: GifPlayer? = null
            newPlayer = GifPlayer(
                source = source,
                onFrame = { bitmap, index, count ->
                    if (!released && gifPlayer === newPlayer) {
                        onGifFrame(bitmap, index, count)
                    } else {
                        newPlayer?.releaseDisplayed(bitmap)
                    }
                },
                onPlayingChanged = { playing ->
                    if (!released && gifPlayer === newPlayer) {
                        updateState(uiState.copy(gifPlaying = playing))
                    }
                },
                onError = {
                    if (!released && gifPlayer === newPlayer) {
                        uiState = uiState.copy(
                            failed = true,
                            gifReady = false,
                            gifPlaying = false,
                            isGif = false
                        )
                        showError(activity.getString(R.string.gif_load_failed))
                        emitState()
                    }
                }
            )
            gifPlayer = newPlayer
            uiState = uiState.copy(
                loading = false,
                failed = false,
                isGif = true,
                gifReady = true,
                gifFrameCount = source.frameCount,
                gifFrameIndex = 0,
                gifPlaying = false
            )
            emitState()

            if (isActive) {
                if (source.frameCount <= 1) {
                    newPlayer.seek(0)
                } else if (!userPausedGif) {
                    newPlayer.start()
                }
            }
        }
    }

    private fun onGifFrame(bitmap: Bitmap, index: Int, count: Int) {
        if (released) return
        binding.pageProgress.visibility = View.GONE
        val old = gifFrame
        binding.zoomImage.setFrameBitmap(bitmap)
        gifFrame = bitmap
        if (old != null && old !== bitmap) {
            gifPlayer?.releaseDisplayed(old)
        }
        updateState(
            uiState.copy(
                gifFrameIndex = index,
                gifFrameCount = count,
                gifPlaying = gifPlayer?.isPlaying == true,
                gifReady = true
            )
        )
    }

    private fun onActivated() {
        if (photo.isGif) {
            val player = gifPlayer
            if (player != null) {
                when {
                    player.frameCount <= 1 -> if (gifFrame == null) player.seek(0)
                    !userPausedGif -> player.start()
                }
            }
        } else if (uiState.isAnimatedGraphic && !userPausedAnimated) {
            (animatedDrawable as? AnimatedImageDrawable)?.start()
            uiState = uiState.copy(gifPlaying = true)
        }

        maybeAutoPlayLive()
        prefetchEmbeddedLive()
    }

    private fun onDeactivated() {
        gifPlayer?.pause()
        (animatedDrawable as? AnimatedImageDrawable)?.stop()
        userPausedGif = false
        userPausedAnimated = false
        autoPlayTriggered = false
        if (uiState.gifPlaying) uiState = uiState.copy(gifPlaying = false)
        livePendingPlay = false
        livePendingHold = false
        liveJob?.cancel()
        liveJob = null
        releaseLiveController()
        if (uiState.livePlaying || uiState.liveBusy) {
            uiState = uiState.copy(livePlaying = false, liveBusy = false)
        }
    }

    /**
     * Probes the embedded video as soon as the page is bound and pre-extracts it, so
     * autoplay starts right away instead of after a probe+copy wait.
     */
    private fun prefetchEmbeddedLive() {
        if (released || liveProbeAttempted) return
        if (photo.hasLivePair || !photo.canContainMotionPhoto) return
        liveProbeAttempted = true
        liveProbeJob = scope.launch {
            val result = try {
                app.motion.probe(photo)
            } catch (_: Throwable) {
                null
            }
            if (released) return@launch
            val part = result?.part ?: return@launch
            livePart = part
            if (isActive) updateState(uiState.copy(liveAvailable = true))
            runCatching { app.motion.extract(photo, part) }
            if (released) return@launch
            Log.d("SwiftPicPage", "live warmed name=${photo.name} bytes=${part.length}")
            maybeAutoPlayLive()
        }
    }

    private fun maybeAutoPlayLive() {
        if (released || !isActive || autoPlayTriggered) return
        if (!app.prefs.liveAutoPlay) return
        if (!uiState.liveAvailable || uiState.loading) return
        autoPlayTriggered = true
        Log.d("SwiftPicPage", "auto-play live name=${photo.name} muted=$liveMuted")
        requestLivePlay(hold = false)
    }

    private fun onLiveHoldStart() {
        if (!isActive || !uiState.liveAvailable || uiState.livePlaying) return
        livePendingHold = true
        requestLivePlay(hold = true)
    }

    private fun onLiveHoldEnd() {
        livePendingHold = false
        if (uiState.livePlaying || uiState.liveBusy || livePendingPlay) {
            stopLivePlayback()
        }
    }

    private fun requestLivePlay(hold: Boolean) {
        if (!isActive) return
        autoPlayTriggered = true
        livePendingPlay = true
        livePendingHold = hold
        updateState(uiState.copy(liveBusy = true))

        when {
            photo.hasLivePair -> preparePairLive()
            livePart != null -> extractAndPrepareLive(livePart!!)
            photo.canContainMotionPhoto -> probeThenPlay()
            else -> failLive(activity.getString(R.string.live_unavailable))
        }
    }

    private fun probeThenPlay() {
        liveJob?.cancel()
        liveJob = scope.launch {
            // Reuse the probe started when the page was bound instead of opening the
            // file a second time.
            liveProbeJob?.join()
            if (released || !isActive) return@launch
            val part = livePart ?: try {
                app.motion.probe(photo).part
            } catch (_: Throwable) {
                null
            }
            if (part == null) {
                failLive(activity.getString(R.string.live_unavailable))
            } else {
                livePart = part
                updateState(uiState.copy(liveAvailable = true))
                if (livePendingPlay) extractAndPrepareLive(part)
            }
        }
    }

    private fun preparePairLive() {
        val uri = photo.liveVideoUri ?: run {
            failLive(activity.getString(R.string.live_unavailable))
            return
        }
        val controller = ensureLiveController()
        controller.prepare(LiveSource.Pair(uri))
    }

    private fun extractAndPrepareLive(part: MotionPart) {
        liveJob?.cancel()
        liveJob = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    app.motion.extract(photo, part)
                }
                if (released || !isActive) return@launch
                val controller = ensureLiveController()
                controller.prepare(LiveSource.Extracted(file))
            } catch (t: Throwable) {
                if (!released) failLive(activity.getString(R.string.live_failed))
            }
        }
    }

    private fun ensureLiveController(): LivePhotoController {
        liveController?.let { return it }
        val controller = LivePhotoController(
            context = activity,
            textureView = binding.liveTexture,
            muted = liveMuted,
            callbacks = object : LivePhotoController.Callbacks {
                override fun onReady() {
                    if (released) return
                    uiState = uiState.copy(liveBusy = false)
                    if (livePendingPlay && isActive) {
                        livePendingPlay = false
                        liveController?.play(livePendingHold)
                    }
                    emitState()
                }

                override fun onPlayingChanged(playing: Boolean) {
                    if (released) return
                    // TextureView cannot paint a background; hide the still so the
                    // letterboxed video sits on the black page instead.
                    binding.zoomImage.visibility = if (playing) View.INVISIBLE else View.VISIBLE
                    uiState = uiState.copy(
                        livePlaying = playing,
                        liveBusy = if (playing) false else uiState.liveBusy
                    )
                    emitState()
                }

                override fun onError(throwable: Throwable) {
                    if (released) return
                    failLive(activity.getString(R.string.live_failed))
                }
            }
        )
        liveController = controller
        return controller
    }

    private fun releaseLiveController() {
        livePendingPlay = false
        livePendingHold = false
        liveController?.release()
        liveController = null
        binding.liveTexture.visibility = View.GONE
        binding.zoomImage.visibility = View.VISIBLE
    }

    private fun failLive(message: String) {
        livePendingPlay = false
        livePendingHold = false
        uiState = uiState.copy(liveBusy = false, livePlaying = false)
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        emitState()
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorBox.visibility = View.VISIBLE
        binding.pageProgress.visibility = View.GONE
    }

    private fun updateState(newState: UiState) {
        uiState = newState
        emitState()
    }

    private fun emitState() {
        if (!released) callback.onPageState(this)
    }

    private fun teardownMedia() {
        imageJob?.cancel()
        imageJob = null
        binding.zoomImage.clearImage()
        val player = gifPlayer
        val frame = gifFrame
        if (player != null && frame != null) {
            player.releaseDisplayed(frame)
        }
        player?.release()
        gifPlayer = null
        gifSource = null
        gifFrame = null
        animatedDrawable = null
        liveProbeJob?.cancel()
        liveJob?.cancel()
        liveProbeJob = null
        liveJob = null
        releaseLiveController()
        binding.liveTexture.visibility = View.GONE
        binding.pageProgress.visibility = View.GONE
        binding.errorBox.visibility = View.GONE
    }
}
