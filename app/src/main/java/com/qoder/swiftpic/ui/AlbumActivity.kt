package com.qoder.swiftpic.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.GalleryFilters
import com.qoder.swiftpic.data.GalleryKeys
import com.qoder.swiftpic.data.GalleryLibrary
import com.qoder.swiftpic.data.MediaPermissions
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.data.TransparencyBackdrop
import com.qoder.swiftpic.data.sortPhotos
import com.qoder.swiftpic.databinding.ActivityAlbumBinding
import com.qoder.swiftpic.databinding.ItemSelectionActionBinding
import com.qoder.swiftpic.swiftPic
import com.qoder.swiftpic.ui.viewer.ViewerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class AlbumActivity : BaseActivity() {

    private lateinit var binding: ActivityAlbumBinding
    private lateinit var adapter: PhotoAdapter
    private val app get() = swiftPic

    override val usesWallpaperBackground: Boolean = true

    private var albumKey: String? = null
    private var albumNameHint: String = ""
    private var albumTitle: String = ""
    private var photos: List<Photo> = emptyList()
    private var loading = false
    private var loadedOnce = false
    private var lastBackdrop: TransparencyBackdrop? = null
    private var lastUserDataVersion = -1
    private var columns = 4
    private var restoreScroll = RecyclerView.NO_POSITION

    private lateinit var favoriteAction: ItemSelectionActionBinding
    private lateinit var coverAction: ItemSelectionActionBinding
    private lateinit var clearCoverAction: ItemSelectionActionBinding

    private val selectionMenuIds = intArrayOf(
        R.id.action_sel_share,
        R.id.action_sel_delete,
        R.id.action_sel_favorite,
        R.id.action_sel_move,
        R.id.action_sel_copy,
        R.id.action_sel_tags,
        R.id.action_sel_cover,
        R.id.action_sel_clear_cover
    )

    private var pendingMove: PendingMove? = null
    private var pendingViewerPhotoId = -1L
    private var pendingViewerPosition = -1
    private var pendingDestinationMove = false
    private var pendingDestinationPhotos: List<Photo> = emptyList()

    // onCreate 从 Bundle 恢复、applyLibrary（photos 就绪后）再解析成 Photo 列表。
    private var pendingDestinationKeys: List<String> = emptyList()
    private var pendingMoveKeys: List<String> = emptyList()
    private var pendingMovePath: String? = null
    private var pendingMoveVolume: String? = null
    private var hasPendingDestination = false
    private var hasPendingMove = false

    private val destinationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val relativePath =
            data.getStringExtra(DestinationActivity.EXTRA_RELATIVE_PATH)
                ?: return@registerForActivityResult
        val volume =
            data.getStringExtra(DestinationActivity.EXTRA_VOLUME)
                ?: return@registerForActivityResult
        val selected = pendingDestinationPhotos
        val move = pendingDestinationMove
        pendingDestinationPhotos = emptyList()
        pendingDestinationMove = false
        if (selected.isEmpty()) return@registerForActivityResult
        executeTransfer(move, selected, relativePath, volume)
    }

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        pendingViewerPhotoId = data.getLongExtra(ViewerActivity.EXTRA_EXIT_PHOTO_ID, -1L)
        pendingViewerPosition = data.getIntExtra(ViewerActivity.EXTRA_EXIT_POSITION, -1)
        Log.d(TAG, "viewer returned pos=$pendingViewerPosition id=$pendingViewerPhotoId")
    }

    private data class PendingMove(
        val photos: List<Photo>,
        val relativePath: String,
        val volume: String
    )

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AppHint.show(this, R.string.deleted)
            adapter.setSelectionMode(false)
            app.gallery.markDirty()
            loadLibrary(force = false, keepScroll = true)
        }
    }

    private val moveLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingMove
        pendingMove = null
        if (result.resultCode == Activity.RESULT_OK && pending != null) {
            performMove(pending)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeUtils.tintToolbarMenu(binding.toolbar)

        albumKey = intent.getStringExtra(EXTRA_ALBUM_KEY)
        albumNameHint = intent.getStringExtra(EXTRA_ALBUM_NAME).orEmpty()
        albumTitle = albumNameHint.ifBlank { getString(R.string.all_photos) }
        restoreScroll = savedInstanceState?.getInt(KEY_SCROLL, RecyclerView.NO_POSITION)
            ?: RecyclerView.NO_POSITION
        if (savedInstanceState != null) {
            pendingViewerPhotoId = savedInstanceState.getLong(KEY_VIEWER_PHOTO_ID, -1L)
            pendingViewerPosition = savedInstanceState.getInt(KEY_VIEWER_POSITION, -1)
            pendingDestinationMove = savedInstanceState.getBoolean(KEY_PENDING_DEST_MOVE, false)
            hasPendingDestination = savedInstanceState.getBoolean(KEY_HAS_PENDING_DEST, false)
            hasPendingMove = savedInstanceState.getBoolean(KEY_HAS_PENDING_MOVE, false)
            pendingDestinationKeys =
                savedInstanceState.getStringArrayList(KEY_PENDING_DEST_KEYS) ?: emptyList()
            pendingMoveKeys =
                savedInstanceState.getStringArrayList(KEY_PENDING_MOVE_KEYS) ?: emptyList()
            pendingMovePath = savedInstanceState.getString(KEY_PENDING_MOVE_PATH)
            pendingMoveVolume = savedInstanceState.getString(KEY_PENDING_MOVE_VOLUME)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::adapter.isInitialized && adapter.selectionMode) {
                    adapter.setSelectionMode(false)
                } else {
                    closeAlbum()
                }
            }
        })

        binding.toolbar.setNavigationOnClickListener {
            if (adapter.selectionMode) adapter.setSelectionMode(false) else closeAlbum()
        }
        binding.toolbar.title = albumTitle
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> {
                    showSortDialog()
                    true
                }
                R.id.action_select -> {
                    if (adapter.selectionMode) {
                        if (adapter.selectionCount() == photos.size) adapter.clearSelection()
                        else adapter.selectAll()
                    } else {
                        adapter.setSelectionMode(true)
                    }
                    true
                }
                R.id.action_refresh -> {
                    loadLibrary(force = true, keepScroll = true)
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_sel_share -> {
                    if (requireSelection()) shareSelected()
                    true
                }
                R.id.action_sel_delete -> {
                    if (requireSelection()) confirmDeleteSelected()
                    true
                }
                R.id.action_sel_favorite -> {
                    if (requireSelection()) toggleFavoriteSelected()
                    true
                }
                R.id.action_sel_move -> {
                    if (requireSelection()) showDestinationDialog(move = true)
                    true
                }
                R.id.action_sel_copy -> {
                    if (requireSelection()) showDestinationDialog(move = false)
                    true
                }
                R.id.action_sel_tags -> {
                    if (requireSelection()) editTagsSelected()
                    true
                }
                R.id.action_sel_cover -> {
                    if (requireSelection()) setCoverSelected()
                    true
                }
                R.id.action_sel_clear_cover -> {
                    clearCoverSelected()
                    true
                }
                else -> false
            }
        }

        adapter = PhotoAdapter(lifecycleScope, app.thumbnails) { photoIndex ->
            viewerLauncher.launch(
                ViewerActivity.intent(
                    context = this,
                    albumKey = albumKey,
                    position = photoIndex,
                    albumName = albumTitle
                )
            )
        }
        adapter.onSelectionChanged = { updateSelectionUi() }

        setupSelectionBar()

        columns = app.prefs.gridColumns.takeIf { it in 3..6 } ?: galleryColumns(this)
        val layoutManager = GridLayoutManager(this, columns)
        layoutManager.spanSizeLookup = spanLookup(columns)
        binding.photos.layoutManager = layoutManager
        binding.photos.adapter = adapter
        binding.photos.itemAnimator = null
        binding.photos.setHasFixedSize(true)
        binding.photos.setItemViewCacheSize(48)
        binding.fastScroller.attachTo(binding.photos)
        attachPinchToResize()

        if (!MediaPermissions.canReadImages(this)) {
            finish()
            return
        }
        loadLibrary(force = false)
    }

    override fun onResume() {
        super.onResume()
        val backdrop = app.prefs.transparencyBackdrop
        if (lastBackdrop != null && lastBackdrop != backdrop && ::adapter.isInitialized) {
            adapter.notifyMediaChanged()
        }
        lastBackdrop = backdrop
        val prefColumns = app.prefs.gridColumns
        val desiredColumns = if (prefColumns in 3..6) prefColumns else galleryColumns(this)
        if (desiredColumns != columns) updateGridColumns(desiredColumns, persist = false)
        if (!MediaPermissions.canReadImages(this)) {
            finish()
            return
        }
        if (loadedOnce && !loading) {
            val cached = app.gallery.peek()
            if (cached != null && app.userData.version != lastUserDataVersion) {
                applyLibrary(cached, firstVisiblePosition())
            } else if (app.gallery.isDirty()) {
                loadLibrary(force = false, keepScroll = true)
            } else if (hasViewedPending()) {
                applyViewedPhotoScroll(clearIfMissing = true)
            }
        }
    }

    override fun onPause() {
        if (!isFinishing) {
            app.prefs.saveLastAlbum(albumKey, albumTitle)
        }
        super.onPause()
    }

    private fun closeAlbum() {
        app.prefs.clearLastAlbum()
        finish()
    }

    private fun spanLookup(count: Int): GridLayoutManager.SpanSizeLookup {
        return object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (position < 0 || position >= adapter.itemCount) return 1
                return if (adapter.itemAt(position) is PhotoGridItem.Header) count else 1
            }
        }
    }

    private fun updateGridColumns(
        newColumns: Int,
        persist: Boolean = true,
        deferLayout: Boolean = false
    ) {
        val bounded = newColumns.coerceIn(3, 6)
        if (bounded == columns) return
        columns = bounded
        if (persist) app.prefs.gridColumns = bounded
        val layoutManager = binding.photos.layoutManager as? GridLayoutManager ?: return
        layoutManager.spanCount = bounded
        layoutManager.spanSizeLookup = spanLookup(bounded)
        if (!deferLayout) layoutManager.requestLayout()
    }

    private fun attachPinchToResize() {
        val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var lastChange = 0L
            private var scaleFactor = 1f
            private var pointerReset = false

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaleFactor = 1f
                pointerReset = false
                return true
            }

            override fun onScale(scaleDetector: ScaleGestureDetector): Boolean {
                scaleFactor = scaleDetector.scaleFactor
                // ACTION_POINTER_UP 后 detector 会基于剩余指针给出一个失真的
                // scaleFactor，忽略这一帧，避免双指变单指时列数突跳。
                if (pointerReset) {
                    pointerReset = false
                    scaleFactor = 1f
                    return true
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastChange < 220) return true
                val next = when {
                    scaleFactor > 1.08f && columns < 6 -> columns + 1
                    scaleFactor < 0.92f && columns > 3 -> columns - 1
                    else -> return true
                }
                lastChange = now
                updateGridColumns(next, deferLayout = true)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaleFactor = 1f
                // 捏合过程中只改 spanCount，结束时统一重新布局一次。
                binding.photos.requestLayout()
            }

            fun resetScaleFactor() {
                scaleFactor = 1f
                pointerReset = true
            }
        }
        val detector = ScaleGestureDetector(this, listener)
        // setOnTouchListener 返回 false 收不到后续 MOVE、返回 true 又会吞掉滚动和点击；
        // OnItemTouchListener 只观察事件、不拦截，手势与列表交互两不误。
        binding.photos.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    listener.resetScaleFactor()
                }
                detector.onTouchEvent(e)
                return false
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL, firstVisiblePosition())
        outState.putLong(KEY_VIEWER_PHOTO_ID, pendingViewerPhotoId)
        outState.putInt(KEY_VIEWER_POSITION, pendingViewerPosition)
        outState.putBoolean(KEY_PENDING_DEST_MOVE, pendingDestinationMove)
        outState.putBoolean(KEY_HAS_PENDING_DEST, pendingDestinationPhotos.isNotEmpty())
        outState.putStringArrayList(
            KEY_PENDING_DEST_KEYS,
            ArrayList(pendingDestinationPhotos.map { it.stableKey })
        )
        val move = pendingMove
        if (move != null) {
            outState.putBoolean(KEY_HAS_PENDING_MOVE, true)
            outState.putStringArrayList(
                KEY_PENDING_MOVE_KEYS,
                ArrayList(move.photos.map { it.stableKey })
            )
            outState.putString(KEY_PENDING_MOVE_PATH, move.relativePath)
            outState.putString(KEY_PENDING_MOVE_VOLUME, move.volume)
        }
    }

    /** photos 就绪后把 Bundle 里的 stableKey 列表还原成 Photo 列表。 */
    private fun restorePendingTransferState() {
        if (hasPendingDestination && pendingDestinationPhotos.isEmpty()) {
            pendingDestinationPhotos = resolvePhotos(pendingDestinationKeys)
        }
        val path = pendingMovePath
        val volume = pendingMoveVolume
        if (hasPendingMove && pendingMove == null && path != null && volume != null) {
            pendingMove = PendingMove(resolvePhotos(pendingMoveKeys), path, volume)
        }
        pendingDestinationKeys = emptyList()
        pendingMoveKeys = emptyList()
        pendingMovePath = null
        pendingMoveVolume = null
        hasPendingDestination = false
        hasPendingMove = false
    }

    private fun resolvePhotos(keys: List<String>): List<Photo> {
        if (keys.isEmpty()) return emptyList()
        val byKey = HashMap<String, Photo>()
        app.gallery.peek()?.photos?.forEach { byKey[it.stableKey] = it }
        photos.forEach { byKey[it.stableKey] = it }
        return keys.mapNotNull { byKey[it] }
    }

    private fun firstVisiblePosition(): Int =
        (binding.photos.layoutManager as? GridLayoutManager)
            ?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION

    private fun loadLibrary(force: Boolean, keepScroll: Boolean = false) {
        if (loading) return
        loading = true
        val cached = app.gallery.peek()
        if (cached == null) {
            binding.loading.visibility = View.VISIBLE
            binding.photos.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        } else if (force) {
            binding.refreshProgress.visibility = View.VISIBLE
        }

        val firstVisible = if (keepScroll) firstVisiblePosition() else RecyclerView.NO_POSITION

        lifecycleScope.launch {
            try {
                val library = app.gallery.load(force)
                loadedOnce = true
                applyLibrary(library, firstVisible)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadLibrary failed", e)
                showEmpty(getString(R.string.scan_failed), e.message.orEmpty())
            } finally {
                loading = false
                binding.loading.visibility = View.GONE
                binding.refreshProgress.visibility = View.GONE
            }
        }
    }

    private fun applyLibrary(library: GalleryLibrary, firstVisible: Int) {
        if (!library.hasImageAccess) {
            finish()
            return
        }

        val name = when (albumKey) {
            null -> getString(R.string.all_photos)
            GalleryKeys.FAVORITES -> getString(R.string.favorites_album)
            else -> {
                val album = library.albums.firstOrNull { it.key == albumKey }
                if (album == null) {
                    app.prefs.clearLastAlbum()
                    finish()
                    return
                }
                album.name
            }
        }

        photos = GalleryFilters.photosFor(
            library,
            app.userData,
            albumKey,
            null,
            app.prefs.sortMode
        )
        restorePendingTransferState()
        adapter.coverKey = albumKey?.let { app.userData.albumCover(it) }
        lastUserDataVersion = app.userData.version
        albumTitle = name
        binding.toolbar.title = name
        binding.toolbar.subtitle = getString(R.string.photo_count, photos.size)
        binding.toolbar.menu.findItem(R.id.action_sort)?.isVisible = photos.isNotEmpty()
        binding.toolbar.menu.findItem(R.id.action_select)?.isVisible = photos.isNotEmpty()

        if (photos.isEmpty()) {
            adapter.submit(emptyList(), false)
            val title = if (albumKey == GalleryKeys.FAVORITES) {
                getString(R.string.favorites_album)
            } else {
                getString(R.string.empty_title)
            }
            showEmpty(title, getString(R.string.empty_message))
            updateSelectionUi()
            return
        }

        binding.emptyState.visibility = View.GONE
        binding.photos.visibility = View.VISIBLE
        val headers = albumKey == null && app.prefs.groupPhotosByDate
        adapter.submit(photos, headers)
        updateSelectionUi()

        val viewed = resolveViewedIndex()
        val viewedPosition = if (viewed >= 0) adapter.adapterPositionOfPhoto(viewed) else RecyclerView.NO_POSITION
        val target = when {
            viewedPosition != RecyclerView.NO_POSITION -> viewedPosition
            firstVisible != RecyclerView.NO_POSITION -> firstVisible
            restoreScroll != RecyclerView.NO_POSITION -> restoreScroll
            else -> RecyclerView.NO_POSITION
        }
        if (target != RecyclerView.NO_POSITION) {
            val clamped = target.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
            Log.d(TAG, "applyLibrary viewed=$viewed viewedPos=$viewedPosition target=$clamped first=$firstVisible")
            binding.photos.post {
                if (viewedPosition != RecyclerView.NO_POSITION) {
                    (binding.photos.layoutManager as? GridLayoutManager)
                        ?.scrollToPositionWithOffset(clamped, 0)
                } else {
                    binding.photos.scrollToPosition(clamped)
                }
                if (viewed >= 0) clearViewedPending()
            }
            restoreScroll = RecyclerView.NO_POSITION
        }
    }

    private fun resolveViewedIndex(): Int {
        if (pendingViewerPhotoId <= 0 && pendingViewerPosition < 0) return -1
        val byId = photos.indexOfFirst { it.id == pendingViewerPhotoId }
        return when {
            byId >= 0 -> byId
            pendingViewerPosition in photos.indices -> pendingViewerPosition
            else -> -1
        }
    }

    private fun clearViewedPending() {
        pendingViewerPhotoId = -1L
        pendingViewerPosition = -1
    }

    private fun hasViewedPending(): Boolean =
        pendingViewerPhotoId > 0 || pendingViewerPosition >= 0

    private fun applyViewedPhotoScroll(clearIfMissing: Boolean) {
        val index = resolveViewedIndex()
        if (index < 0) {
            if (clearIfMissing) clearViewedPending()
            return
        }
        val position = adapter.adapterPositionOfPhoto(index).let {
            if (it == RecyclerView.NO_POSITION) index else it
        }
        Log.d(TAG, "restore scroll photo=$index adapterPos=$position")
        binding.photos.post {
            (binding.photos.layoutManager as? GridLayoutManager)
                ?.scrollToPositionWithOffset(position, 0)
            clearViewedPending()
        }
    }

    private fun refreshFromCache() {
        val cached = app.gallery.peek()
        if (cached == null) {
            loadLibrary(force = false, keepScroll = true)
            return
        }
        applyLibrary(cached, firstVisiblePosition())
    }

    private fun showEmpty(title: String, message: String) {
        binding.photos.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyTitle.text = title
        binding.emptyMessage.text = message
        binding.loading.visibility = View.GONE
        binding.toolbar.subtitle = null
    }

    private fun showSortDialog() {
        showPhotoSortDialog(this, app.prefs) { mode ->
            lifecycleScope.launch {
                // 排序是 O(n log n)，几万张时不能压在主线程；提交仍回主线程。
                val sorted = withContext(Dispatchers.IO) { sortPhotos(photos, mode) }
                photos = sorted
                adapter.submit(photos, albumKey == null && app.prefs.groupPhotosByDate)
                binding.photos.post { binding.photos.scrollToPosition(0) }
            }
        }
    }

    private fun setupSelectionBar() {
        addSelectionAction(R.drawable.ic_share, getString(R.string.share)) { shareSelected() }
        addSelectionAction(R.drawable.ic_delete, getString(R.string.delete)) { confirmDeleteSelected() }
        favoriteAction = addSelectionAction(R.drawable.ic_star_border, getString(R.string.favorite)) {
            toggleFavoriteSelected()
        }
        addSelectionAction(R.drawable.ic_move, getString(R.string.move)) {
            showDestinationDialog(move = true)
        }
        addSelectionAction(R.drawable.ic_copy, getString(R.string.copy)) {
            showDestinationDialog(move = false)
        }
        addSelectionAction(R.drawable.ic_tag, getString(R.string.tags)) { editTagsSelected() }
        addSelectionAction(R.drawable.ic_info, getString(R.string.view_details)) {
            val selected = selectedPhotos()
            if (selected.size == 1) {
                MediaDetails.showPhoto(this, selected.first())
            } else {
                AppHint.show(this, R.string.select_one_for_details)
            }
        }
        coverAction = addSelectionAction(R.drawable.ic_image, getString(R.string.set_cover)) {
            setCoverSelected()
        }
        clearCoverAction = addSelectionAction(
            R.drawable.ic_close,
            getString(R.string.clear_cover),
            requireSelection = false
        ) {
            clearCoverSelected()
        }
    }

    private fun requireSelection(): Boolean {
        if (adapter.selectionCount() > 0) return true
        AppHint.show(this, R.string.select_first)
        return false
    }

    private fun addSelectionAction(
        iconRes: Int,
        label: String,
        requireSelection: Boolean = true,
        onClick: () -> Unit
    ): ItemSelectionActionBinding {
        val action = ItemSelectionActionBinding.inflate(
            layoutInflater,
            binding.selectionBar.selectionActions,
            false
        )
        action.actionIcon.setImageResource(iconRes)
        action.actionLabel.text = label
        action.root.contentDescription = label
        action.root.setOnClickListener {
            if (requireSelection && adapter.selectionCount() == 0) {
                AppHint.show(this, R.string.select_first)
                return@setOnClickListener
            }
            onClick()
        }
        binding.selectionBar.selectionActions.addView(action.root)
        return action
    }

    private fun updateSelectionUi() {
        val selecting = ::adapter.isInitialized && adapter.selectionMode
        binding.selectionBar.root.visibility = if (selecting) View.VISIBLE else View.GONE
        if (!selecting) {
            binding.toolbar.title = albumTitle
            binding.toolbar.subtitle = if (photos.isEmpty()) null
            else getString(R.string.photo_count, photos.size)
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.toolbar.menu.findItem(R.id.action_sort)?.isVisible = photos.isNotEmpty()
            binding.toolbar.menu.findItem(R.id.action_select)?.isVisible = photos.isNotEmpty()
            binding.toolbar.menu.findItem(R.id.action_select)?.title =
                getString(R.string.selection_mode)
            binding.toolbar.menu.findItem(R.id.action_refresh)?.isVisible = true
            selectionMenuIds.forEach { binding.toolbar.menu.findItem(it)?.isVisible = false }
            return
        }
        binding.toolbar.title = getString(R.string.selected_count, adapter.selectionCount())
        binding.toolbar.subtitle = null
        binding.toolbar.setNavigationIcon(R.drawable.ic_close)
        binding.toolbar.menu.findItem(R.id.action_sort)?.isVisible = false
        binding.toolbar.menu.findItem(R.id.action_select)?.isVisible = true
        binding.toolbar.menu.findItem(R.id.action_select)?.title = getString(R.string.select_all)
        binding.toolbar.menu.findItem(R.id.action_refresh)?.isVisible = false

        val selected = adapter.selectedPhotos()
        val allFavorite = selected.isNotEmpty() && selected.all { app.userData.isFavorite(it) }
        val favoriteLabel = getString(if (allFavorite) R.string.unfavorite else R.string.favorite)
        favoriteAction.actionLabel.text = favoriteLabel
        favoriteAction.actionIcon.setImageResource(
            if (allFavorite) R.drawable.ic_star else R.drawable.ic_star_border
        )
        binding.toolbar.menu.findItem(R.id.action_sel_favorite)?.title = favoriteLabel
        val key = albumKey
        val coverAllowed = key != null && key != GalleryKeys.FAVORITES
        val coverExists = coverAllowed &&
            key?.let { app.userData.albumCover(it) != null } == true
        coverAction.root.visibility = if (coverAllowed) View.VISIBLE else View.GONE
        clearCoverAction.root.visibility = if (coverExists) View.VISIBLE else View.GONE
        selectionMenuIds.forEach { binding.toolbar.menu.findItem(it)?.isVisible = true }
        binding.toolbar.menu.findItem(R.id.action_sel_cover)?.isVisible = coverAllowed
        binding.toolbar.menu.findItem(R.id.action_sel_clear_cover)?.isVisible = coverExists
    }

    private fun selectedPhotos(): List<Photo> = adapter.selectedPhotos()

    private fun shareSelected() {
        val selected = selectedPhotos()
        if (selected.isEmpty()) return
        val uris = ArrayList<Uri>(selected.map { it.uri })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share)))
            adapter.setSelectionMode(false)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "share failed", e)
            AppHint.show(this, R.string.share_failed)
        }
    }

    private fun confirmDeleteSelected() {
        val selected = selectedPhotos()
        if (selected.isEmpty()) return
        if (Build.VERSION.SDK_INT < 30) {
            AppHint.show(this, R.string.delete_not_supported)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_selected_title, selected.size))
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.delete) { _, _ -> requestDelete(selected) }
            .setNegativeButton(R.string.cancel, null)
            .showMotion()
    }

    private fun requestDelete(selected: List<Photo>) {
        try {
            val sender = MediaStore.createDeleteRequest(contentResolver, selected.map { it.uri })
                .intentSender
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "requestDelete failed", e)
            AppHint.show(this, R.string.delete_failed)
        }
    }

    private fun showDestinationDialog(move: Boolean) {
        val selected = selectedPhotos()
        if (selected.isEmpty()) return
        if (move && Build.VERSION.SDK_INT < 30) {
            AppHint.show(this, R.string.move_not_supported)
            return
        }
        if (!move && Build.VERSION.SDK_INT < 29) {
            AppHint.show(this, R.string.copy_not_supported)
            return
        }
        val library = app.gallery.peek() ?: return
        if (library.albums.isEmpty()) {
            AppHint.show(this, R.string.destination_empty)
            return
        }
        val sourceDirs = selected.map { it.volumeName to it.relativePath.trim('/') }.toSet()
        val singleSource = sourceDirs.singleOrNull()
        val defaultVolume = selected.map { it.volumeName }.distinct().singleOrNull()
            ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        pendingDestinationMove = move
        pendingDestinationPhotos = selected
        destinationLauncher.launch(
            DestinationActivity.intent(
                context = this,
                move = move,
                count = selected.size,
                sourceVolume = singleSource?.first,
                sourcePath = singleSource?.second,
                defaultVolume = defaultVolume
            )
        )
    }

    private fun executeTransfer(
        move: Boolean,
        selected: List<Photo>,
        relativePath: String,
        volume: String
    ) {
        if (move) requestMove(selected, relativePath, volume)
        else copyPhotos(selected, relativePath, volume)
    }

    private fun requestMove(selected: List<Photo>, relativePath: String, volume: String) {
        val uris = ArrayList<Uri>(selected.size + 2)
        selected.forEach { photo ->
            if (photo.volumeName != volume) return@forEach
            uris.add(photo.uri)
            photo.liveVideoUri?.let { uris.add(it) }
        }
        if (uris.isEmpty()) {
            AppHint.show(this, getString(R.string.transfer_failed, 0))
            return
        }
        pendingMove = PendingMove(selected, relativePath, volume)
        try {
            val sender = MediaStore.createWriteRequest(contentResolver, uris).intentSender
            moveLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: CancellationException) {
            pendingMove = null
            throw e
        } catch (e: Exception) {
            pendingMove = null
            Log.w(TAG, "requestMove failed", e)
            AppHint.show(this, getString(R.string.transfer_failed, 0))
        }
    }

    private fun performMove(pending: PendingMove) {
        lifecycleScope.launch {
            val moved = withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, pending.relativePath)
                }
                var count = 0
                pending.photos.forEach { photo ->
                    if (photo.volumeName != pending.volume) return@forEach
                    val ok = try {
                        contentResolver.update(photo.uri, values, null, null) > 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "move update failed uri=${photo.uri}", e)
                        false
                    }
                    if (ok) {
                        photo.liveVideoUri?.let { video ->
                            try {
                                contentResolver.update(video, values, null, null)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "move live video update failed uri=$video", e)
                            }
                        }
                        count++
                    }
                }
                count
            }
            adapter.setSelectionMode(false)
            app.gallery.markDirty()
            AppHint.show(this@AlbumActivity, if (moved > 0) getString(R.string.move_done, moved) else getString(R.string.transfer_failed, moved))
            loadLibrary(force = false, keepScroll = true)
        }
    }

    private fun copyPhotos(selected: List<Photo>, relativePath: String, volume: String) {
        AppHint.show(this, R.string.processing)
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                var count = 0
                selected.forEach { photo ->
                    val ok = try {
                        copyOne(photo, relativePath, volume)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "copy failed name=${photo.name}", e)
                        false
                    }
                    if (ok) count++
                }
                count
            }
            adapter.setSelectionMode(false)
            app.gallery.markDirty()
            AppHint.show(this@AlbumActivity, if (copied > 0) getString(R.string.copy_done, copied) else getString(R.string.transfer_failed, copied))
            loadLibrary(force = false, keepScroll = true)
        }
    }

    private fun copyOne(photo: Photo, relativePath: String, volume: String): Boolean {
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val base = photo.name.substringBeforeLast('.', photo.name)
        val ext = photo.name.substringAfterLast('.', "").ifBlank { "jpg" }
        val copyBase = "${base}_copy"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$copyBase.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, photo.mime.ifBlank { "image/jpeg" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (photo.dateTakenMs > 0) {
                put(MediaStore.MediaColumns.DATE_TAKEN, photo.dateTakenMs)
            }
        }
        val dest = contentResolver.insert(collection, values) ?: return false
        try {
            contentResolver.openOutputStream(dest)?.use { output ->
                contentResolver.openInputStream(photo.uri)?.use { input ->
                    input.copyTo(output)
                } ?: throw IOException("open image failed")
            } ?: throw IOException("open destination failed")
            finalizePending(dest)
        } catch (e: Exception) {
            try {
                contentResolver.delete(dest, null, null)
            } catch (cleanup: CancellationException) {
                throw cleanup
            } catch (cleanup: Exception) {
                Log.w(TAG, "copy cleanup failed", cleanup)
            }
            throw e
        }
        photo.liveVideoUri?.let { video ->
            try {
                copyLiveVideo(video, relativePath, volume, copyBase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "copy live video failed uri=$video", e)
            }
        }
        return true
    }

    private fun copyLiveVideo(
        videoUri: Uri,
        relativePath: String,
        volume: String,
        copyBase: String
    ) {
        var displayName: String? = null
        var mime = "video/mp4"
        var dateTaken = 0L
        contentResolver.query(
            videoUri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_TAKEN
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.getString(0)
                mime = cursor.getString(1) ?: mime
                dateTaken = cursor.getLong(2)
            }
        }
        val ext = displayName?.substringAfterLast('.', "")?.ifBlank { "mp4" } ?: "mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$copyBase.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (dateTaken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
        }
        val collection = MediaStore.Video.Media.getContentUri(volume)
        val dest = contentResolver.insert(collection, values) ?: return
        try {
            contentResolver.openOutputStream(dest)?.use { output ->
                contentResolver.openInputStream(videoUri)?.use { input ->
                    input.copyTo(output)
                } ?: throw IOException("open video failed")
            } ?: throw IOException("open video destination failed")
            finalizePending(dest)
        } catch (e: Exception) {
            try {
                contentResolver.delete(dest, null, null)
            } catch (cleanup: CancellationException) {
                throw cleanup
            } catch (cleanup: Exception) {
                Log.w(TAG, "live video cleanup failed", cleanup)
            }
            throw e
        }
    }

    private fun finalizePending(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        contentResolver.update(uri, values, null, null)
    }

    private fun toggleFavoriteSelected() {
        val selected = selectedPhotos()
        if (selected.isEmpty()) return
        val data = app.userData
        val allFavorite = selected.all { data.isFavorite(it) }
        data.setFavorite(selected, !allFavorite)
        AppHint.show(this, if (allFavorite) R.string.favorite_removed else R.string.favorite_added)
        adapter.setSelectionMode(false)
        refreshFromCache()
    }

    private fun editTagsSelected() {
        val selected = selectedPhotos()
        if (selected.isEmpty()) return
        val current = if (selected.size == 1) app.userData.tagsFor(selected.first()) else emptyList()
        val density = resources.displayMetrics.density
        val horizontal = (14 * density).toInt()
        val vertical = (12 * density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(current.joinToString(", "))
            hint = getString(R.string.tag_hint)
            setSingleLine(true)
            setBackgroundResource(R.drawable.bg_search_field)
            minHeight = (52 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_tags)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val tags = input.text.toString()
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                app.userData.setTags(selected, tags)
                AppHint.show(this, R.string.tag_saved)
                adapter.setSelectionMode(false)
            }
            .setNegativeButton(R.string.cancel, null)
            .showMotion()
    }

    private fun setCoverSelected() {
        val key = albumKey
        if (key == null || key == GalleryKeys.FAVORITES) return
        val photo = selectedPhotos().firstOrNull() ?: return
        app.userData.setAlbumCover(key, photo)
        val oldKey = adapter.coverKey
        adapter.coverKey = photo.stableKey
        val oldPosition = oldKey?.let { adapter.positionOfKey(it) } ?: RecyclerView.NO_POSITION
        val newPosition = adapter.positionOfKey(photo.stableKey)
        if (oldPosition != RecyclerView.NO_POSITION && oldPosition != newPosition) {
            adapter.notifyItemChanged(oldPosition, PhotoAdapter.PAYLOAD_COVER)
        }
        if (newPosition != RecyclerView.NO_POSITION) {
            adapter.notifyItemChanged(newPosition, PhotoAdapter.PAYLOAD_COVER)
        }
        AppHint.show(this, R.string.cover_set)
        adapter.setSelectionMode(false)
    }

    private fun clearCoverSelected() {
        val key = albumKey
        if (key == null || key == GalleryKeys.FAVORITES) return
        app.userData.clearAlbumCover(key)
        val oldKey = adapter.coverKey
        adapter.coverKey = null
        val oldPosition = oldKey?.let { adapter.positionOfKey(it) } ?: RecyclerView.NO_POSITION
        if (oldPosition != RecyclerView.NO_POSITION) {
            adapter.notifyItemChanged(oldPosition, PhotoAdapter.PAYLOAD_COVER)
        }
        AppHint.show(this, R.string.cover_cleared)
        adapter.setSelectionMode(false)
        updateSelectionUi()
    }

    companion object {
        private const val TAG = "AlbumActivity"
        private const val EXTRA_ALBUM_KEY = "album_key"
        private const val EXTRA_ALBUM_NAME = "album_name"
        private const val KEY_SCROLL = "album_scroll"
        private const val KEY_VIEWER_PHOTO_ID = "album_viewer_photo_id"
        private const val KEY_VIEWER_POSITION = "album_viewer_position"
        private const val KEY_PENDING_DEST_MOVE = "album_pending_dest_move"
        private const val KEY_HAS_PENDING_DEST = "album_has_pending_dest"
        private const val KEY_PENDING_DEST_KEYS = "album_pending_dest_keys"
        private const val KEY_HAS_PENDING_MOVE = "album_has_pending_move"
        private const val KEY_PENDING_MOVE_KEYS = "album_pending_move_keys"
        private const val KEY_PENDING_MOVE_PATH = "album_pending_move_path"
        private const val KEY_PENDING_MOVE_VOLUME = "album_pending_move_volume"

        fun intent(context: Context, albumKey: String?, albumName: String): Intent {
            return Intent(context, AlbumActivity::class.java).apply {
                putExtra(EXTRA_ALBUM_NAME, albumName)
                if (albumKey != null) putExtra(EXTRA_ALBUM_KEY, albumKey)
            }
        }
    }
}
