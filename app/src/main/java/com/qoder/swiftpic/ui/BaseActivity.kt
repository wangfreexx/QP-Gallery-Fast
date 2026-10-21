package com.qoder.swiftpic.ui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qoder.swiftpic.data.AppPrefs
import com.qoder.swiftpic.data.BackgroundMode
import com.qoder.swiftpic.data.ThemeMode
import com.qoder.swiftpic.data.WallpaperBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class BaseActivity : AppCompatActivity() {

    private var appliedThemeKey: String? = null
    private var appliedBackgroundKey: String? = null
    private var wallpaperImage: ImageView? = null
    private var wallpaperScrim: View? = null

    protected open val usesWallpaperBackground: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = AppPrefs(this)
        setTheme(ThemeUtils.styleFor(this, prefs.themeMode))
        appliedThemeKey = ThemeUtils.keyFor(this, prefs.themeMode)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val prefs = AppPrefs(this)
        val key = ThemeUtils.keyFor(this, prefs.themeMode)
        if (appliedThemeKey != null && appliedThemeKey != key) {
            recreate()
            return
        }
        val backgroundKey = backgroundKey(prefs)
        if (appliedBackgroundKey != null && appliedBackgroundKey != backgroundKey) {
            recreate()
        }
    }

    override fun onContentChanged() {
        super.onContentChanged()
        val content = findViewById<View>(android.R.id.content) ?: return
        val prefs = AppPrefs(this)
        appliedBackgroundKey = backgroundKey(prefs)
        applyWallpaperBackground(content, prefs)
        animateContentEnter(content, prefs)
    }

    private fun backgroundKey(prefs: AppPrefs): String =
        WallpaperBackground.stamp(this) + "|${prefs.backgroundMode.ordinal}"

    private fun applyWallpaperBackground(content: View, prefs: AppPrefs) {
        if (!usesWallpaperBackground || prefs.backgroundMode == BackgroundMode.THEME) {
            return
        }
        val container = content as? ViewGroup ?: return
        if (container.childCount == 0) return
        val activityRoot = container.getChildAt(0)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val scrim = View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(if (isDarkUi(prefs)) 0x59000000 else 0x66FFFFFF)
        }
        container.addView(
            image,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        container.addView(
            scrim,
            1,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        activityRoot.setBackgroundColor(Color.TRANSPARENT)
        wallpaperImage = image
        wallpaperScrim = scrim

        if (Build.VERSION.SDK_INT >= 31 && prefs.backgroundBlur > 0) {
            val radius = prefs.backgroundBlur * resources.displayMetrics.density * 0.7f
            image.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
        }

        lifecycleScope.launch {
            val metrics = resources.displayMetrics
            val bitmap = withContext(Dispatchers.IO) {
                WallpaperBackground.load(
                    this@BaseActivity,
                    metrics.widthPixels / 2,
                    metrics.heightPixels / 2
                )
            }
            if (bitmap != null && !isFinishing && !isDestroyed && wallpaperImage === image) {
                image.setImageBitmap(bitmap)
            }
        }
    }

    private fun animateContentEnter(content: View, prefs: AppPrefs) {
        Log.d(
            "SwiftPicAnim",
            "enter ${javaClass.simpleName} duration=${UiMotion.duration(this, 180L)}ms " +
                "scale=${prefs.animationScale}"
        )
        UiMotion.animateEnter(content)
    }

    private fun isDarkUi(prefs: AppPrefs): Boolean = when (prefs.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM ->
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}
