package com.qoder.swiftpic.ui.viewer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import android.content.IntentSender
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.GalleryLibrary
import com.qoder.swiftpic.data.GalleryFilters
import com.qoder.swiftpic.data.GalleryKeys
import com.qoder.swiftpic.data.OrientationMode
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.data.TransitionMode
import com.qoder.swiftpic.data.sortPhotos
import com.qoder.swiftpic.databinding.ActivityViewerBinding
import com.qoder.swiftpic.swiftPic
import com.qoder.swiftpic.ui.AppHint
import com.qoder.swiftpic.ui.SettingsActivity
import com.qoder.swiftpic.ui.UiMotion
import com.qoder.swiftpic.ui.showMotion
import com.qoder.swiftpic.ui.WallpaperCropActivity
import com.qoder.swiftpic.ui.edit.EditActivity
import com.qoder.swiftpic.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.Locale

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private val app get() = swiftPic

    private val photos = ArrayList<Photo>()
    private lateinit var adapter: PhotoPagerAdapter

    private var albumKey: String? = null
    private var query: String? = null
    private var isChromeVisible = true
    private var sliderTracking = false
    private var updatingSlider = false
    private var lastHdrMode = -1
    private var lastHeaderPhotoId = -1L
    private var lastIndicator: String? = null
    private var lastPlayIconRes = 0
    private var lastMuteIconRes = 0
    private var lastLoggedLive: Boolean? = null
    private var lastLiveVisual: String? = null
    private var gifControlsExpanded = false

    private var pendingDeleteId = -1L
    private var pendingDeletePosition = -1
    private var savedPosition = -1
    private var pageAnimator: ValueAnimator? = null
    private val autoHideChrome = Runnable {
        if (!isFinishing && isChromeVisible) hideChrome()
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            removeDeletedPhoto()
        }
    }

    private val pageCallback = object : PhotoPageHolder.Callback {
        override fun onPageState(holder: PhotoPageHolder) {
            if (!::adapter.isInitialized) return
            if (holder.bindingAdapterPosition != binding.pager.currentItem) return
            refreshChrome()
        }

        override fun onTap(holder: PhotoPageHolder, xFraction: Float, yFraction: Float) {
            if (!::adapter.isInitialized) return
            if (holder.bindingAdapterPosition != binding.pager.currentItem) return
            val paged = app.prefs.tapPaging &&
                xFraction in 0f..1f && yFraction in 0f..1f &&
                when {
                    xFraction < SIDE_TAP_ZONE -> { pageBy(-1); true }
                    xFraction > 1f - SIDE_TAP_ZONE -> { pageBy(1); true }
                    else -> false
                }
            if (!paged) toggleChrome()
        }

        override fun onPageRequest(holder: PhotoPageHolder, delta: Int) {
            if (!::adapter.isInitialized) return
            if (holder.bindingAdapterPosition != binding.pager.currentItem) return
            pageBy(delta)
        }
    }

    private val pageChangeCallback = object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (!::adapter.isInitialized) return
            val photo = photos.getOrNull(position)
            Log.d(
                TAG,
                "page selected $position ${photo?.name} live=${photo?.hasLivePair} kind=${photo?.liveKind}"
            )
            gifControlsExpanded = false
            lastLiveVisual = null
            adapter.setActivePosition(position)
            refreshChrome()
            binding.pager.postDelayed({
                if (!isFinishing && ::adapter.isInitialized) {
                    adapter.setActivePosition(binding.pager.currentItem)
                    refreshChrome()
                }
            }, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (app.prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        when (app.prefs.orientationMode) {
            OrientationMode.SYSTEM -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationMode.PORTRAIT -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiMotion.animateEnter(binding.root, 160L, fadeOnly = true)
        applySystemBars(true)

        albumKey = intent.getStringExtra(EXTRA_ALBUM_KEY)
        query = intent.getStringExtra(EXTRA_QUERY)
        savedPosition = savedInstanceState?.getInt(KEY_POSITION, -1)
            ?: intent.getIntExtra(EXTRA_POSITION, 0)

        binding.topChrome.alpha = 1f
        binding.bottomChrome.alpha = 1f
        applyPageTransformer()
        setupInsets()
        setupListeners()
        if (Build.VERSION.SDK_INT < 30) {
            binding.deleteButton.visibility = View.GONE
        }

        loadPhotos()
    }

    override fun onStart() {
        super.onStart()
        // The transition mode can change while this viewer sits in the back stack.
        applyPageTransformer()
        if (::adapter.isInitialized && photos.isNotEmpty()) {
            adapter.setActivePosition(binding.pager.currentItem)
            refreshChrome()
        }
    }

    override fun onStop() {
        if (::adapter.isInitialized) {
            adapter.activeHolder?.setActive(false)
        }
        gifControlsExpanded = false
        super.onStop()
    }

    override fun onDestroy() {
        cancelChromeAutoHide()
        pageAnimator?.cancel()
        pageAnimator = null
        if (::adapter.isInitialized) {
            adapter.releaseAll()
            binding.pager.adapter = null
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::adapter.isInitialized) {
            outState.putInt(KEY_POSITION, binding.pager.currentItem)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySystemBars(isChromeVisible)
            if (isChromeVisible) scheduleChromeAutoHide()
        } else {
            cancelChromeAutoHide()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (isChromeVisible) scheduleChromeAutoHide()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun loadPhotos() {
        binding.viewerLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val library: GalleryLibrary? = try {
                app.gallery.load(force = false)
            } catch (_: Throwable) {
                null
            }
            if (isFinishing || isDestroyed) return@launch
            if (library == null || !library.hasImageAccess) {
                finish()
                return@launch
            }
            val sorted = GalleryFilters.photosFor(
                library,
                app.userData,
                albumKey,
                query,
                app.prefs.sortMode
            )
            photos.clear()
            photos.addAll(sorted)
            if (photos.isEmpty()) {
                finish()
                return@launch
            }
            val target = if (savedPosition in photos.indices) savedPosition else 0
            adapter = PhotoPagerAdapter(this@ViewerActivity, photos, pageCallback).apply {
                activeIndex = target
            }
            binding.pager.offscreenPageLimit = 1
            binding.pager.adapter = adapter
            binding.pager.registerOnPageChangeCallback(pageChangeCallback)
            binding.pager.setCurrentItem(target, false)
            binding.viewerLoading.visibility = View.GONE
            binding.pager.post {
                if (isFinishing) return@post
                adapter.setActivePosition(binding.pager.currentItem)
                refreshChrome()
                showChrome()
            }
            binding.pager.postDelayed({
                if (!isFinishing && ::adapter.isInitialized) {
                    adapter.setActivePosition(binding.pager.currentItem)
                    refreshChrome()
                }
            }, 120)
        }
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener { finish() }

        binding.gifFramesButton.setOnClickListener { toggleGifControls() }
        binding.gifPlayPause.setOnClickListener {
            adapter.activeHolder?.toggleGifPlayback()
        }
        binding.gifPrev.setOnClickListener {
            adapter.activeHolder?.stepGif(-1)
        }
        binding.gifNext.setOnClickListener {
            adapter.activeHolder?.stepGif(1)
        }
        binding.frameSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                sliderTracking = true
                adapter.activeHolder?.beginGifSeek()
            }

            override fun onStopTrackingTouch(slider: Slider) {
                sliderTracking = false
                val target = slider.value.roundToInt()
                adapter.activeHolder?.seekGif(target)
            }
        })
        binding.frameSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && sliderTracking) {
                val count = adapter.activeHolder?.uiState?.gifFrameCount ?: 0
                if (count > 0) {
                    binding.frameCounter.text = getString(
                        R.string.frame_counter,
                        value.roundToInt() + 1,
                        count
                    )
                }
            }
        }

        binding.liveButton.setOnClickListener {
            adapter.activeHolder?.toggleLivePlayback()
        }
        binding.liveMuteButton.setOnClickListener {
            adapter.activeHolder?.toggleLiveMute()
        }

        binding.editButton.setOnClickListener { editCurrent() }
        binding.hdrButton.setOnClickListener { toggleHdr() }
        binding.favoriteButton.setOnClickListener { toggleFavorite() }
        binding.shareButton.setOnClickListener { shareCurrent() }
        binding.deleteButton.setOnClickListener { requestDeleteCurrent() }
        binding.detailsButton.setOnClickListener { showDetails() }
        binding.moreButton.setOnClickListener { showMoreMenu() }
    }

    private fun setupInsets() {
        val topBarInitial = intArrayOf(
            binding.topBar.paddingLeft,
            binding.topBar.paddingTop,
            binding.topBar.paddingRight,
            binding.topBar.paddingBottom
        )
        val bottomInitial = intArrayOf(
            binding.bottomChrome.paddingLeft,
            binding.bottomChrome.paddingTop,
            binding.bottomChrome.paddingRight,
            binding.bottomChrome.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topBar.setPadding(
                topBarInitial[0] + bars.left,
                topBarInitial[1] + bars.top,
                topBarInitial[2] + bars.right,
                topBarInitial[3]
            )
            binding.bottomChrome.setPadding(
                bottomInitial[0] + bars.left,
                bottomInitial[1],
                bottomInitial[2] + bars.right,
                bottomInitial[3] + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun refreshChrome() {
        if (!::adapter.isInitialized) return
        updateHdrButton()
        val position = binding.pager.currentItem
        val photo = photos.getOrNull(position) ?: return
        val indicator = getString(R.string.page_indicator, position + 1, photos.size)
        if (binding.viewerTitle.text != photo.name) {
            binding.viewerTitle.text = photo.name
        }
        if (lastHeaderPhotoId != photo.id || lastIndicator != indicator) {
            binding.viewerSubtitle.text = subtitle(photo)
            binding.pageIndicator.text = indicator
            lastHeaderPhotoId = photo.id
            lastIndicator = indicator
        }

        val holder = adapter.activeHolder
            ?.takeIf { it.bindingAdapterPosition == position }
        val state = holder?.uiState
        val liveVisible = state?.liveAvailable == true
        if (lastLoggedLive != liveVisible) {
            lastLoggedLive = liveVisible
            Log.d(
                TAG,
                "chrome live=$liveVisible photoLive=${photo.hasLivePair} " +
                    "state=${state != null} holderPos=${holder?.bindingAdapterPosition}"
            )
        }
        if (state == null) {
            setVisible(binding.hdrBadge, false)
            setVisible(binding.liveBadge, false)
            setVisible(binding.liveButton, false)
            setVisible(binding.liveMuteButton, false)
            setVisible(binding.liveSpinner, false)
            setVisible(binding.gifFramesButton, false)
            setVisible(binding.gifPanel, false)
            setVisible(binding.hdrButton, false)
            applyHdrMode(false)
            return
        }

        setVisible(binding.hdrBadge, state.hasGainMap)
        setVisible(binding.hdrButton, state.hasGainMap)
        setVisible(binding.liveBadge, state.liveAvailable)
        setVisible(binding.liveButton, state.liveAvailable)
        setVisible(binding.liveMuteButton, state.liveAvailable)
        setVisible(binding.liveSpinner, state.liveBusy)
        val liveVisualKey = "${state.liveBusy}/${state.livePlaying}"
        if (lastLiveVisual != liveVisualKey) {
            lastLiveVisual = liveVisualKey
            val livePlaying = state.livePlaying
            binding.liveLabel.text = getString(
                when {
                    state.liveBusy -> R.string.live_busy_short
                    livePlaying -> R.string.live_playing
                    else -> R.string.live_badge
                }
            )
            binding.liveIcon.setImageResource(
                if (livePlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            binding.liveButton.setBackgroundResource(
                if (livePlaying) R.drawable.bg_pill_accent else R.drawable.bg_pill_dark
            )
            val liveContentColor = ContextCompat.getColor(
                this,
                if (livePlaying) R.color.on_accent else R.color.white
            )
            binding.liveLabel.setTextColor(liveContentColor)
            binding.liveIcon.imageTintList = ColorStateList.valueOf(liveContentColor)
            binding.liveSpinner.indeterminateTintList = ColorStateList.valueOf(liveContentColor)
            binding.liveBadge.text = getString(
                if (livePlaying) R.string.live_playing else R.string.live_badge
            )
            binding.liveBadge.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (livePlaying) R.color.accent else R.color.white
                )
            )
        }
        val favorite = app.userData.isFavorite(photo)
        binding.favoriteButton.setImageResource(
            if (favorite) R.drawable.ic_star else R.drawable.ic_star_border
        )
        binding.favoriteButton.contentDescription = getString(
            if (favorite) R.string.unfavorite else R.string.favorite
        )
        binding.favoriteButton.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (favorite) R.color.hdr_gold else R.color.white
            )
        )
        val muteIcon = if (state.liveMuted) R.drawable.ic_volume_off else R.drawable.ic_volume
        if (lastMuteIconRes != muteIcon) {
            lastMuteIconRes = muteIcon
            binding.liveMuteButton.setImageResource(muteIcon)
        }
        binding.liveMuteButton.contentDescription = getString(
            if (state.liveMuted) R.string.live_unmute else R.string.live_mute
        )

        val gifSteppable = state.isGif && state.gifReady && state.gifFrameCount > 1
        if (!gifSteppable) gifControlsExpanded = false
        setVisible(binding.gifFramesButton, gifSteppable)
        if (gifSteppable) {
            val label = getString(
                if (gifControlsExpanded) R.string.gif_controls_collapse
                else R.string.gif_controls_entry
            )
            binding.gifFramesLabel.text = label
            binding.gifFramesButton.contentDescription = label
        }
        applyHdrMode(state.hasGainMap)
        updateGifControls(state, gifSteppable)
    }

    private fun updateGifControls(state: PhotoPageHolder.UiState, gifSteppable: Boolean) {
        val show = gifSteppable && gifControlsExpanded
        setVisible(binding.gifPanel, show)
        if (!show) return

        val count = state.gifFrameCount
        setVisible(binding.frameSlider, true)
        setVisible(binding.gifPrev, true)
        setVisible(binding.gifNext, true)
        binding.gifPlayPause.isEnabled = true
        if (!sliderTracking) {
            val index = state.gifFrameIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
            binding.frameCounter.text = getString(
                R.string.frame_counter,
                index + 1,
                count
            )
            updateFrameSlider(count, state.gifFrameIndex)
        }
        val playIcon = if (state.gifPlaying) R.drawable.ic_pause else R.drawable.ic_play
        if (lastPlayIconRes != playIcon) {
            lastPlayIconRes = playIcon
            binding.gifPlayPause.setImageResource(playIcon)
        }
        binding.gifPlayPause.contentDescription = getString(
            if (state.gifPlaying) R.string.gif_pause else R.string.gif_play
        )
    }

    private fun toggleGifControls() {
        if (!::adapter.isInitialized) return
        val holder = adapter.activeHolder ?: return
        val state = holder.uiState
        if (!state.isGif || !state.gifReady || state.gifFrameCount <= 1) return
        if (gifControlsExpanded) {
            gifControlsExpanded = false
            holder.resumeGifPlayback()
        } else {
            gifControlsExpanded = true
            holder.beginGifSeek()
        }
        refreshChrome()
    }

    private fun updateFrameSlider(count: Int, index: Int) {
        val max = (count - 1).coerceAtLeast(1).toFloat()
        val target = index.coerceIn(0, (count - 1).coerceAtLeast(0)).toFloat()
        updatingSlider = true
        try {
            if (binding.frameSlider.value > max) {
                binding.frameSlider.value = 0f
            }
            if (binding.frameSlider.valueTo != max) {
                binding.frameSlider.valueTo = max
            }
            if (binding.frameSlider.value != target) {
                binding.frameSlider.value = target
            }
        } catch (_: IllegalStateException) {
            // Slider values can briefly be out of range while switching images.
        } finally {
            updatingSlider = false
        }
    }

    private fun subtitle(photo: Photo): String {
        val parts = ArrayList<String>(3)
        if (photo.sortTimeMs > 0) parts.add(Formats.dayLabel(photo.sortTimeMs))
        if (photo.width > 0 && photo.height > 0) {
            parts.add(Formats.dimensions(photo.width, photo.height))
        }
        if (photo.sizeBytes > 0) parts.add(Formats.size(this, photo.sizeBytes))
        return if (parts.isEmpty()) photo.mime else parts.joinToString(" · ")
    }

    private fun scheduleChromeAutoHide() {
        cancelChromeAutoHide()
        val delay = app.prefs.chromeAutoHideMs
        if (delay > 0 && isChromeVisible) binding.root.postDelayed(autoHideChrome, delay.toLong())
    }

    private fun cancelChromeAutoHide() {
        binding.root.removeCallbacks(autoHideChrome)
    }

    private fun showChrome() {
        isChromeVisible = true
        binding.topChrome.visibility = View.VISIBLE
        binding.bottomChrome.visibility = View.VISIBLE
        binding.topChrome.animate().cancel()
        binding.bottomChrome.animate().cancel()
        binding.topChrome.animate().alpha(1f).setDuration(animDuration(160)).start()
        binding.bottomChrome.animate().alpha(1f).setDuration(animDuration(160)).start()
        applySystemBars(true)
        refreshChrome()
        scheduleChromeAutoHide()
    }

    private fun hideChrome() {
        if (!isChromeVisible) return
        cancelChromeAutoHide()
        isChromeVisible = false
        applySystemBars(false)
        binding.topChrome.animate().cancel()
        binding.bottomChrome.animate().cancel()
        binding.topChrome.animate()
            .alpha(0f)
            .setDuration(animDuration(160))
            .withEndAction {
                if (!isChromeVisible) binding.topChrome.visibility = View.INVISIBLE
            }
            .start()
        binding.bottomChrome.animate()
            .alpha(0f)
            .setDuration(animDuration(160))
            .withEndAction {
                if (!isChromeVisible) binding.bottomChrome.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun animDuration(baseMs: Long): Long =
        (baseMs / app.prefs.animationScale).toLong().coerceAtLeast(20L)

    private fun toggleChrome() {
        if (isChromeVisible) hideChrome() else showChrome()
    }

    private fun pageBy(delta: Int) {
        if (!::adapter.isInitialized) return
        val current = binding.pager.currentItem
        val target = (current + delta).coerceIn(0, photos.size - 1)
        if (target == current) {
            if (delta > 0 && photos.isNotEmpty() && current >= photos.size - 1) {
                AppHint.show(this, getString(R.string.last_page_hint))
            }
            return
        }
        val mode = app.prefs.transitionMode
        if (mode == TransitionMode.NONE) {
            binding.pager.setCurrentItem(target, false)
            return
        }
        if (kotlin.math.abs(target - current) != 1 || binding.pager.width <= 0) {
            binding.pager.setCurrentItem(target, false)
            return
        }
        animatePageTo(target)
    }

    private fun animatePageTo(target: Int) {
        val direction = if (target > binding.pager.currentItem) 1 else -1
        pageAnimator?.cancel()
        if (binding.pager.isFakeDragging) {
            runCatching { binding.pager.endFakeDrag() }
        }
        if (!binding.pager.beginFakeDrag()) {
            Log.d(TAG, "fake drag unavailable, jumping to $target")
            binding.pager.setCurrentItem(target, false)
            return
        }
        val width = binding.pager.width.toFloat()
        val duration = app.prefs.transitionDurationMs.toLong()
        Log.d(TAG, "page anim -> $target duration=${duration}ms")
        var traveled = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
        }
        animator.addUpdateListener { valueAnimator ->
            val fraction = valueAnimator.animatedValue as Float
            val next = width * fraction
            binding.pager.fakeDragBy(-direction * (next - traveled))
            traveled = next
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                releasePageDrag()
                verifyPage(target)
            }

            override fun onAnimationCancel(animation: Animator) {
                releasePageDrag()
                verifyPage(target)
            }
        })
        pageAnimator = animator
        animator.start()
    }

    /**
     * A settled fake drag can snap back to the previous page. Check only after the pager
     * has had time to settle, otherwise the correction would cut the transition short.
     */
    private fun verifyPage(target: Int) {
        val delay = app.prefs.transitionDurationMs + 300L
        binding.pager.postDelayed({
            if (isFinishing || !::adapter.isInitialized) return@postDelayed
            if (binding.pager.isFakeDragging || binding.pager.currentItem == target) return@postDelayed
            Log.d(TAG, "page $target not reached from ${binding.pager.currentItem}, correcting")
            binding.pager.setCurrentItem(target, false)
            adapter.setActivePosition(target)
            refreshChrome()
        }, delay)
    }

    private fun releasePageDrag() {
        if (::adapter.isInitialized && binding.pager.isFakeDragging) {
            runCatching { binding.pager.endFakeDrag() }
        }
        pageAnimator = null
    }

    private fun applyPageTransformer() {
        val mode = app.prefs.transitionMode
        Log.d(TAG, "transition mode=$mode")
        if (mode == TransitionMode.FADE) {
            binding.pager.setPageTransformer { page, position ->
                val distance = kotlin.math.abs(position)
                if (distance >= 1f) {
                    // Settled neighbour: park it back in its own slot. An alpha-0 view
                    // still receives touches, so leaving it stacked over the current
                    // page would swallow every tap.
                    page.alpha = 0f
                    page.translationX = 0f
                    return@setPageTransformer
                }
                page.alpha = (1f - distance).coerceIn(0f, 1f)
                // Overlap both pages in place so they cross-dissolve instead of sliding.
                page.translationX = -(page.x - page.translationX)
            }
        } else {
            binding.pager.setPageTransformer(null)
            resetPageTransforms()
        }
    }

    /** Leaving fade mode must clear the offsets and alphas the fade left on live pages. */
    private fun resetPageTransforms() {
        val list = binding.pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i) ?: continue
            child.translationX = 0f
            child.alpha = 1f
        }
    }

    private fun applyHdrMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 34) return
        val target = if (enabled && app.prefs.hdrEnabled) {
            ActivityInfo.COLOR_MODE_HDR
        } else {
            ActivityInfo.COLOR_MODE_DEFAULT
        }
        if (lastHdrMode == target) return
        lastHdrMode = target
        Log.d(TAG, "HDR mode -> ${if (target == ActivityInfo.COLOR_MODE_HDR) "HDR" else "SDR"} gainmap=$enabled")
        runCatching { window.colorMode = target }
    }

    private fun editCurrent() {
        val photo = photos.getOrNull(binding.pager.currentItem) ?: return
        EditActivity.start(this, photo)
    }

    private fun shareCurrent() {
        val photo = photos.getOrNull(binding.pager.currentItem) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = photo.mime.ifBlank { "image/*" }
            putExtra(Intent.EXTRA_STREAM, photo.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (_: ActivityNotFoundException) {
            AppHint.show(this, getString(R.string.share_failed))
        }
    }

    private fun requestDeleteCurrent() {
        if (Build.VERSION.SDK_INT < 30) {
            AppHint.show(this, getString(R.string.delete_not_supported))
            return
        }
        val position = binding.pager.currentItem
        val photo = photos.getOrNull(position) ?: return
        pendingDeleteId = photo.id
        pendingDeletePosition = position
        try {
            val sender = MediaStore.createDeleteRequest(contentResolver, listOf(photo.uri)).intentSender
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (t: Throwable) {
            AppHint.show(this, getString(R.string.delete_failed))
        }
    }

    private fun removeDeletedPhoto() {
        val index = photos.indexOfFirst { it.id == pendingDeleteId }
        if (index !in photos.indices) {
            app.gallery.markDirty()
            return
        }
        photos.removeAt(index)
        app.gallery.markDirty()
        adapter.notifyItemRemoved(index)
        if (photos.isEmpty()) {
            finish()
            return
        }
        lastHeaderPhotoId = -1L
        lastIndicator = null
        val target = index.coerceAtMost(photos.size - 1)
        binding.pager.post {
            if (isFinishing) return@post
            binding.pager.setCurrentItem(target, false)
            adapter.setActivePosition(binding.pager.currentItem)
            refreshChrome()
        }
        AppHint.show(this, getString(R.string.deleted))
    }

    private fun showDetails() {
        val photo = photos.getOrNull(binding.pager.currentItem) ?: return
        val state = adapter.activeHolder?.uiState
        val hdr = state?.hasGainMap == true
        val live = state?.liveAvailable == true
        lifecycleScope.launch {
            val exifLines = withContext(Dispatchers.IO) { readExif(photo) }
            if (isFinishing || isDestroyed) return@launch
            val type = buildString {
                append(photo.mime.ifBlank { getString(R.string.unknown) })
                if (photo.isGif) append(" · GIF")
                if (state?.isAnimatedGraphic == true) append(" · ${getString(R.string.animated_webp)}")
                if (live) append(" · ${getString(R.string.live_badge)}")
                if (hdr) append(" · HDR")
            }
            val message = buildString {
                append(getString(R.string.detail_name)).append("：").append(photo.name).append('\n')
                append(getString(R.string.detail_path)).append("：").append(photo.displayPath).append('\n')
                append(getString(R.string.detail_size)).append("：")
                    .append(Formats.size(this@ViewerActivity, photo.sizeBytes)).append('\n')
                append(getString(R.string.detail_dimensions)).append("：")
                    .append(Formats.dimensions(photo.width, photo.height)).append('\n')
                append(getString(R.string.detail_date)).append("：")
                    .append(Formats.dateTime(photo.sortTimeMs)).append('\n')
                append(getString(R.string.detail_type)).append("：").append(type).append('\n')
                append(getString(R.string.detail_hdr)).append("：")
                    .append(getString(if (hdr) R.string.yes else R.string.no)).append('\n')
                append(getString(R.string.detail_live)).append("：")
                    .append(getString(if (live) R.string.yes else R.string.no))
                val tags = app.userData.tagsFor(photo)
                append('\n').append(getString(R.string.detail_tags)).append("：")
                    .append(if (tags.isEmpty()) getString(R.string.tags_none) else tags.joinToString("、"))
                if (exifLines.isNotEmpty()) {
                    append('\n')
                    exifLines.forEach { (label, value) ->
                        append(label).append("：").append(value).append('\n')
                    }
                }
            }
            MaterialAlertDialogBuilder(this@ViewerActivity)
                .setTitle(R.string.detail_title)
                .setMessage(message)
                .setPositiveButton(R.string.close, null)
                .showMotion()
        }
    }

    private fun toggleFavorite() {
        val photo = photos.getOrNull(binding.pager.currentItem) ?: return
        app.userData.toggleFavorite(photo)
        refreshChrome()
    }

    private fun editTags() {
        val photo = photos.getOrNull(binding.pager.currentItem) ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.tag_hint)
            setText(app.userData.tagsFor(photo).joinToString(", "))
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_tags)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val tags = input.text.toString()
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                app.userData.setTags(listOf(photo), tags)
                AppHint.show(this, getString(R.string.tag_saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .showMotion()
    }

    private fun readExif(photo: Photo): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            contentResolver.openInputStream(photo.uri)?.use { input ->
                val exif = ExifInterface(input)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                val camera = listOfNotNull(
                    make?.takeIf { it.isNotBlank() },
                    model?.takeIf { it.isNotBlank() }
                ).joinToString(" ")
                if (camera.isNotBlank()) out.add(getString(R.string.exif_camera) to camera)

                exif.getAttribute(ExifInterface.TAG_LENS_MODEL)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out.add(getString(R.string.exif_lens) to it) }
                val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    ?: exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                iso?.takeIf { it.isNotBlank() }?.let {
                    out.add(getString(R.string.exif_iso) to it)
                }
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out.add(getString(R.string.exif_exposure) to "$it 秒") }
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw ->
                        val value = rational(raw)
                        out.add(
                            getString(R.string.exif_aperture) to
                                (value?.let { String.format(Locale.US, "f/%.1f", it) } ?: raw)
                        )
                    }
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw ->
                        val value = rational(raw)
                        out.add(
                            getString(R.string.exif_focal) to
                                (value?.let { String.format(Locale.US, "%.1f mm", it) } ?: raw)
                        )
                    }
                (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out.add(getString(R.string.exif_datetime) to it) }
                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    out.add(
                        getString(R.string.exif_gps) to
                            String.format(Locale.US, "%.5f, %.5f", latLong[0], latLong[1])
                    )
                }
            }
        }
        return out
    }

    private fun rational(raw: String): Float? {
        val parts = raw.split('/')
        return if (parts.size == 2) {
            val numerator = parts[0].toFloatOrNull() ?: return null
            val denominator = parts[1].toFloatOrNull() ?: return null
            if (denominator == 0f) null else numerator / denominator
        } else {
            raw.toFloatOrNull()
        }
    }

    private fun showMoreMenu() {
        val popup = PopupMenu(this, binding.moreButton)
        popup.menuInflater.inflate(R.menu.menu_viewer_actions, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share -> { shareCurrent(); true }
                R.id.action_details -> { showDetails(); true }
                R.id.action_tags -> { editTags(); true }
                R.id.action_wallpaper -> { applyWallpaper(); true }
                R.id.action_contact -> { setContactPhoto(); true }
                R.id.action_print -> { printCurrent(); true }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_exit -> {
                    finish()
                    true
                }
                else -> false
            }
        }
        cancelChromeAutoHide()
        popup.setOnDismissListener { scheduleChromeAutoHide() }
        popup.show()
    }

    private fun toggleHdr() {
        val enabled = !app.prefs.hdrEnabled
        app.prefs.hdrEnabled = enabled
        val hasGainMap = adapter.activeHolder?.uiState?.hasGainMap == true
        applyHdrMode(hasGainMap)
        updateHdrButton()
        AppHint.show(this, getString(if (enabled) R.string.hdr_on else R.string.hdr_off))
    }

    private fun updateHdrButton() {
        val enabled = app.prefs.hdrEnabled
        binding.hdrLabel.text = getString(
            if (enabled) R.string.hdr_switch_on else R.string.hdr_switch_off
        )
        binding.hdrLabel.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.hdr_gold else R.color.text_secondary
            )
        )
        binding.hdrButton.alpha = if (enabled) 1f else 0.65f
        binding.hdrButton.contentDescription = getString(
            if (enabled) R.string.hdr_disable else R.string.hdr_enable
        )
    }

    private fun applyWallpaper() {
        val photo = photos.getOrNull(binding.pager.currentItem) ?: return
        WallpaperCropActivity.start(this, photo.uri)
    }

    private fun setContactPhoto() {
        val photo = photos.getOrNull(binding.pager.currentItem) ?: return
        val mime = photo.mime.ifBlank { "image/*" }
        val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
            setDataAndType(photo.uri, mime)
            putExtra("mimeType", mime)
            putExtra(Intent.EXTRA_STREAM, photo.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.export_contact)))
        } catch (_: ActivityNotFoundException) {
            AppHint.show(this, getString(R.string.share_failed))
        }
    }

    private fun printCurrent() {
        val photo = photos.getOrNull(binding.pager.currentItem) ?: return
        val printManager = getSystemService(PrintManager::class.java) ?: return
        try {
            printManager.print(getString(R.string.print_job), ImagePrintAdapter(this, photo), null)
        } catch (t: Throwable) {
            Log.w(TAG, "print failed", t)
            AppHint.show(this, getString(R.string.print_failed))
        }
    }

    private fun applySystemBars(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setVisible(view: View, visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != target) view.visibility = target
    }

    companion object {
        private const val TAG = "SwiftPicViewer"
        private const val SIDE_TAP_ZONE = 0.17f
        private const val EXTRA_ALBUM_KEY = "viewer_album_key"
        private const val EXTRA_POSITION = "viewer_position"
        private const val EXTRA_QUERY = "viewer_query"
        private const val EXTRA_ALBUM_NAME = "viewer_album_name"
        private const val KEY_POSITION = "viewer_position_saved"

        fun start(
            context: Context,
            albumKey: String?,
            position: Int,
            albumName: String?,
            query: String? = null
        ) {
            val intent = Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_POSITION, position)
                if (albumKey != null) putExtra(EXTRA_ALBUM_KEY, albumKey)
                if (albumName != null) putExtra(EXTRA_ALBUM_NAME, albumName)
                if (query != null) putExtra(EXTRA_QUERY, query)
            }
            context.startActivity(intent)
        }
    }
}
