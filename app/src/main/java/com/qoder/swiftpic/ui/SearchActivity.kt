package com.qoder.swiftpic.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.GalleryFilters
import com.qoder.swiftpic.data.GalleryKeys
import com.qoder.swiftpic.data.GalleryLibrary
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.data.TransparencyBackdrop
import com.qoder.swiftpic.data.sortPhotos
import com.qoder.swiftpic.databinding.ActivitySearchBinding
import com.qoder.swiftpic.swiftPic
import com.qoder.swiftpic.ui.viewer.ViewerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SearchActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: PhotoAdapter
    private val app get() = swiftPic

    private var library: GalleryLibrary? = null
    private var visible: List<Photo> = emptyList()
    private var results: List<Photo> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { runSearch() }
    private var lastBackdrop: TransparencyBackdrop? = null
    // Snapshot of the query that produced the current `results`, NOT the live
    // input field (which may already contain a newer, un-debounced query).
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.settingsButton.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        binding.searchInput.requestFocus()

        adapter = PhotoAdapter(lifecycleScope, app.thumbnails) { photoIndex ->
            // A pending debounce would otherwise pair a NEW query with a position
            // from the OLD results. Flush it, refresh the results, and hand the
            // query snapshot that produced those results to the viewer.
            handler.removeCallbacks(searchRunnable)
            runSearch()
            ViewerActivity.start(
                context = this,
                albumKey = GalleryKeys.SEARCH,
                position = photoIndex,
                albumName = getString(R.string.search),
                query = currentQuery
            )
        }
        val columns = app.prefs.gridColumns.takeIf { it in 3..6 } ?: galleryColumns(this)
        binding.results.layoutManager = GridLayoutManager(this, columns)
        binding.results.adapter = adapter
        binding.results.itemAnimator = null
        binding.results.setHasFixedSize(true)
        binding.results.setItemViewCacheSize(32)

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                handler.removeCallbacks(searchRunnable)
                handler.postDelayed(searchRunnable, 260)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(searchRunnable)
                runSearch()
                true
            } else false
        }

        lifecycleScope.launch {
            val lib = try {
                app.gallery.load(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "gallery load failed", e)
                null
            }
            if (lib == null) {
                // Do not leave the user stuck in a permanent empty state.
                binding.empty.visibility = View.VISIBLE
                binding.empty.text = getString(R.string.scan_failed)
                return@launch
            }
            library = lib
            visible = lib.photos
            runSearch()
        }
    }

    override fun onResume() {
        super.onResume()
        val backdrop = app.prefs.transparencyBackdrop
        if (lastBackdrop != null && lastBackdrop != backdrop) {
            adapter.notifyDataSetChanged()
        }
        lastBackdrop = backdrop
    }

    override fun onDestroy() {
        handler.removeCallbacks(searchRunnable)
        super.onDestroy()
    }

    private fun runSearch() {
        val query = binding.searchInput.text.toString()
        currentQuery = query
        val matched = GalleryFilters.searchPhotos(visible, app.userData, query)
        results = sortPhotos(matched, app.prefs.sortMode)
        adapter.submit(results, false)
        val hasQuery = query.isNotBlank()
        binding.empty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        binding.empty.text = getString(
            if (hasQuery) R.string.search_empty else R.string.search_start
        )
    }

    private companion object {
        const val TAG = "SwiftPicSearch"
    }
}
