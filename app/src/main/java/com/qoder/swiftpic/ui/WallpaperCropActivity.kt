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
import androidx.lifecycle.lifecycleScope
import com.qoder.swiftpic.R
import com.qoder.swiftpic.databinding.ActivityWallpaperCropBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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
                AppHint.show(this@WallpaperCropActivity, R.string.image_decode_failed)
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
        fun decode(drawableMode: Boolean): Bitmap {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val dimensionScale = MAX_DIMENSION.toFloat() / max(width, height)
                val pixelScale = sqrt(MAX_PIXELS / (width.toDouble() * height)).toFloat()
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
            val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            var drawn = false
            return try {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(android.graphics.Canvas(created))
                drawn = true
                created
            } finally {
                if (!drawn) created.recycle()
            }
        }

        return try {
            decode(false)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "decodeBitmap OOM uri=$uri", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "decodeBitmap failed uri=$uri", e)
            try {
                decode(true)
            } catch (e2: OutOfMemoryError) {
                Log.w(TAG, "drawable decode OOM uri=$uri", e2)
                null
            } catch (e2: Exception) {
                Log.w(TAG, "drawable decode failed uri=$uri", e2)
                null
            }
        }
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
                // Clamp first: never hand an out-of-bounds rect to createBitmap.
                val clamped = clampCrop(crop, source.width, source.height)
                if (clamped == null) {
                    Log.w(TAG, "invalid crop $crop for ${source.width}x${source.height}")
                    return@withContext false
                }
                var output: Bitmap? = null
                try {
                    output = croppedRegion(source, clamped)
                    if (output == null) {
                        // A failed crop must abort; never fall back to the full
                        // un-cropped original image.
                        Log.w(TAG, "crop failed, refusing to set the uncropped original")
                        return@withContext false
                    }
                    WallpaperManager.getInstance(this@WallpaperCropActivity).setBitmap(output)
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "setBitmap failed", e)
                    false
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "setBitmap failed", e)
                    false
                } catch (e: SecurityException) {
                    Log.w(TAG, "setBitmap failed", e)
                    false
                } catch (e: Exception) {
                    Log.w(TAG, "applyWallpaper failed", e)
                    false
                } finally {
                    // Always release the cropped bitmap (but never the source).
                    if (output !== source) output?.recycle()
                }
            }
            applying = false
            binding.applyButton.isEnabled = true
            AppHint.show(
                this@WallpaperCropActivity,
                if (ok) R.string.wallpaper_done else R.string.wallpaper_failed
            )
            if (ok) finish()
        }
    }

    private fun clampCrop(crop: Rect, width: Int, height: Int): Rect? {
        if (width <= 0 || height <= 0) return null
        val left = crop.left.coerceIn(0, width - 1)
        val top = crop.top.coerceIn(0, height - 1)
        val right = crop.right.coerceIn(left + 1, width)
        val bottom = crop.bottom.coerceIn(top + 1, height)
        if (right <= left || bottom <= top) return null
        return Rect(left, top, right, bottom)
    }

    /** Returns the cropped bitmap, or null on any failure (never the original). */
    private fun croppedRegion(source: Bitmap, crop: Rect): Bitmap? {
        val isFull = crop.left == 0 && crop.top == 0 &&
            crop.width() == source.width && crop.height() == source.height
        if (isFull) return source
        return try {
            Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "crop OOM", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "crop out of bounds", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "crop failed", e)
            null
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
        private const val TAG = "SwiftPicWallpaper"
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
