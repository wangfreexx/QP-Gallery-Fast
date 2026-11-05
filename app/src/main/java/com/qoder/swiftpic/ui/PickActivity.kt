package com.qoder.swiftpic.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.GalleryFilters
import com.qoder.swiftpic.data.GalleryLibrary
import com.qoder.swiftpic.data.MediaPermissions
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.databinding.ActivityPickBinding
import com.qoder.swiftpic.swiftPic
import kotlinx.coroutines.launch

/**
 * Picker entry for other apps (browser uploads, scanners, etc.). Returns MediaStore
 * content URIs with a read grant; the source photo never leaves MediaStore.
 */
class PickActivity : BaseActivity() {

    private lateinit var binding: ActivityPickBinding
    private lateinit var adapter: PhotoAdapter
    private val app get() = swiftPic

    private var library: GalleryLibrary? = null
    private var photos: List<Photo> = emptyList()
    private var albumKey: String? = null
    private var allowMultiple = false
    private var returning = false
    private var askedPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadLibrary()
        } else {
            AppHint.show(this, R.string.permission_denied)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPickBinding.inflate(layoutInflater)
        setContentView(binding.root)
        allowMultiple = intent.action != Intent.ACTION_PICK &&
            intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

        binding.pickToolbar.setNavigationOnClickListener { finish() }
        binding.pickToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_pick_album) {
                showAlbumDialog()
                true
            } else {
                false
            }
        }
        binding.pickBar.visibility = if (allowMultiple) View.VISIBLE else View.GONE
        binding.pickDone.setOnClickListener { returnSelected() }

        adapter = PhotoAdapter(lifecycleScope, app.thumbnails) { }
        adapter.onSelectionChanged = {
            updateSelectionBar()
            if (!allowMultiple && adapter.selectionCount() > 0) returnSelected()
        }
        adapter.setSelectionMode(true)

        val columns = app.prefs.gridColumns.takeIf { it in 3..6 } ?: galleryColumns(this)
        binding.pickPhotos.layoutManager = GridLayoutManager(this, columns)
        binding.pickPhotos.adapter = adapter
        binding.pickPhotos.itemAnimator = null
        binding.pickPhotos.setHasFixedSize(true)
        binding.pickPhotos.setItemViewCacheSize(48)

        loadLibrary()
    }

    private fun loadLibrary() {
        if (!MediaPermissions.canReadImages(this)) {
            if (!askedPermission) {
                askedPermission = true
                permissionLauncher.launch(MediaPermissions.requestPermissions().first())
            } else {
                AppHint.show(this, R.string.permission_denied)
                finish()
            }
            return
        }
        binding.pickLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val lib = runCatching { app.gallery.load(false) }.getOrNull()
            binding.pickLoading.visibility = View.GONE
            if (lib == null || !lib.hasImageAccess) {
                binding.pickEmpty.visibility = View.VISIBLE
                return@launch
            }
            library = lib
            applyAlbum()
        }
    }

    private fun applyAlbum() {
        val lib = library ?: return
        photos = GalleryFilters.photosFor(
            lib,
            app.userData,
            albumKey,
            null,
            app.prefs.sortMode
        )
        adapter.clearSelection()
        adapter.submit(photos, false)

        val name = if (albumKey == null) {
            getString(R.string.all_photos)
        } else {
            lib.albums.firstOrNull { it.key == albumKey }?.name ?: getString(R.string.albums)
        }
        binding.pickToolbar.subtitle = "$name · " + getString(R.string.photo_count, photos.size)
        binding.pickEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        binding.pickPhotos.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
        binding.pickPhotos.scrollToPosition(0)
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        if (!::adapter.isInitialized) return
        val count = adapter.selectionCount()
        binding.pickCount.text = getString(R.string.pick_selected_count, count)
        binding.pickDone.isEnabled = count > 0
    }

    private fun showAlbumDialog() {
        val lib = library ?: return
        val keys = ArrayList<String?>()
        val labels = ArrayList<String>()
        keys.add(null)
        labels.add(getString(R.string.all_photos))
        lib.albums.forEach { album ->
            keys.add(album.key)
            labels.add(
                album.name + " · " + getString(R.string.photo_count, album.photos.size)
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_choose_album)
            .setSingleChoiceItems(
                labels.toTypedArray(),
                keys.indexOf(albumKey)
            ) { dialog, which ->
                albumKey = keys[which]
                dialog.dismiss()
                applyAlbum()
            }
            .show()
    }

    private fun returnSelected() {
        if (returning) return
        val selected = photos.filter { adapter.isSelected(it) }
        if (selected.isEmpty()) return
        returning = true

        val uris = selected.map { it.uri }
        val mime = selected.first().mime.ifBlank { "image/*" }
        val result = Intent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uris.first(), mime)
        }
        if (uris.size > 1) {
            val clip = ClipData.newUri(
                contentResolver,
                getString(R.string.pick_title),
                uris.first()
            )
            for (index in 1 until uris.size) {
                clip.addItem(ClipData.Item(uris[index]))
            }
            result.clipData = clip
        }
        callingPackage?.let { target ->
            uris.forEach { uri ->
                runCatching {
                    grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
