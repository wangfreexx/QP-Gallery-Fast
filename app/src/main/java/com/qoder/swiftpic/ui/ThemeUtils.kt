package com.qoder.swiftpic.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.color.MaterialColors
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.AppPrefs
import com.qoder.swiftpic.data.ThemeMode

object ThemeUtils {

    fun isSystemNight(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    fun styleFor(context: Context, themeMode: ThemeMode): Int {
        return when (themeMode) {
            ThemeMode.SYSTEM -> if (isSystemNight(context)) R.style.Theme_SwiftPic else R.style.Theme_SwiftPic_Light
            ThemeMode.DARK -> R.style.Theme_SwiftPic
            ThemeMode.LIGHT -> R.style.Theme_SwiftPic_Light
            ThemeMode.AMOLED -> R.style.Theme_SwiftPic_Amoled
        }
    }

    fun keyFor(context: Context, themeMode: ThemeMode): String {
        return "${themeMode.name}:${isSystemNight(context)}"
    }

    fun applyTheme(context: Context): ThemeMode {
        val prefs = AppPrefs(context)
        context.setTheme(styleFor(context, prefs.themeMode))
        return prefs.themeMode
    }

    fun tintToolbarMenu(toolbar: Toolbar) {
        val color = MaterialColors.getColor(
            toolbar,
            com.google.android.material.R.attr.colorOnSurface
        )
        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            val icon = menu.getItem(i).icon ?: continue
            DrawableCompat.setTint(icon.mutate(), color)
        }
    }
}
