package com.qoder.swiftpic.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.Album
import com.qoder.swiftpic.data.AlbumSortMode
import com.qoder.swiftpic.data.GalleryFilters
import com.qoder.swiftpic.data.GalleryKeys
import com.qoder.swiftpic.data.GallerySnapshot
import com.qoder.swiftpic.data.GallerySnapshotStore
import com.qoder.swiftpic.data.HomeLayout
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.data.SortMode
import com.qoder.swiftpic.data.sortPhotos
import com.qoder.swiftpic.data.GalleryLibrary
import com.qoder.swiftpic.data.MediaPermissions
import com.qoder.swiftpic.databinding.ActivityMainBinding
import com.qoder.swiftpic.swiftPic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MainAdapter
    private val app get() = swiftPic
    private var loading = false
    private var pullRefresh = false
    private var loadedOnce = false
    private var lastAccess = false
    private var lastUserDataVersion = -1
    private var lastSnapshotScannedAt = -1L
    private var currentDisplayedAlbumKeys: List<String> = emptyList()
    private var restoreScroll = RecyclerView.NO_POSITION
    private var restoreScrollOffset = 0
    private var currentHomeLayout: HomeLayout = HomeLayout.GRID

    override val usesWallpaperBackground: Boolean = true

    private var sessionRestoreChecked = false

    private var pendingDeleteAlbumName = ""
    private val deleteAlbumLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val name = pendingDeleteAlbumName
        pendingDeleteAlbumName = ""
        if (result.resultCode == Activity.RESULT_OK) {
            app.gallery.markDirty()
            loadLibrary(force = true)
            AppHint.show(this, getString(R.string.delete_album_done, name))
        } else {
            AppHint.show(this, R.string.delete_album_canceled)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (MediaPermissions.canReadImages(this)) {
            loadLibrary(force = true)
            maybeRestoreLastAlbum(null)
        } else {
            showPermissionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeUtils.tintToolbarMenu(binding.toolbar)

        restoreScroll = savedInstanceState?.getInt(KEY_SCROLL, RecyclerView.NO_POSITION)
            ?: RecyclerView.NO_POSITION
        restoreScrollOffset = savedInstanceState?.getInt(KEY_SCROLL_OFFSET, 0) ?: 0
        pendingDeleteAlbumName = savedInstanceState?.getString(KEY_PENDING_DELETE_ALBUM).orEmpty()

        currentHomeLayout = app.prefs.homeLayout
        adapter = MainAdapter(
            lifecycleScope,
            app.thumbnails,
            onClick = { item ->
                when (item) {
                    is MainItem.Banner -> openAlbum(null, getString(R.string.all_photos))
                    is MainItem.AlbumRow -> openAlbum(item.album.key, item.album.name)
                    is MainItem.Section -> Unit
                }
            },
            onLongClick = { item -> showAlbumMenu(item) },
            stackedCovers = currentHomeLayout == HomeLayout.STACK,
            listMode = currentHomeLayout == HomeLayout.LIST
        )
        setupRecyclerView()
        binding.swipeRefresh.setColorSchemeColors(getColor(R.color.accent))
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.surface_high))
        binding.swipeRefresh.setOnRefreshListener {
            if (MediaPermissions.canReadImages(this)) {
                pullRefresh = true
                loadLibrary(force = true)
            } else {
                binding.swipeRefresh.isRefreshing = false
                requestOrSettings()
            }
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> {
                    startActivity(Intent(this, SearchActivity::class.java))
                    true
                }
                R.id.action_refresh -> {
                    if (MediaPermissions.canReadImages(this)) {
                        loadLibrary(force = true)
                    } else {
                        requestOrSettings()
                    }
                    true
                }
                R.id.action_home_sort -> {
                    showAlbumSortDialog(this, app.userData) { renderCached() }
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_about -> {
                    showAbout()
                    true
                }
                R.id.action_exit -> {
                    finishAffinity()
                    true
                }
                else -> false
            }
        }

        binding.permissionButton.setOnClickListener { requestOrSettings() }
        binding.settingsButton.setOnClickListener { openAppSettings() }

        // Paint the previous scan immediately (a few KB) while MediaStore is
        // re-scanned in the background, so a cold start never shows an empty grid.
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                GallerySnapshotStore(this@MainActivity).restore()
            }
            if (snapshot != null && !loadedOnce && !isFinishing && !isDestroyed) {
                renderPreview(snapshot)
            }
        }

        when {
            MediaPermissions.canReadImages(this) -> loadLibrary(force = false)
            app.prefs.askedPermission -> showPermissionState()
            else -> {
                app.prefs.askedPermission = true
                permissionLauncher.launch(MediaPermissions.requestPermissions())
            }
        }
        maybeRestoreLastAlbum(savedInstanceState)
    }

    private fun maybeRestoreLastAlbum(savedInstanceState: Bundle?) {
        if (sessionRestoreChecked) return
        if (savedInstanceState != null) {
            sessionRestoreChecked = true
            return
        }
        val at = app.prefs.lastAlbumAt
        if (at <= 0L) {
            sessionRestoreChecked = true
            return
        }
        if (!MediaPermissions.canReadImages(this)) return
        sessionRestoreChecked = true
        if (System.currentTimeMillis() - at > SESSION_RESTORE_WINDOW_MS) {
            app.prefs.clearLastAlbum()
            return
        }
        val key = app.prefs.lastAlbumKey.orEmpty().ifEmpty { null }
        val name = app.prefs.lastAlbumName.ifBlank { getString(R.string.all_photos) }
        app.prefs.clearLastAlbum()
        openAlbum(key, name)
    }

    override fun onResume() {
        super.onResume()
        if (app.prefs.homeLayout != currentHomeLayout) {
            recreate()
            return
        }
        val hasAccess = MediaPermissions.canReadImages(this)
        if (!hasAccess) {
            lastAccess = false
            if (loadedOnce || app.prefs.askedPermission) showPermissionState()
            return
        }
        if (!loading) {
            val cached = app.gallery.peek()
            if (loadedOnce && cached != null && app.userData.version != lastUserDataVersion) {
                render(cached)
            } else if (!loadedOnce || !lastAccess || app.gallery.isDirty()) {
                loadLibrary(force = false)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val layout = binding.albums.layoutManager as? GridLayoutManager
        val position = layout?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val offset = layout?.findViewByPosition(position)?.top ?: 0
        outState.putInt(KEY_SCROLL, position)
        outState.putInt(KEY_SCROLL_OFFSET, offset)
        outState.putString(KEY_PENDING_DELETE_ALBUM, pendingDeleteAlbumName)
    }

    private fun setupRecyclerView() {
        val columns = albumColumns(this)
        val layoutManager = GridLayoutManager(this, columns)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (position < 0 || position >= adapter.itemCount) return 1
                return when (adapter.itemAt(position)) {
                    is MainItem.Banner, is MainItem.Section -> columns
                    is MainItem.AlbumRow -> if (currentHomeLayout == HomeLayout.LIST) columns else 1
                }
            }
        }
        binding.albums.layoutManager = layoutManager
        binding.albums.adapter = adapter
        binding.albums.itemAnimator = null
        binding.albums.setHasFixedSize(true)
        binding.albums.setItemViewCacheSize(32)
        binding.fastScroller.attachTo(binding.albums)
    }

    private fun loadLibrary(force: Boolean) {
        if (loading) {
            binding.swipeRefresh.isRefreshing = false
            return
        }
        loading = true
        val cached = app.gallery.peek()
        if (cached == null) {
            binding.loading.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
            binding.albums.visibility = View.GONE
        } else if (force && !pullRefresh) {
            binding.refreshProgress.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val library = app.gallery.load(force)
                loadedOnce = true
                app.userData.migrateLegacyKeys(library.photos)
                render(library)
            } catch (e: Exception) {
                Log.w(TAG, "library load failed", e)
                showEmpty(
                    getString(R.string.scan_failed),
                    e.message ?: getString(R.string.image_load_failed)
                )
            } finally {
                loading = false
                pullRefresh = false
                binding.swipeRefresh.isRefreshing = false
                binding.loading.visibility = View.GONE
                binding.refreshProgress.visibility = View.GONE
            }
        }
    }

    /** Lightweight first paint from the previous scan; replaced by [render] once the scan finishes. */
    private fun renderPreview(snapshot: GallerySnapshot) {
        val items = ArrayList<MainItem>(snapshot.albums.size + 3)
        items.add(MainItem.Banner(snapshot.cover, snapshot.photoCount))
        if (snapshot.favoritesCount > 0) {
            val cover = snapshot.favoritesCover
            items.add(
                MainItem.AlbumRow(
                    Album(
                        GalleryKeys.FAVORITES,
                        getString(R.string.favorites_album),
                        listOfNotNull(cover),
                        snapshot.favoritesCount
                    ),
                    cover,
                    app.userData.isPinned(GalleryKeys.FAVORITES)
                )
            )
        }
        snapshot.albums.forEach { digest ->
            val cover = digest.cover
            items.add(
                MainItem.AlbumRow(
                    Album(digest.key, digest.name, listOfNotNull(cover), digest.count),
                    cover,
                    app.userData.isPinned(digest.key)
                )
            )
        }
        adapter.submit(items)
        currentDisplayedAlbumKeys = snapshot.albums.map { it.key }
        Log.d(TAG, "render preview albums=${snapshot.albums.size} photos=${snapshot.photoCount}")
        binding.emptyState.visibility = View.GONE
        binding.albums.visibility = View.VISIBLE
        binding.loading.visibility = View.GONE
        binding.toolbar.subtitle = getString(R.string.refreshing_snapshot)
    }

    private fun render(library: GalleryLibrary) {
        if (!library.hasImageAccess) {
            showPermissionState()
            return
        }
        lastAccess = true
        val visible = library.photos
        val visibleAlbums = orderAlbums(GalleryFilters.buildVisibleAlbums(visible))
        val favorites = sortPhotos(
            GalleryFilters.favoritePhotos(visible, app.userData),
            SortMode.DATE_DESC
        )
        binding.toolbar.subtitle = if (MediaPermissions.isPartialAccess(this)) {
            getString(R.string.partial_permission_message)
        } else {
            null
        }
        if (library.scanError) {
            showEmpty(
                getString(R.string.scan_failed),
                getString(R.string.image_decode_failed)
            )
            return
        }

        if (visible.isEmpty()) {
            adapter.submit(emptyList())
            currentDisplayedAlbumKeys = emptyList()
            lastUserDataVersion = app.userData.version
            if (MediaPermissions.isPartialAccess(this)) {
                showEmpty(
                    getString(R.string.empty_title),
                    getString(R.string.partial_permission_message),
                    showPermissionAction = true
                )
            } else {
                showEmpty(getString(R.string.empty_title), getString(R.string.empty_message))
            }
            return
        }

        binding.emptyState.visibility = View.GONE
        binding.albums.visibility = View.VISIBLE

        val items = ArrayList<MainItem>(visibleAlbums.size + 4)
        items.add(MainItem.Banner(visible.firstOrNull(), visible.size))
        if (favorites.isNotEmpty()) {
            val favoritesAlbum = Album(
                GalleryKeys.FAVORITES,
                getString(R.string.favorites_album),
                favorites
            )
            items.add(MainItem.AlbumRow(favoritesAlbum, favorites.firstOrNull()))
        }
        val photoByKey = HashMap<String, com.qoder.swiftpic.data.Photo>(visible.size)
        visible.forEach { photoByKey[it.stableKey] = it }
        visibleAlbums.forEach { album ->
            val customCover = app.userData.albumCover(album.key)?.let { photoByKey[it] }
            items.add(
                MainItem.AlbumRow(
                    album = album,
                    cover = customCover ?: album.cover,
                    pinned = app.userData.isPinned(album.key)
                )
            )
        }
        adapter.submit(items)
        currentDisplayedAlbumKeys = visibleAlbums.map { it.key }
        lastUserDataVersion = app.userData.version

        // Persist only once per actual scan (render also runs for sorting/pinning).
        if (library.scannedAt != lastSnapshotScannedAt) {
            lastSnapshotScannedAt = library.scannedAt
            val favoritesSnapshot = favorites
            lifecycleScope.launch(Dispatchers.IO) {
                GallerySnapshotStore(this@MainActivity).save(library, favoritesSnapshot)
            }
        }

        if (restoreScroll != RecyclerView.NO_POSITION) {
            val target = restoreScroll.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
            val offset = restoreScrollOffset
            binding.albums.post {
                (binding.albums.layoutManager as? GridLayoutManager)
                    ?.scrollToPositionWithOffset(target, offset)
            }
            restoreScroll = RecyclerView.NO_POSITION
            restoreScrollOffset = 0
        }
    }

    private fun orderAlbums(albums: List<Album>): List<Album> {
        val collator = Collator.getInstance(Locale.CHINA)
        val sorted = when (app.userData.albumSort) {
            AlbumSortMode.LATEST -> albums
            AlbumSortMode.OLDEST -> albums.reversed()
            AlbumSortMode.NAME -> albums.sortedWith { a, b -> collator.compare(a.name, b.name) }
            AlbumSortMode.NAME_DESC -> albums.sortedWith { a, b -> collator.compare(b.name, a.name) }
            AlbumSortMode.MANUAL -> {
                val byKey = albums.associateBy { it.key }
                val ordered = ArrayList<Album>(albums.size)
                val seen = HashSet<String>(albums.size)
                fun addIfNew(album: Album?) {
                    if (album != null && seen.add(album.key)) ordered.add(album)
                }
                app.userData.albumOrder().forEach { key -> addIfNew(byKey[key]) }
                albums.forEach { album -> addIfNew(album) }
                ordered
            }
        }
        val sortedByKey = sorted.associateBy { it.key }
        val pinnedOrder = app.userData.pinnedKeys()
        val pinned = ArrayList<Album>(pinnedOrder.size)
        val pinnedKeys = HashSet<String>(pinnedOrder.size)
        pinnedOrder.forEach { key ->
            sortedByKey[key]?.let { album ->
                if (pinnedKeys.add(key)) pinned.add(album)
            }
        }
        sorted.forEach { album ->
            if (app.userData.isPinned(album.key) && pinnedKeys.add(album.key)) {
                pinned.add(album)
            }
        }
        val normal = sorted.filterNot { pinnedKeys.contains(it.key) }
        return pinned + normal
    }

    private fun showAlbumMenu(item: MainItem.AlbumRow) {
        val album = item.album
        if (album.key == GalleryKeys.FAVORITES) {
            MediaDetails.showAlbum(this, album)
            return
        }
        val pinned = app.userData.isPinned(album.key)
        val manual = app.userData.albumSort == AlbumSortMode.MANUAL
        val actions = ArrayList<Pair<String, () -> Unit>>(4)
        actions.add(
            getString(if (pinned) R.string.unpin_top else R.string.pin_top) to {
                app.userData.setPinned(album.key, !pinned)
                renderCached()
            }
        )
        if (manual) {
            actions.add(getString(R.string.move_up) to { moveAlbum(album.key, -1) })
            actions.add(getString(R.string.move_down) to { moveAlbum(album.key, 1) })
        }
        actions.add(getString(R.string.delete_album) to { confirmDeleteAlbum(album) })
        actions.add(getString(R.string.exclude_album) to { excludeAlbum(album) })
        actions.add(getString(R.string.tags) to { editAlbumTags(album) })
        actions.add(
            getString(R.string.album_sort) to {
                showAlbumSortDialog(this, app.userData) { renderCached() }
            }
        )
        actions.add(getString(R.string.view_details) to {
            MediaDetails.showAlbum(this, album)
        })
        MaterialAlertDialogBuilder(this)
            .setTitle(album.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .showMotion()
    }

    private fun editAlbumTags(album: Album) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.tag_hint)
            setText(app.userData.albumTags(album.key).joinToString(", "))
            setSingleLine(true)
            setPadding(pad, pad / 2, pad, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.album_tags_title, album.name))
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val tags = input.text.toString()
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                app.userData.setAlbumTags(album.key, tags)
                AppHint.show(this, R.string.tag_saved)
                renderCached()
            }
            .setNegativeButton(R.string.cancel, null)
            .showMotion()
    }

    private fun excludeAlbum(album: Album) {
        val path = album.photos.firstOrNull()?.relativePath?.trim('/').orEmpty()
        if (path.isEmpty()) {
            AppHint.show(this, R.string.exclude_album_root)
            return
        }
        app.prefs.excludePaths = app.prefs.excludePaths + path
        app.gallery.markDirty()
        AppHint.show(this, getString(R.string.exclude_album_done, album.name, path))
        loadLibrary(force = true)
    }

    private fun confirmDeleteAlbum(album: Album) {
        if (album.photos.isEmpty()) {
            AppHint.show(this, R.string.delete_album_empty)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_album_title, album.name, album.photos.size))
            .setMessage(
                if (Build.VERSION.SDK_INT >= 30) R.string.delete_album_message
                else R.string.delete_album_message_legacy
            )
            .setPositiveButton(R.string.delete) { _, _ -> deleteAlbum(album) }
            .setNegativeButton(R.string.cancel, null)
            .showMotion()
    }

    private fun albumDeletionUris(album: Album): ArrayList<Uri> {
        val uris = ArrayList<Uri>(album.photos.size * 2)
        album.photos.forEach { photo ->
            uris.add(photo.uri)
            photo.liveVideoUri?.let { uris.add(it) }
        }
        return uris
    }

    private fun deleteAlbum(album: Album) {
        val uris = albumDeletionUris(album)
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            pendingDeleteAlbumName = album.name
            try {
                val request = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteAlbumLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (e: Exception) {
                Log.w(TAG, "createDeleteRequest failed", e)
                pendingDeleteAlbumName = ""
                AppHint.show(this, R.string.delete_failed)
            }
            return
        }
        // Android 10 and below have no batch consent dialog; delete directly with storage rights.
        AppHint.show(this, R.string.processing)
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                var count = 0
                uris.forEach { uri ->
                    val ok = try {
                        contentResolver.delete(uri, null, null) > 0
                    } catch (e: Exception) {
                        Log.w(TAG, "delete failed for $uri", e)
                        false
                    }
                    if (ok) count++
                }
                count
            }
            app.gallery.markDirty()
            loadLibrary(force = true)
            AppHint.show(this@MainActivity, if (deleted > 0) getString(R.string.delete_album_done_count, album.name, deleted) else getString(R.string.delete_album_done, album.name))
        }
    }

    private fun moveAlbum(key: String, delta: Int) {
        val targetPinned = app.userData.isPinned(key)
        val group = currentDisplayedAlbumKeys
            .filter { app.userData.isPinned(it) == targetPinned }
            .toMutableList()
        val current = group.indexOf(key)
        if (current < 0) return
        group.removeAt(current)
        val target = (current + delta).coerceIn(0, group.size)
        group.add(target, key)
        val pinnedOrder = currentDisplayedAlbumKeys.filter { app.userData.isPinned(it) }
        val normalOrder = currentDisplayedAlbumKeys.filterNot { app.userData.isPinned(it) }
        val order = if (targetPinned) group + normalOrder else pinnedOrder + group
        app.userData.setAlbumOrder(order)
        if (targetPinned) app.userData.setPinnedOrder(group)
        renderCached()
    }

    private fun renderCached() {
        val cached = app.gallery.peek() ?: return
        render(cached)
    }

    private fun showPermissionState() {
        lastAccess = false
        val partial = MediaPermissions.isPartialAccess(this)
        showEmpty(
            getString(R.string.permission_title),
            if (partial) getString(R.string.partial_permission_message)
            else getString(R.string.permission_message),
            showPermissionAction = true
        )
    }

    private fun showEmpty(
        title: String,
        message: String,
        showPermissionAction: Boolean = false
    ) {
        binding.albums.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyTitle.text = title
        binding.emptyMessage.text = message
        binding.permissionButton.visibility = if (showPermissionAction) View.VISIBLE else View.GONE
        binding.settingsButton.visibility =
            if (showPermissionAction && app.prefs.askedPermission) View.VISIBLE else View.GONE
        binding.loading.visibility = View.GONE
        binding.toolbar.subtitle = null
    }

    private fun requestOrSettings() {
        // Limited access still returns canReadImages() == true, so check the
        // partial state separately and let the user re-open the photo picker.
        if (MediaPermissions.isPartialAccess(this)) {
            permissionLauncher.launch(MediaPermissions.requestPermissions())
            return
        }
        if (!MediaPermissions.canReadImages(this) &&
            app.prefs.askedPermission &&
            MediaPermissions.shouldOpenSettings(this, true)
        ) {
            openAppSettings()
        } else {
            app.prefs.askedPermission = true
            permissionLauncher.launch(MediaPermissions.requestPermissions())
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "open settings failed", e)
        }
    }

    private fun openAlbum(key: String?, name: String) {
        startActivity(AlbumActivity.intent(this, key, name))
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            Log.w(TAG, "package info unavailable", e)
            null
        } ?: "1.0.0"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about)
            .setMessage(getString(R.string.about_message, version))
            .setPositiveButton(R.string.close, null)
            .showMotion()
    }

    private companion object {
        const val TAG = "SwiftPicMain"
        const val KEY_SCROLL = "main_scroll"
        const val KEY_SCROLL_OFFSET = "main_scroll_offset"
        const val KEY_PENDING_DELETE_ALBUM = "main_pending_delete_album"
        const val SESSION_RESTORE_WINDOW_MS = 12 * 60 * 60 * 1000L
    }
}
