package com.qoder.swiftpic.ui.edit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.ImageExporter
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.databinding.ActivityEditBinding
import com.qoder.swiftpic.swiftPic
import com.qoder.swiftpic.ui.BaseActivity
import com.qoder.swiftpic.ui.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class EditActivity : BaseActivity() {

    private lateinit var binding: ActivityEditBinding
    private val app get() = swiftPic

    override val usesWallpaperBackground: Boolean = false

    private lateinit var photoUri: Uri
    private var photoName: String = "SwiftPic"
    private var dateTakenMs: Long = 0L

    private var original: Bitmap? = null
    private var working: Bitmap? = null
    private var aspect: Float? = null
    private var selectedRatioView: TextView? = null
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoUri = intent.getParcelableExtra(EXTRA_URI) ?: run {
            finish()
            return
        }
        photoName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "SwiftPic" }
        dateTakenMs = intent.getLongExtra(EXTRA_DATE_TAKEN, 0L)

        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.isNavigationBarContrastEnforced = false
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        binding.backButton.setOnClickListener { finish() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.saveButton.setOnClickListener { confirmSave() }
        binding.rotateLeftButton.setOnClickListener { rotate(-90) }
        binding.rotateRightButton.setOnClickListener { rotate(90) }
        binding.mirrorButton.setOnClickListener { mirror() }
        binding.resetButton.setOnClickListener { reset() }
        setupRatios()
        loadBitmap()
    }

    private fun setupRatios() {
        val chips = arrayOf(
            binding.ratioFree to null,
            binding.ratioSquare to 1f,
            binding.ratio4x3 to 4f / 3f,
            binding.ratio3x4 to 3f / 4f,
            binding.ratio16x9 to 16f / 9f,
            binding.ratioOriginal to null
        )
        chips.forEach { (view, ratio) ->
            view.setOnClickListener {
                selectedRatioView = view
                aspect = if (view === binding.ratioOriginal) {
                    working?.let { it.width.toFloat() / it.height }
                } else {
                    ratio
                }
                chips.forEach { (chip, _) -> chip.isSelected = chip === view }
                binding.cropOverlay.setAspectRatio(aspect)
            }
        }
        selectedRatioView = binding.ratioFree
        binding.ratioFree.isSelected = true
    }

    private fun loadBitmap() {
        binding.editLoading.visibility = View.VISIBLE
        binding.saveButton.isEnabled = false
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeEditable(photoUri) }
            if (isFinishing || isDestroyed) {
                bitmap?.recycle()
                return@launch
            }
            binding.editLoading.visibility = View.GONE
            if (bitmap == null) {
                Toast.makeText(this@EditActivity, R.string.edit_load_failed, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            original = bitmap
            binding.saveButton.isEnabled = true
            setWorking(bitmap)
        }
    }

    private fun decodeEditable(uri: Uri): Bitmap? {
        val decode = { drawableMode: Boolean ->
            val source = ImageDecoder.createSource(contentResolver, uri)
            val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val dimensionScale = MAX_DIMENSION.toFloat() / max(width, height)
                val pixelScale = sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height))
                    .toFloat()
                val scale = min(1f, min(dimensionScale, pixelScale))
                if (scale < 1f) {
                    decoder.setTargetSize(
                        max(1, (width * scale).roundToInt()),
                        max(1, (height * scale).roundToInt())
                    )
                }
            }
            if (drawableMode) {
                val drawable = ImageDecoder.decodeDrawable(source, listener)
                val width = drawable.intrinsicWidth.coerceAtLeast(1)
                val height = drawable.intrinsicHeight.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
                bitmap
            } else {
                ImageDecoder.decodeBitmap(source, listener)
            }
        }
        return runCatching { decode(false) }.getOrElse { runCatching { decode(true) }.getOrNull() }
    }

    private fun setWorking(bitmap: Bitmap) {
        val old = working
        working = bitmap
        if (selectedRatioView === binding.ratioOriginal) {
            aspect = bitmap.width.toFloat() / bitmap.height
            binding.cropOverlay.setAspectRatio(aspect)
        }
        binding.editImage.setImageBitmap(bitmap)
        binding.cropOverlay.post {
            if (isFinishing || isDestroyed) return@post
            updateImageBox(bitmap)
        }
        if (old != null && old !== bitmap && old !== original) {
            old.recycle()
        }
    }

    private fun updateImageBox(bitmap: Bitmap) {
        val viewWidth = binding.editImage.width
        val viewHeight = binding.editImage.height
        if (viewWidth <= 0 || viewHeight <= 0) return
        val scale = min(
            viewWidth.toFloat() / bitmap.width,
            viewHeight.toFloat() / bitmap.height
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f
        binding.cropOverlay.setImageBox(left, top, left + width, top + height)
    }

    private fun rotate(degrees: Int) {
        if (saving) return
        val source = working ?: return
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )
        setWorking(rotated)
    }

    private fun mirror() {
        if (saving) return
        val source = working ?: return
        val matrix = Matrix().apply {
            postScale(-1f, 1f, source.width / 2f, source.height / 2f)
        }
        val flipped = Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )
        setWorking(flipped)
    }

    private fun reset() {
        if (saving) return
        val source = original ?: return
        if (working === source) {
            binding.cropOverlay.resetCrop()
            return
        }
        setWorking(source)
    }

    private fun confirmSave() {
        if (saving || working == null) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_edit_title)
            .setMessage(R.string.save_edit_message)
            .setPositiveButton(R.string.save_copy) { _, _ -> saveNow() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveNow() {
        val source = working ?: return
        saving = true
        binding.saveButton.isEnabled = false
        binding.editLoading.visibility = View.VISIBLE
        val cropRect = binding.cropOverlay.cropInBitmap(source.width, source.height)
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val isFull = cropRect.left == 0 && cropRect.top == 0 &&
                    cropRect.width() == source.width && cropRect.height() == source.height
                val output = if (isFull) {
                    source
                } else {
                    runCatching {
                        Bitmap.createBitmap(
                            source,
                            cropRect.left,
                            cropRect.top,
                            cropRect.width(),
                            cropRect.height()
                        )
                    }.getOrNull()
                } ?: return@withContext null
                val uri = ImageExporter.saveJpegCopy(
                    this@EditActivity,
                    output,
                    photoName,
                    dateTakenMs
                )
                if (output !== source) output.recycle()
                uri
            }
            saving = false
            binding.editLoading.visibility = View.GONE
            if (saved != null) {
                app.gallery.markDirty()
                Toast.makeText(this@EditActivity, R.string.save_edit_done, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                binding.saveButton.isEnabled = true
                Toast.makeText(this@EditActivity, R.string.save_edit_failed, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (working !== original) working?.recycle()
        original?.recycle()
        working = null
        original = null
    }

    companion object {
        private const val EXTRA_URI = "edit_uri"
        private const val EXTRA_NAME = "edit_name"
        private const val EXTRA_DATE_TAKEN = "edit_date_taken"
        private const val MAX_DIMENSION = 4096
        private const val MAX_PIXELS = 10_000_000.0

        fun start(context: Context, photo: Photo) {
            val intent = Intent(context, EditActivity::class.java).apply {
                putExtra(EXTRA_URI, photo.uri)
                putExtra(EXTRA_NAME, photo.name)
                putExtra(EXTRA_DATE_TAKEN, photo.dateTakenMs)
            }
            context.startActivity(intent)
        }
    }
}
