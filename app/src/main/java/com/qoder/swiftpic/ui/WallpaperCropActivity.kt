package com.qoder.swiftpic.ui

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.qoder.swiftpic.R
import com.qoder.swiftpic.databinding.ActivityWallpaperCropBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class WallpaperCropActivity : BaseActivity() {

    private lateinit var binding: ActivityWallpaperCropBinding

    override val usesWallpaperBackground: Boolean = false

    private var sourceUri: Uri? = null
    private var bitmap: Bitmap? = null
    private var selectedAspect: Aspect = Aspect.Free
    private var applying = false

    private val screenAspect: Float
        get() {
            val metrics = resources.displayMetrics
            return if (metrics.heightPixels <= 0) 0.5625f
            else metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWallpaperCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
        if (uri == null) {
            finish()
            return
        }
        sourceUri = uri
        binding.backButton.setOnClickListener { finish() }
        binding.applyButton.setOnClickListener { applyWallpaper() }
        binding.resetButton.setOnClickListener { binding.cropOverlay.resetCrop() }
        setupChips()
        loadBitmap()
    }

    private fun setupChips() {
        val chips = listOf(
            binding.ratioScreen to Aspect.Fixed(screenAspect),
            binding.ratioFree to Aspect.Free,
            binding.ratioSquare to Aspect.Fixed(1f),
            binding.ratio3x4 to Aspect.Fixed(3f / 4f),
            binding.ratio16x9 to Aspect.Fixed(16f / 9f),
            binding.ratioOriginal to Aspect.Original
        )
        chips.forEach { (view, aspect) ->
            view.setOnClickListener {
                selectedAspect = aspect
                chips.forEach { (chip, _) -> chip.isSelected = chip === view }
                applyAspect()
            }
        }
        selectedAspect = Aspect.Fixed(screenAspect)
        binding.ratioScreen.isSelected = true
    }

    private fun applyAspect() {
        val source = bitmap
        val ratio = when (val aspect = selectedAspect) {
            is Aspect.Fixed -> aspect.ratio
            Aspect.Original -> source?.let { it.width.toFloat() / it.height.toFloat() }
            Aspect.Free -> null
        }
        binding.cropOverlay.setAspectRatio(ratio)
    }

    private fun loadBitmap() {
        val uri = sourceUri ?: return
        binding.cropLoading.visibility = View.VISIBLE
        binding.applyButton.isEnabled = false
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeScaled(uri) }
            if (isFinishing || isDestroyed) {
                decoded?.recycle()
                return@launch
            }
            binding.cropLoading.visibility = View.GONE
            if (decoded == null) {
                Toast.makeText(this@WallpaperCropActivity, R.string.image_decode_failed, Toast.LENGTH_SHORT)
                    .show()
                finish()
                return@launch
            }
            bitmap = decoded
            binding.applyButton.isEnabled = true
            binding.cropImage.setImageBitmap(decoded)
            binding.cropImage.post {
                if (isFinishing || isDestroyed) return@post
                updateCropBox(decoded)
            }
        }
    }

    private fun decodeScaled(uri: Uri): Bitmap? {
        val decode = { drawableMode: Boolean ->            val source = ImageDecoder.createSource(contentResolver, uri)
            val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val dimensionScale = MAX_DIMENSION.toFloat() / max(width, height)
                val pixelScale = sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height)).toFloat()
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
                val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(android.graphics.Canvas(created))
                created
            } else {
                ImageDecoder.decodeBitmap(source, listener)
            }
        }
        val first = runCatching { decode(false) }
        if (first.isSuccess) return first.getOrNull()
        val second = runCatching { decode(true) }
        Log.w("SwiftPicWallpaper", "decode failed uri=$uri", second.exceptionOrNull() ?: first.exceptionOrNull())
        return second.getOrNull()
    }

    private fun updateCropBox(source: Bitmap) {
        val viewWidth = binding.cropImage.width
        val viewHeight = binding.cropImage.height
        if (viewWidth <= 0 || viewHeight <= 0) return
        val scale = min(
            viewWidth.toFloat() / source.width,
            viewHeight.toFloat() / source.height
        )
        val width = source.width * scale
        val height = source.height * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f
        binding.cropOverlay.setImageBox(left, top, left + width, top + height)
        applyAspect()
    }

    private fun applyWallpaper() {
        val source = bitmap ?: return
        if (applying) return
        applying = true
        binding.applyButton.isEnabled = false
        val crop = binding.cropOverlay.cropInBitmap(source.width, source.height)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val output = croppedRegion(source, crop)
                    WallpaperManager.getInstance(this@WallpaperCropActivity).setBitmap(output)
                    if (output !== source) output.recycle()
                }.isSuccess
            }
            applying = false
            binding.applyButton.isEnabled = true
            Toast.makeText(
                this@WallpaperCropActivity,
                if (ok) R.string.wallpaper_done else R.string.wallpaper_failed,
                Toast.LENGTH_SHORT
            ).show()
            if (ok) finish()
        }
    }

    private fun croppedRegion(source: Bitmap, crop: Rect): Bitmap {
        val isFull = crop.left == 0 && crop.top == 0 &&
            crop.width() == source.width && crop.height() == source.height
        return if (isFull) {
            source
        } else {
            runCatching {
                Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
            }.getOrDefault(source)
        }
    }

    override fun onDestroy() {
        binding.cropImage.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }

    private sealed interface Aspect {
        data class Fixed(val ratio: Float) : Aspect
        object Free : Aspect
        object Original : Aspect
    }

    companion object {
        private const val EXTRA_URI = "wallpaper_uri"
        private const val MAX_DIMENSION = 2560
        private const val MAX_PIXELS = 12_000_000.0

        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, WallpaperCropActivity::class.java).putExtra(EXTRA_URI, uri)
            )
        }
    }
}
