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
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.ImageExporter
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.databinding.ActivityEditBinding
import com.qoder.swiftpic.swiftPic
import com.qoder.swiftpic.ui.AppHint
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
    private var photoMime: String = "image/jpeg"
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
        photoMime = intent.getStringExtra(EXTRA_MIME).orEmpty().ifBlank { "image/jpeg" }
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
            binding.ratioFree,
            binding.ratioSquare,
            binding.ratio4x3,
            binding.ratio3x4,
            binding.ratio16x9,
            binding.ratioOriginal
        )
        chips.forEach { view ->
            view.setOnClickListener {
                selectedRatioView = view
                aspect = ratioForView(view)
                chips.forEach { chip -> chip.isSelected = chip === view }
                binding.cropOverlay.setAspectRatio(aspect)
            }
        }
        selectedRatioView = binding.ratioFree
        binding.ratioFree.isSelected = true
    }

    private fun ratioForView(view: View?): Float? = when (view) {
        binding.ratioSquare -> 1f
        binding.ratio4x3 -> 4f / 3f
        binding.ratio3x4 -> 3f / 4f
        binding.ratio16x9 -> 16f / 9f
        // "Original" depends on the loaded bitmap; return null until it is ready.
        binding.ratioOriginal -> working?.takeIf { it.height > 0 }
            ?.let { it.width.toFloat() / it.height.toFloat() }
        else -> null
    }

    private fun setRatiosEnabled(enabled: Boolean) {
        arrayOf<View>(
            binding.ratioFree,
            binding.ratioSquare,
            binding.ratio4x3,
            binding.ratio3x4,
            binding.ratio16x9,
            binding.ratioOriginal
        ).forEach { it.isEnabled = enabled }
    }

    private fun loadBitmap() {
        binding.editLoading.visibility = View.VISIBLE
        binding.saveButton.isEnabled = false
        // A ratio chip cannot be honoured before the bitmap exists (especially
        // "original"), so keep them disabled until loading finishes.
        setRatiosEnabled(false)
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeEditable(photoUri) }
            if (isFinishing || isDestroyed) {
                bitmap?.recycle()
                return@launch
            }
            binding.editLoading.visibility = View.GONE
            if (bitmap == null) {
                AppHint.show(this@EditActivity, R.string.edit_load_failed)
                finish()
                return@launch
            }
            original = bitmap
            binding.saveButton.isEnabled = true
            setRatiosEnabled(true)
            setWorking(bitmap)
        }
    }

    private fun decodeEditable(uri: Uri): Bitmap? {
        fun decode(drawableMode: Boolean, maxDimension: Int, maxPixels: Double): Bitmap {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val dimensionScale = maxDimension.toFloat() / max(width, height)
                val pixelScale = sqrt(maxPixels / (width.toDouble() * height)).toFloat()
                val scale = min(1f, min(dimensionScale, pixelScale))
                if (scale < 1f) {
                    decoder.setTargetSize(
                        max(1, (width * scale).roundToInt()),
                        max(1, (height * scale).roundToInt())
                    )
                }
            }
            if (!drawableMode) return ImageDecoder.decodeBitmap(source, listener)
            val drawable = ImageDecoder.decodeDrawable(source, listener)
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            var drawn = false
            // Release the intermediate bitmap if drawing fails for any reason.
            return try {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
                drawn = true
                bitmap
            } finally {
                if (!drawn) bitmap.recycle()
            }
        }

        // First attempt: software bitmap decode. Only Exception triggers the
        // drawable fallback. An OutOfMemoryError is handled separately with a
        // SINGLE downgraded retry, never a second full-size allocation.
        try {
            return decode(false, MAX_DIMENSION, MAX_PIXELS)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "decodeBitmap OOM, downgrading once", e)
            return try {
                decode(true, MAX_DIMENSION / 2, MAX_PIXELS / 4)
            } catch (e2: OutOfMemoryError) {
                Log.w(TAG, "OOM downgrade failed", e2)
                null
            } catch (e2: Exception) {
                Log.w(TAG, "OOM downgrade failed", e2)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodeBitmap failed, trying drawable mode", e)
        }

        return try {
            decode(true, MAX_DIMENSION, MAX_PIXELS)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "drawable decode OOM", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "drawable decode failed", e)
            null
        }
    }

    private fun setWorking(bitmap: Bitmap) {
        val old = working
        working = bitmap
        // "Original" ratio depends on bitmap dimensions, so recompute the aspect
        // for the currently selected chip before the crop box is re-applied.
        aspect = ratioForView(selectedRatioView)
        binding.cropOverlay.setAspectRatio(aspect)
        binding.editImage.setImageBitmap(bitmap)
        binding.cropOverlay.post {
            if (isFinishing || isDestroyed) return@post
            updateImageBox(bitmap)
            binding.cropOverlay.resetCrop()
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
        applyTransform(Matrix().apply { postRotate(degrees.toFloat()) }, "rotate")
    }

    private fun mirror() {
        val source = working ?: return
        applyTransform(
            Matrix().apply {
                postScale(-1f, 1f, source.width / 2f, source.height / 2f)
            },
            "mirror"
        )
    }

    private fun applyTransform(matrix: Matrix, tag: String) {
        if (saving) return
        val source = working ?: return
        val transformed: Bitmap? = try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "$tag OOM", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "$tag failed", e)
            null
        }
        // On failure keep the original working bitmap untouched and recycle any
        // temporary bitmap that was produced but is unusable.
        if (transformed == null || transformed === source) return
        if (transformed.isRecycled || transformed.width <= 0 || transformed.height <= 0) {
            transformed.recycle()
            return
        }
        setWorking(transformed)
    }

    private fun reset() {
        if (saving) return
        val source = original ?: return
        // Reset the crop frame in BOTH branches (before and after restoring the
        // original bitmap); setWorking also re-applies the current ratio.
        binding.cropOverlay.resetCrop()
        if (working === source) return
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
                val output: Bitmap = if (isFull) {
                    source
                } else {
                    val cropped = try {
                        Bitmap.createBitmap(
                            source,
                            cropRect.left,
                            cropRect.top,
                            cropRect.width(),
                            cropRect.height()
                        )
                    } catch (e: OutOfMemoryError) {
                        Log.w(TAG, "crop OOM", e)
                        null
                    } catch (e: Exception) {
                        Log.w(TAG, "crop failed", e)
                        null
                    }
                    cropped ?: return@withContext null
                }
                try {
                    ImageExporter.saveJpegCopy(
                        this@EditActivity,
                        output,
                        photoName,
                        dateTakenMs,
                        sourceMime = photoMime,
                        sourceUri = photoUri,
                        preserveAlpha = output.hasAlpha()
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "saveJpegCopy failed", e)
                    null
                } finally {
                    if (output !== source) output.recycle()
                }
            }
            saving = false
            binding.editLoading.visibility = View.GONE
            if (saved != null) {
                app.gallery.markDirty()
                AppHint.show(this@EditActivity, R.string.save_edit_done)
                setResult(RESULT_OK)
                finish()
            } else {
                binding.saveButton.isEnabled = true
                AppHint.show(this@EditActivity, R.string.save_edit_failed)
            }
        }
    }

    override fun onDestroy() {
        // Detach the bitmap before recycling so a transition frame cannot draw a
        // recycled bitmap.
        binding.editImage.setImageDrawable(null)
        if (working !== original) working?.recycle()
        original?.recycle()
        working = null
        original = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SwiftPicEdit"
        private const val EXTRA_URI = "edit_uri"
        private const val EXTRA_NAME = "edit_name"
        private const val EXTRA_MIME = "edit_mime"
        private const val EXTRA_DATE_TAKEN = "edit_date_taken"
        private const val MAX_DIMENSION = 4096
        private const val MAX_PIXELS = 10_000_000.0

        fun start(context: Context, photo: Photo) {
            val intent = Intent(context, EditActivity::class.java).apply {
                putExtra(EXTRA_URI, photo.uri)
                putExtra(EXTRA_NAME, photo.name)
                putExtra(EXTRA_MIME, photo.mime)
                putExtra(EXTRA_DATE_TAKEN, photo.dateTakenMs)
            }
            context.startActivity(intent)
        }
    }
}
