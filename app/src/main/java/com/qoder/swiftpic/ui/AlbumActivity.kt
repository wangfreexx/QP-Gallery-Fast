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
import android.view.View
import android.view.ScaleGestureDetector
import android.widget.EditText
import android.widget.Toast
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
import com.qoder.swiftpic.data.sortPhotos
import com.qoder.swiftpic.databinding.ActivityAlbumBinding
import com.qoder.swiftpic.databinding.ItemSelectionActionBinding
import com.qoder.swiftpic.swiftPic
import com.qoder.swiftpic.ui.viewer.ViewerActivity
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
    private var lastUserDataVersion = -1
    private var columns = 4
    private var restoreScroll = RecyclerView.NO_POSITION

    private lateinit var favoriteAction: ItemSelectionActionBinding
    private lateinit var coverAction: ItemSelectionActionBinding

    private var pendingMove: PendingMove? = null

    private data class PendingMove(
        val photos: List<Photo>,
        val relativePath: String,
        val volume: String
    )

    private data class TransferTarget(
        val label: String,
        val relativePath: String,
        val volume: String
    )

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
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
                else -> false
            }
        }

        adapter = PhotoAdapter(lifecycleScope, app.thumbnails) { photoIndex ->
            ViewerActivity.start(
                context = this,
                albumKey = albumKey,
                position = photoIndex,
                albumName = albumTitle
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
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::adapter.isInitialized && adapter.selectionMode) {
            adapter.setSelectionMode(false)
            return
        }
        closeAlbum()
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

    private fun updateGridColumns(newColumns: Int, persist: Boolean = true) {
        val bounded = newColumns.coerceIn(3, 6)
        if (bounded == columns) return
        columns = bounded
        if (persist) app.prefs.gridColumns = bounded
        val layoutManager = binding.photos.layoutManager as? GridLayoutManager ?: return
        layoutManager.spanCount = bounded
        layoutManager.spanSizeLookup = spanLookup(bounded)
        layoutManager.requestLayout()
    }

    private fun attachPinchToResize() {
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var lastChange = 0L
            override fun onScale(scaleDetector: ScaleGestureDetector): Boolean {
                val now = SystemClock.uptimeMillis()
                if (now - lastChange < 220) return true
                val next = when {
                    scaleDetector.scaleFactor > 1.08f && columns < 6 -> columns + 1
                    scaleDetector.scaleFactor < 0.92f && columns > 3 -> columns - 1
                    else -> return true
                }
                lastChange = now
                updateGridColumns(next)
                return true
            }
        })
        binding.photos.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL, firstVisiblePosition())
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
            } catch (t: Throwable) {
                showEmpty(getString(R.string.scan_failed), t.message.orEmpty())
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

        val target = when {
            firstVisible != RecyclerView.NO_POSITION -> firstVisible
            restoreScroll != RecyclerView.NO_POSITION -> restoreScroll
            else -> RecyclerView.NO_POSITION
        }
        if (target != RecyclerView.NO_POSITION) {
            val clamped = target.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
            binding.photos.post { binding.photos.scrollToPosition(clamped) }
            restoreScroll = RecyclerView.NO_POSITION
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
            photos = sortPhotos(photos, mode)
            adapter.submit(photos, albumKey == null && app.prefs.groupPhotosByDate)
            binding.photos.post { binding.photos.scrollToPosition(0) }
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
        coverAction = addSelectionAction(R.drawable.ic_image, getString(R.string.set_cover)) {
            setCoverSelected()
        }
    }

    private fun addSelectionAction(
        iconRes: Int,
        label: String,
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
            if (adapter.selectionCount() == 0) {
                Toast.makeText(this, R.string.select_first, Toast.LENGTH_SHORT).show()
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
            return
        }
        binding.toolbar.title = getString(R.string.selected_count, adapter.selectionCount())
        binding.toolbar.subtitle = null
        binding.toolbar.setNavigationIcon(R.drawable.ic_close)
        binding.toolbar.menu.findItem(R.id.action_sort)?.isVisible = false
        binding.toolbar.menu.findItem(R.id.action_select)?.isVisible = true
        binding.toolbar.menu.findItem(R.id.action_select)?.title = getString(R.string.select_all)
        binding.toolbar.menu.findItem(R.id.action_refresh)?.isVisible = false

        val selected = selectedPhotos()
        val allFavorite = selected.isNotEmpty() && selected.all { app.userData.isFavorite(it) }
        favoriteAction.actionLabel.text =
            getString(if (allFavorite) R.string.unfavorite else R.string.favorite)
        favoriteAction.actionIcon.setImageResource(
            if (allFavorite) R.drawable.ic_star else R.drawable.ic_star_border
        )
        coverAction.root.visibility = if (albumKey != null &&
            albumKey != GalleryKeys.FAVORITES
        ) View.VISIBLE else View.GONE
    }

    private fun selectedPhotos(): List<Photo> = photos.filter { adapter.isSelected(it) }

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
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSelected() {
        val selected = selectedPhotos()
        if (selected.isEmpty()) return
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, R.string.delete_not_supported, Toast.LENGTH_SHORT).show()
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
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDestinationDialog(move: Boolean) {
        val selected = selectedPhotos()
        if (selected.isEmpty()) return
        if (move && Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, R.string.move_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        if (!move && Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, R.string.copy_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        val library = app.gallery.peek() ?: return
        val sourceDirs = selected.map { it.volumeName to it.relativePath.trim('/') }.toSet()
        val targets = ArrayList<TransferTarget>()
        val seen = HashSet<String>()
        library.albums.forEach { album ->
            val sample = album.photos.firstOrNull() ?: return@forEach
            val rel = sample.relativePath.trim('/')
            val volume = sample.volumeName
            if (sourceDirs.size == 1 && sourceDirs.first() == (volume to rel)) return@forEach
            val key = "$volume|${rel.lowercase()}"
            if (!seen.add(key)) return@forEach
            targets.add(
                TransferTarget(
                    label = "${album.name}  ·  ${rel.ifBlank { "/" }}",
                    relativePath = if (rel.isEmpty()) "" else "$rel/",
                    volume = volume
                )
            )
        }
        val labels = ArrayList<String>(targets.size + 1)
        labels.add(getString(R.string.new_album))
        targets.forEach { labels.add(it.label) }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (move) R.string.move_to else R.string.copy_to)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    promptNewAlbum(move, selected)
                } else {
                    val target = targets[which - 1]
                    executeTransfer(move, selected, target.relativePath, target.volume)
                }
            }
            .showMotion()
    }

    private fun promptNewAlbum(move: Boolean, selected: List<Photo>) {
        val density = resources.displayMetrics.density
        val horizontal = (14 * density).toInt()
        val vertical = (12 * density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.new_album_hint)
            setSingleLine(true)
            setBackgroundResource(R.drawable.bg_search_field)
            minHeight = (52 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (move) R.string.move_to else R.string.copy_to)
            .setView(input)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener {
                    val name = input.text.toString().trim()
                    if (!isValidAlbumName(name)) {
                        input.error = getString(R.string.new_album_invalid)
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    val volume = selected.map { it.volumeName }.distinct().singleOrNull()
                        ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    executeTransfer(move, selected, "Pictures/$name/", volume)
                }
        }
        dialog.show()
    }

    private fun isValidAlbumName(name: String): Boolean {
        if (name.isEmpty() || name.length > 60) return false
        if (name == "." || name == "..") return false
        return name.none { it in "/\\:*?\"<>|" }
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
            Toast.makeText(this, getString(R.string.transfer_failed, 0), Toast.LENGTH_SHORT).show()
            return
        }
        pendingMove = PendingMove(selected, relativePath, volume)
        try {
            val sender = MediaStore.createWriteRequest(contentResolver, uris).intentSender
            moveLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (t: Throwable) {
            pendingMove = null
            Toast.makeText(this, getString(R.string.transfer_failed, 0), Toast.LENGTH_SHORT).show()
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
                    val ok = runCatching {
                        contentResolver.update(photo.uri, values, null, null) > 0
                    }.getOrDefault(false)
                    if (ok) {
                        photo.liveVideoUri?.let { video ->
                            runCatching { contentResolver.update(video, values, null, null) }
                        }
                        count++
                    }
                }
                count
            }
            adapter.setSelectionMode(false)
            app.gallery.markDirty()
            Toast.makeText(
                this@AlbumActivity,
                if (moved > 0) getString(R.string.move_done, moved)
                else getString(R.string.transfer_failed, moved),
                Toast.LENGTH_SHORT
            ).show()
            loadLibrary(force = false, keepScroll = true)
        }
    }

    private fun copyPhotos(selected: List<Photo>, relativePath: String, volume: String) {
        Toast.makeText(this, R.string.processing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                var count = 0
                selected.forEach { photo ->
                    if (runCatching { copyOne(photo, relativePath, volume) }.getOrDefault(false)) {
                        count++
                    }
                }
                count
            }
            adapter.setSelectionMode(false)
            app.gallery.markDirty()
            Toast.makeText(
                this@AlbumActivity,
                if (copied > 0) getString(R.string.copy_done, copied)
                else getString(R.string.transfer_failed, copied),
                Toast.LENGTH_SHORT
            ).show()
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
        } catch (t: Throwable) {
            runCatching { contentResolver.delete(dest, null, null) }
            throw t
        }
        photo.liveVideoUri?.let { video ->
            runCatching { copyLiveVideo(video, relativePath, volume, copyBase) }
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
        } catch (t: Throwable) {
            runCatching { contentResolver.delete(dest, null, null) }
            throw t
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
        Toast.makeText(
            this,
            if (allFavorite) R.string.favorite_removed else R.string.favorite_added,
            Toast.LENGTH_SHORT
        ).show()
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
                val tags = input.text.toString().split(',', '，')
                app.userData.setTags(selected, tags)
                Toast.makeText(this, R.string.tag_saved, Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, R.string.cover_set, Toast.LENGTH_SHORT).show()
        adapter.setSelectionMode(false)
    }

    companion object {
        private const val EXTRA_ALBUM_KEY = "album_key"
        private const val EXTRA_ALBUM_NAME = "album_name"
        private const val KEY_SCROLL = "album_scroll"

        fun intent(context: Context, albumKey: String?, albumName: String): Intent {
            return Intent(context, AlbumActivity::class.java).apply {
                putExtra(EXTRA_ALBUM_NAME, albumName)
                if (albumKey != null) putExtra(EXTRA_ALBUM_KEY, albumKey)
            }
        }
    }
}
