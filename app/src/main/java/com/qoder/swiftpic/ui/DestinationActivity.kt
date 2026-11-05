package com.qoder.swiftpic.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.databinding.ActivityDestinationBinding
import com.qoder.swiftpic.swiftPic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

class DestinationActivity : BaseActivity() {

    private lateinit var binding: ActivityDestinationBinding
    private lateinit var adapter: DestinationAdapter

    private var move = false
    private var photoCount = 0
    private var defaultVolume = MediaStore.VOLUME_EXTERNAL_PRIMARY
    private var sourceVolume: String? = null
    private var sourcePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDestinationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        move = intent.getBooleanExtra(EXTRA_MOVE, false)
        photoCount = intent.getIntExtra(EXTRA_COUNT, 0)
        defaultVolume = intent.getStringExtra(EXTRA_DEFAULT_VOLUME)
            ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        sourceVolume = intent.getStringExtra(EXTRA_SOURCE_VOLUME)
        sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH)

        binding.toolbar.setTitle(if (move) R.string.move_to else R.string.copy_to)
        binding.toolbar.setSubtitle(R.string.choose_destination)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_new_album) {
                promptNewAlbum()
                true
            } else {
                false
            }
        }

        adapter = DestinationAdapter(lifecycleScope, swiftPic.thumbnails) { target ->
            confirmTarget(target)
        }
        binding.albums.layoutManager = GridLayoutManager(this, 2)
        binding.albums.adapter = adapter
        buildTargets()
    }

    private fun buildTargets() {
        val app = swiftPic
        val library = app.gallery.peek()
        if (library == null) {
            // Cold start: the gallery has not been scanned yet. Show a loading
            // state and load it instead of returning an unselectable empty page.
            binding.albums.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.emptyState.text = getString(R.string.loading)
            lifecycleScope.launch {
                val loaded = try {
                    app.gallery.load(false)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "gallery load failed", e)
                    false
                }
                if (isFinishing || isDestroyed) return@launch
                if (!loaded || app.gallery.peek() == null) {
                    binding.emptyState.text = getString(R.string.destination_empty)
                    return@launch
                }
                buildTargets()
            }
            return
        }
        binding.albums.visibility = View.VISIBLE
        val source = if (sourceVolume != null && sourcePath != null) {
            sourceVolume to sourcePath.orEmpty()
        } else {
            null
        }
        val pinnedOrder = app.userData.pinnedKeys()
        val pinnedRank = HashMap<String, Int>(pinnedOrder.size)
        pinnedOrder.forEachIndexed { index, key -> pinnedRank[key] = index }

        val targets = ArrayList<DestinationAdapter.Target>()
        val seen = HashSet<String>()
        library.albums.forEach { album ->
            val sample = album.photos.firstOrNull() ?: return@forEach
            val rel = sample.relativePath.trim('/')
            val volume = sample.volumeName
            if (source != null && source == (volume to rel)) return@forEach
            val key = "$volume|${rel.lowercase(Locale.ROOT)}"
            if (!seen.add(key)) return@forEach
            val coverKey = app.userData.albumCover(album.key)
            val cover = coverKey?.let { wanted ->
                album.photos.firstOrNull { it.stableKey == wanted }
            } ?: album.cover
            targets.add(
                DestinationAdapter.Target(
                    name = album.name,
                    relativePath = if (rel.isEmpty()) "" else "$rel/",
                    volume = volume,
                    count = album.count,
                    cover = cover,
                    key = album.key
                )
            )
        }
        val collator = Collator.getInstance(Locale.CHINA)
        targets.sortWith { a, b ->
            val rankA = pinnedRank[a.key] ?: Int.MAX_VALUE
            val rankB = pinnedRank[b.key] ?: Int.MAX_VALUE
            if (rankA != rankB) rankA.compareTo(rankB) else collator.compare(a.name, b.name)
        }
        adapter.submit(targets)
        binding.emptyState.visibility = if (targets.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyState.text = getString(R.string.destination_empty)
    }

    private fun confirmTarget(target: DestinationAdapter.Target) {
        MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(
                    if (move) R.string.move_confirm_title else R.string.copy_confirm_title,
                    target.name
                )
            )
            .setMessage(getString(R.string.transfer_confirm_count, photoCount))
            .setPositiveButton(R.string.confirm) { _, _ ->
                deliver(target.relativePath, target.volume)
            }
            .setNegativeButton(R.string.cancel, null)
            .showMotion()
    }

    private fun promptNewAlbum() {
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
                    deliver("Pictures/$name/", defaultVolume)
                }
        }
        dialog.show()
    }

    private fun isValidAlbumName(name: String): Boolean {
        if (name.isEmpty() || name.isBlank()) return false
        if (name != name.trim()) return false                       // leading/trailing whitespace
        if (name.startsWith(".")) return false                      // hidden names (. / ..)
        if (name.codePointCount(0, name.length) > 60) return false  // length by code points
        // Reject control characters (includes \n, \r) and filename-unsafe characters.
        return name.none { c ->
            c.code < 0x20 || c.code == 0x7F || c in "/\\:*?\"<>|"
        }
    }

    private fun deliver(relativePath: String, volume: String) {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_RELATIVE_PATH, relativePath)
                putExtra(EXTRA_VOLUME, volume)
            }
        )
        finish()
    }

    companion object {
        private const val TAG = "SwiftPicDestination"
        private const val EXTRA_MOVE = "move"
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_DEFAULT_VOLUME = "default_volume"
        private const val EXTRA_SOURCE_VOLUME = "source_volume"
        private const val EXTRA_SOURCE_PATH = "source_path"
        const val EXTRA_RELATIVE_PATH = "destination_relative_path"
        const val EXTRA_VOLUME = "destination_volume"

        fun intent(
            context: Context,
            move: Boolean,
            count: Int,
            sourceVolume: String?,
            sourcePath: String?,
            defaultVolume: String
        ): Intent {
            return Intent(context, DestinationActivity::class.java).apply {
                putExtra(EXTRA_MOVE, move)
                putExtra(EXTRA_COUNT, count)
                putExtra(EXTRA_DEFAULT_VOLUME, defaultVolume)
                if (sourceVolume != null && sourcePath != null) {
                    putExtra(EXTRA_SOURCE_VOLUME, sourceVolume)
                    putExtra(EXTRA_SOURCE_PATH, sourcePath)
                }
            }
        }
    }
}
