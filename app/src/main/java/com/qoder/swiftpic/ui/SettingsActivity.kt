package com.qoder.swiftpic.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.AlbumSortMode
import com.qoder.swiftpic.data.BackgroundMode
import com.qoder.swiftpic.data.HomeLayout
import com.qoder.swiftpic.data.OrientationMode
import com.qoder.swiftpic.data.ThemeMode
import com.qoder.swiftpic.data.TransitionMode
import com.qoder.swiftpic.data.WallpaperBackground
import com.qoder.swiftpic.util.Formats
import com.qoder.swiftpic.databinding.ActivitySettingsBinding
import com.qoder.swiftpic.databinding.ItemSettingChoiceBinding
import com.qoder.swiftpic.databinding.ItemSettingHeaderBinding
import com.qoder.swiftpic.databinding.ItemSettingSwitchBinding
import com.qoder.swiftpic.swiftPic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val app get() = swiftPic
    private val prefs get() = app.prefs

    private var pendingInclude = true
    private var cacheValue = ""
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) addFilterPath(uri, pendingInclude)
    }

    private val backgroundPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            buildRows()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                WallpaperBackground.setCustomImage(this@SettingsActivity, uri)
            }
            if (stored) {
                prefs.backgroundMode = BackgroundMode.CUSTOM
                Toast.makeText(this@SettingsActivity, R.string.background_pick_done, Toast.LENGTH_SHORT)
                    .show()
                recreate()
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.background_pick_failed,
                    Toast.LENGTH_SHORT
                ).show()
                buildRows()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        ThemeUtils.tintToolbarMenu(binding.toolbar)
        buildRows()
        refreshCacheSize()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) buildRows()
    }

    private fun buildRows() {
        binding.settingsContent.removeAllViews()
        addHeader(getString(R.string.settings_appearance))
        addChoice(
            title = getString(R.string.theme),
            summary = null,
            value = themeLabel(prefs.themeMode)
        ) { showThemeDialog() }
        addChoice(
            title = getString(R.string.background_title),
            summary = null,
            value = backgroundLabel(prefs.backgroundMode)
        ) { showBackgroundDialog() }
        addChoice(
            title = getString(R.string.background_blur),
            summary = getString(R.string.background_blur_summary),
            value = getString(R.string.background_blur_value, prefs.backgroundBlur)
        ) { showBackgroundBlurDialog() }
        addChoice(
            title = getString(R.string.grid_columns),
            summary = getString(R.string.grid_columns_summary),
            value = gridLabel(prefs.gridColumns)
        ) { showGridDialog() }
        addChoice(
            title = getString(R.string.home_layout),
            summary = getString(R.string.home_layout_summary),
            value = getString(
                when (prefs.homeLayout) {
                    HomeLayout.GRID -> R.string.home_layout_grid
                    HomeLayout.STACK -> R.string.home_layout_stack
                    HomeLayout.LIST -> R.string.home_layout_list
                }
            )
        ) { showHomeLayoutDialog() }
        addChoice(
            title = getString(R.string.animation_speed),
            summary = getString(R.string.animation_speed_summary),
            value = String.format(java.util.Locale.US, "%.1fx", prefs.animationScale)
        ) { showAnimationSpeedDialog() }
        addChoice(
            title = getString(R.string.transition),
            summary = null,
            value = transitionLabel(prefs.transitionMode)
        ) { showTransitionDialog() }
        addChoice(
            title = getString(R.string.transition_duration),
            summary = getString(R.string.transition_duration_summary),
            value = getString(R.string.transition_duration_value, prefs.transitionDurationMs)
        ) { showTransitionDurationDialog() }
        addSwitch(
            title = getString(R.string.tap_paging),
            summary = getString(R.string.tap_paging_summary),
            checked = prefs.tapPaging
        ) { prefs.tapPaging = it }

        addHeader(getString(R.string.settings_browsing))
        addChoice(
            title = getString(R.string.include_paths),
            summary = getString(R.string.paths_hint),
            value = pathsLabel(prefs.includePaths)
        ) { editPaths(include = true) }
        addChoice(
            title = getString(R.string.exclude_paths),
            summary = null,
            value = pathsLabel(prefs.excludePaths)
        ) { editPaths(include = false) }
        addChoice(
            title = getString(R.string.album_sort),
            summary = null,
            value = albumSortLabel(this, app.userData.albumSort)
        ) { showAlbumSortDialog(this, app.userData) { buildRows() } }
        addChoice(
            title = getString(R.string.photo_sort),
            summary = getString(R.string.photo_sort_summary),
            value = photoSortLabel(this, prefs.sortMode)
        ) { showPhotoSortDialog(this, prefs) { buildRows() } }

        addHeader(getString(R.string.settings_viewing))
        addSwitch(
            title = getString(R.string.keep_screen_on),
            summary = getString(R.string.keep_screen_on_summary),
            checked = prefs.keepScreenOn
        ) { prefs.keepScreenOn = it }
        addChoice(
            title = getString(R.string.chrome_auto_hide),
            summary = getString(R.string.chrome_auto_hide_summary),
            value = autoHideLabel(prefs.chromeAutoHideMs)
        ) { showAutoHideDialog() }
        addChoice(
            title = getString(R.string.orientation),
            summary = null,
            value = orientationLabel(prefs.orientationMode)
        ) { showOrientationDialog() }
        addSwitch(
            title = getString(R.string.hdr_display),
            summary = getString(R.string.hdr_display_summary),
            checked = prefs.hdrEnabled
        ) { prefs.hdrEnabled = it }
        addSwitch(
            title = getString(R.string.live_autoplay),
            summary = getString(R.string.live_autoplay_summary),
            checked = prefs.liveAutoPlay
        ) { prefs.liveAutoPlay = it }
        addSwitch(
            title = getString(R.string.live_default_mute),
            summary = getString(R.string.live_default_mute_summary),
            checked = prefs.liveMuted
        ) { prefs.liveMuted = it }

        addHeader(getString(R.string.settings_storage))
        addChoice(
            title = getString(R.string.cache_clear),
            summary = getString(R.string.cache_clear_summary),
            value = cacheValue
        ) { clearCache() }

        addHeader(getString(R.string.settings_other))
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        addChoice(
            title = getString(R.string.about),
            summary = getString(R.string.version_label, version),
            value = ""
        ) { showAbout(version) }
    }

    private fun addHeader(title: String) {
        val row = ItemSettingHeaderBinding.inflate(LayoutInflater.from(this), binding.settingsContent, false)
        row.settingHeader.text = title
        binding.settingsContent.addView(row.root)
    }

    private fun addChoice(
        title: String,
        summary: String?,
        value: String,
        onClick: () -> Unit
    ) {
        val row = ItemSettingChoiceBinding.inflate(LayoutInflater.from(this), binding.settingsContent, false)
        row.settingTitle.text = title
        row.settingSummary.text = summary.orEmpty()
        row.settingSummary.visibility = if (summary.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        row.settingValue.text = value
        row.settingRow.setOnClickListener { onClick() }
        binding.settingsContent.addView(row.root)
    }

    private fun addSwitch(
        title: String,
        summary: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = ItemSettingSwitchBinding.inflate(LayoutInflater.from(this), binding.settingsContent, false)
        row.settingTitle.text = title
        row.settingSummary.text = summary.orEmpty()
        row.settingSummary.visibility = if (summary.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        row.settingSwitch.isChecked = checked
        row.settingRow.setOnClickListener {
            val next = !row.settingSwitch.isChecked
            row.settingSwitch.isChecked = next
            onChange(next)
            buildRows()
        }
        binding.settingsContent.addView(row.root)
    }

    private fun showThemeDialog() {
        val modes = arrayOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.AMOLED)
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_dark),
            getString(R.string.theme_light),
            getString(R.string.theme_amoled)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, modes.indexOf(prefs.themeMode)) { dialog, which ->
                prefs.themeMode = modes[which]
                dialog.dismiss()
                recreate()
            }
            .showMotion()
    }

    private fun showBackgroundDialog() {
        val modes = arrayOf(BackgroundMode.WALLPAPER, BackgroundMode.CUSTOM, BackgroundMode.THEME)
        val labels = arrayOf(
            getString(R.string.background_wallpaper),
            getString(R.string.background_custom),
            getString(R.string.background_theme)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.background_title)
            .setSingleChoiceItems(labels, modes.indexOf(prefs.backgroundMode)) { dialog, which ->
                val mode = modes[which]
                dialog.dismiss()
                if (mode == BackgroundMode.CUSTOM) {
                    pickBackgroundImage()
                } else {
                    prefs.backgroundMode = mode
                    recreate()
                }
            }
            .showMotion()
    }

    private fun pickBackgroundImage() {
        runCatching { backgroundPicker.launch(arrayOf("image/*")) }.onFailure {
            Toast.makeText(this, R.string.path_picker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun backgroundLabel(mode: BackgroundMode): String = getString(
        when (mode) {
            BackgroundMode.WALLPAPER -> R.string.background_wallpaper
            BackgroundMode.CUSTOM -> R.string.background_custom
            BackgroundMode.THEME -> R.string.background_theme
        }
    )

    private fun showBackgroundBlurDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val label = TextView(this).apply {
            text = getString(R.string.background_blur_value, prefs.backgroundBlur)
            textSize = 16f
        }
        val seek = SeekBar(this).apply {
            max = 40
            progress = prefs.backgroundBlur.coerceIn(0, 40)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = getString(R.string.background_blur_value, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        container.addView(label)
        container.addView(seek)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.background_blur)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                prefs.backgroundBlur = seek.progress
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .showMotion()
    }

    private fun showHomeLayoutDialog() {
        val modes = arrayOf(HomeLayout.GRID, HomeLayout.STACK, HomeLayout.LIST)
        val labels = arrayOf(
            getString(R.string.home_layout_grid),
            getString(R.string.home_layout_stack),
            getString(R.string.home_layout_list)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_layout)
            .setSingleChoiceItems(labels, modes.indexOf(prefs.homeLayout)) { dialog, which ->
                prefs.homeLayout = modes[which]
                dialog.dismiss()
                buildRows()
            }
            .showMotion()
    }

    private fun showAnimationSpeedDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val label = TextView(this).apply {
            text = String.format(java.util.Locale.US, "%.1fx", prefs.animationScale)
            textSize = 16f
        }
        val seek = SeekBar(this).apply {
            max = 39
            progress = ((prefs.animationScale * 10).roundToInt() - 1).coerceIn(0, 39)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = String.format(java.util.Locale.US, "%.1fx", (progress + 1) / 10f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        container.addView(label)
        container.addView(seek)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.animation_speed)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                prefs.animationScale = (seek.progress + 1) / 10f
                buildRows()
            }
            .setNegativeButton(R.string.cancel, null)
            .showMotion()
    }

    private fun showGridDialog() {
        val values = intArrayOf(0, 4, 5, 6)
        val labels = arrayOf(
            getString(R.string.grid_columns_auto),
            getString(R.string.grid_columns_n, 4),
            getString(R.string.grid_columns_n, 5),
            getString(R.string.grid_columns_n, 6)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.grid_columns)
            .setSingleChoiceItems(labels, values.indexOf(prefs.gridColumns)) { dialog, which ->
                prefs.gridColumns = values[which]
                dialog.dismiss()
                buildRows()
            }
            .showMotion()
    }

    private fun autoHideLabel(ms: Int): String =
        if (ms <= 0) getString(R.string.auto_hide_never)
        else getString(R.string.auto_hide_seconds, ms / 1000)

    private fun showAutoHideDialog() {
        val values = intArrayOf(2000, 3000, 5000, 10000, 0)
        val labels = arrayOf(
            getString(R.string.auto_hide_seconds, 2),
            getString(R.string.auto_hide_seconds, 3),
            getString(R.string.auto_hide_seconds, 5),
            getString(R.string.auto_hide_seconds, 10),
            getString(R.string.auto_hide_never)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chrome_auto_hide)
            .setSingleChoiceItems(labels, values.indexOf(prefs.chromeAutoHideMs)) { dialog, which ->
                prefs.chromeAutoHideMs = values[which]
                dialog.dismiss()
                buildRows()
            }
            .showMotion()
    }

    private fun showTransitionDialog() {
        val modes = TransitionMode.values()
        val labels = arrayOf(
            getString(R.string.transition_none),
            getString(R.string.transition_slide),
            getString(R.string.transition_fade)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.transition)
            .setSingleChoiceItems(labels, modes.indexOf(prefs.transitionMode)) { dialog, which ->
                prefs.transitionMode = modes[which]
                dialog.dismiss()
                buildRows()
            }
            .showMotion()
    }

    private fun showTransitionDurationDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val label = TextView(this).apply {
            text = getString(R.string.transition_duration_value, prefs.transitionDurationMs)
            textSize = 16f
        }
        val seek = SeekBar(this).apply {
            max = 29
            progress = ((prefs.transitionDurationMs - 50) / 50).coerceIn(0, 29)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = getString(
                    R.string.transition_duration_value,
                    50 + progress * 50
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        container.addView(label)
        container.addView(seek)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.transition_duration)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                prefs.transitionDurationMs = 50 + seek.progress * 50
                buildRows()
            }
            .setNegativeButton(R.string.cancel, null)
            .showMotion()
    }

    private fun showOrientationDialog() {
        val modes = OrientationMode.values()
        val labels = arrayOf(
            getString(R.string.orientation_system),
            getString(R.string.orientation_portrait),
            getString(R.string.orientation_landscape)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.orientation)
            .setSingleChoiceItems(labels, modes.indexOf(prefs.orientationMode)) { dialog, which ->
                prefs.orientationMode = modes[which]
                dialog.dismiss()
                buildRows()
            }
            .showMotion()
    }

    private fun editPaths(include: Boolean) {
        val current = (if (include) prefs.includePaths else prefs.excludePaths).sorted()
        val labels = ArrayList<String>(current.size + 2)
        labels.add(getString(R.string.path_add_folder))
        current.forEach { labels.add(getString(R.string.path_remove, it)) }
        if (current.isNotEmpty()) labels.add(getString(R.string.path_clear))
        MaterialAlertDialogBuilder(this)
            .setTitle(if (include) R.string.paths_dialog_include else R.string.paths_dialog_exclude)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        pendingInclude = include
                        runCatching { folderPicker.launch(null) }.onFailure {
                            Toast.makeText(
                                this,
                                R.string.path_picker_unavailable,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    which <= current.size -> {
                        val set = (if (include) prefs.includePaths else prefs.excludePaths)
                            .toMutableSet()
                        set.remove(current[which - 1])
                        if (include) prefs.includePaths = set else prefs.excludePaths = set
                        app.gallery.markDirty()
                        buildRows()
                    }
                    else -> {
                        if (include) prefs.includePaths = emptySet()
                        else prefs.excludePaths = emptySet()
                        app.gallery.markDirty()
                        buildRows()
                    }
                }
            }
            .showMotion()
    }

    private fun addFilterPath(uri: Uri, include: Boolean) {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val path = docId?.substringAfter(':', "")?.trim('/')?.takeIf { it.isNotEmpty() }
        if (path == null) {
            Toast.makeText(this, R.string.path_pick_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val set = (if (include) prefs.includePaths else prefs.excludePaths).toMutableSet()
        set.add(path)
        if (include) prefs.includePaths = set else prefs.excludePaths = set
        app.gallery.markDirty()
        buildRows()
        Toast.makeText(this, getString(R.string.path_added, path), Toast.LENGTH_SHORT).show()
    }

    private fun clearCache() {
        app.thumbnails.evictAll()
        app.images.evictAll()
        lifecycleScope.launch(Dispatchers.IO) {
            app.motion.clearDiskCache()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SettingsActivity, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                refreshCacheSize()
            }
        }
    }

    private fun refreshCacheSize() {
        lifecycleScope.launch {
            val value = withContext(Dispatchers.IO) {
                val memory = app.thumbnails.memoryCacheBytes().toLong() +
                    app.images.memoryCacheBytes().toLong()
                val disk = app.motion.diskCacheBytes()
                getString(
                    R.string.cache_usage_value,
                    Formats.cacheSize(this@SettingsActivity, memory),
                    Formats.cacheSize(this@SettingsActivity, disk)
                )
            }
            cacheValue = value
            if (!isFinishing && ::binding.isInitialized) buildRows()
        }
    }

    private fun showAbout(version: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about)
            .setMessage(getString(R.string.about_message, version))
            .setPositiveButton(R.string.close, null)
            .showMotion()
    }

    private fun themeLabel(mode: ThemeMode): String = getString(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.theme_system
            ThemeMode.DARK -> R.string.theme_dark
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.AMOLED -> R.string.theme_amoled
        }
    )

    private fun gridLabel(value: Int): String =
        if (value in 3..6) getString(R.string.grid_columns_n, value)
        else getString(R.string.grid_columns_auto)

    private fun transitionLabel(mode: TransitionMode): String = getString(
        when (mode) {
            TransitionMode.NONE -> R.string.transition_none
            TransitionMode.SLIDE -> R.string.transition_slide
            TransitionMode.FADE -> R.string.transition_fade
        }
    )

    private fun orientationLabel(mode: OrientationMode): String = getString(
        when (mode) {
            OrientationMode.SYSTEM -> R.string.orientation_system
            OrientationMode.PORTRAIT -> R.string.orientation_portrait
            OrientationMode.LANDSCAPE -> R.string.orientation_landscape
        }
    )

    private fun pathsLabel(paths: Set<String>): String =
        if (paths.isEmpty()) getString(R.string.paths_value_all)
        else getString(R.string.paths_value_n, paths.size)
}
