package com.qoder.swiftpic.data

import android.content.Context

enum class SortMode {
    DATE_DESC,
    DATE_ASC,
    NAME_ASC,
    NAME_DESC;

    companion object {
        fun fromOrdinal(value: Int): SortMode = values().getOrElse(value) { DATE_DESC }
    }
}

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
    AMOLED;

    companion object {
        fun fromOrdinal(value: Int): ThemeMode = values().getOrElse(value) { DARK }
    }
}

enum class TransitionMode {
    NONE,
    SLIDE,
    FADE;

    companion object {
        fun fromOrdinal(value: Int): TransitionMode = values().getOrElse(value) { NONE }
    }
}

enum class OrientationMode {
    SYSTEM,
    PORTRAIT,
    LANDSCAPE;

    companion object {
        fun fromOrdinal(value: Int): OrientationMode = values().getOrElse(value) { SYSTEM }
    }
}

enum class HomeLayout {
    GRID,
    STACK,
    LIST;

    companion object {
        fun fromOrdinal(value: Int): HomeLayout = values().getOrElse(value) { GRID }
    }
}

enum class BackgroundMode {
    WALLPAPER,
    CUSTOM,
    THEME;

    companion object {
        fun fromOrdinal(value: Int): BackgroundMode = values().getOrElse(value) { WALLPAPER }
    }
}

class AppPrefs(context: Context) {

    private val sp = context.getSharedPreferences("swiftpic_prefs", Context.MODE_PRIVATE)

    var sortMode: SortMode
        get() = SortMode.fromOrdinal(sp.getInt(KEY_SORT, SortMode.DATE_DESC.ordinal))
        set(value) {
            sp.edit().putInt(KEY_SORT, value.ordinal).apply()
        }

    /** Day headers only make sense for the date based modes. */
    val groupPhotosByDate: Boolean
        get() = sortMode == SortMode.DATE_DESC || sortMode == SortMode.DATE_ASC

    var askedPermission: Boolean
        get() = sp.getBoolean(KEY_ASKED_PERMISSION, false)
        set(value) {
            sp.edit().putBoolean(KEY_ASKED_PERMISSION, value).apply()
        }

    var liveMuted: Boolean
        get() = sp.getBoolean(KEY_LIVE_MUTED, true)
        set(value) {
            sp.edit().putBoolean(KEY_LIVE_MUTED, value).apply()
        }

    var liveAutoPlay: Boolean
        get() = sp.getBoolean(KEY_LIVE_AUTOPLAY, true)
        set(value) {
            sp.edit().putBoolean(KEY_LIVE_AUTOPLAY, value).apply()
        }

    var hdrEnabled: Boolean
        get() = sp.getBoolean(KEY_HDR_ENABLED, true)
        set(value) {
            sp.edit().putBoolean(KEY_HDR_ENABLED, value).apply()
        }

    var themeMode: ThemeMode
        get() = ThemeMode.fromOrdinal(sp.getInt(KEY_THEME, ThemeMode.DARK.ordinal))
        set(value) {
            sp.edit().putInt(KEY_THEME, value.ordinal).apply()
        }

    var transitionMode: TransitionMode
        get() = TransitionMode.fromOrdinal(sp.getInt(KEY_TRANSITION, TransitionMode.NONE.ordinal))
        set(value) {
            sp.edit().putInt(KEY_TRANSITION, value.ordinal).apply()
        }

    /** Duration of the photo page transition, 50..1500 ms. */
    var transitionDurationMs: Int
        get() = sp.getInt(KEY_TRANSITION_DURATION, 250).coerceIn(50, 1500)
        set(value) {
            sp.edit().putInt(KEY_TRANSITION_DURATION, value.coerceIn(50, 1500)).apply()
        }

    var orientationMode: OrientationMode
        get() = OrientationMode.fromOrdinal(sp.getInt(KEY_ORIENTATION, OrientationMode.SYSTEM.ordinal))
        set(value) {
            sp.edit().putInt(KEY_ORIENTATION, value.ordinal).apply()
        }

    var tapPaging: Boolean
        get() = sp.getBoolean(KEY_TAP_PAGING, true)
        set(value) {
            sp.edit().putBoolean(KEY_TAP_PAGING, value).apply()
        }

    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) {
            sp.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
        }

    /** Viewer chrome idle timeout in ms; 0 keeps it on screen. */
    var chromeAutoHideMs: Int
        get() = sp.getInt(KEY_CHROME_AUTO_HIDE, 3000).let {
            if (it == 0 || it == 2000 || it == 3000 || it == 5000 || it == 10000) it else 3000
        }
        set(value) {
            sp.edit().putInt(KEY_CHROME_AUTO_HIDE, value).apply()
        }

    var homeLayout: HomeLayout
        get() = HomeLayout.fromOrdinal(sp.getInt(KEY_HOME_LAYOUT, HomeLayout.GRID.ordinal))
        set(value) {
            sp.edit().putInt(KEY_HOME_LAYOUT, value.ordinal).apply()
        }

    /** Animation speed multiplier, 0.1x..4x. Higher is faster. */
    var animationScale: Float
        get() = sp.getFloat(KEY_ANIMATION_SCALE, 1f).coerceIn(0.1f, 4f)
        set(value) {
            sp.edit().putFloat(KEY_ANIMATION_SCALE, value.coerceIn(0.1f, 4f)).apply()
        }

    var backgroundMode: BackgroundMode
        get() = BackgroundMode.fromOrdinal(
            sp.getInt(KEY_BACKGROUND_MODE, BackgroundMode.WALLPAPER.ordinal)
        )
        set(value) {
            sp.edit().putInt(KEY_BACKGROUND_MODE, value.ordinal).apply()
        }

    /** Gaussian blur level for the wallpaper background, 0..40. */
    var backgroundBlur: Int
        get() = sp.getInt(KEY_BACKGROUND_BLUR, 18).coerceIn(0, 40)
        set(value) {
            sp.edit().putInt(KEY_BACKGROUND_BLUR, value.coerceIn(0, 40)).apply()
        }

    /** 0 = auto by screen width; otherwise 3..6. */
    var gridColumns: Int
        get() = sp.getInt(KEY_GRID_COLUMNS, 0).let { if (it in 3..6) it else 0 }
        set(value) {
            sp.edit().putInt(KEY_GRID_COLUMNS, if (value in 3..6) value else 0).apply()
        }

    var includePaths: Set<String>
        get() = sp.getStringSet(KEY_INCLUDE_PATHS, emptySet()).orEmpty()
        set(value) {
            sp.edit().putStringSet(KEY_INCLUDE_PATHS, normalizePaths(value)).apply()
        }

    var excludePaths: Set<String>
        get() = sp.getStringSet(KEY_EXCLUDE_PATHS, emptySet()).orEmpty()
        set(value) {
            sp.edit().putStringSet(KEY_EXCLUDE_PATHS, normalizePaths(value)).apply()
        }

    val lastAlbumKey: String?
        get() = sp.getString(KEY_LAST_ALBUM_KEY, null)

    val lastAlbumName: String
        get() = sp.getString(KEY_LAST_ALBUM_NAME, "").orEmpty()

    val lastAlbumAt: Long
        get() = sp.getLong(KEY_LAST_ALBUM_AT, 0L)

    fun saveLastAlbum(key: String?, name: String) {
        sp.edit()
            .putString(KEY_LAST_ALBUM_KEY, key ?: "")
            .putString(KEY_LAST_ALBUM_NAME, name)
            .putLong(KEY_LAST_ALBUM_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearLastAlbum() {
        sp.edit()
            .remove(KEY_LAST_ALBUM_KEY)
            .remove(KEY_LAST_ALBUM_NAME)
            .remove(KEY_LAST_ALBUM_AT)
            .apply()
    }

    private fun normalizePaths(paths: Collection<String>): Set<String> {
        return paths.map { it.trim().trim('/') }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private companion object {
        const val KEY_SORT = "sort_mode"
        const val KEY_ASKED_PERMISSION = "asked_permission"
        const val KEY_LIVE_MUTED = "live_muted"
        const val KEY_LIVE_AUTOPLAY = "live_autoplay"
        const val KEY_HDR_ENABLED = "hdr_enabled"
        const val KEY_THEME = "theme_mode"
        const val KEY_TRANSITION = "transition_mode"
        const val KEY_TRANSITION_DURATION = "transition_duration"
        const val KEY_ORIENTATION = "orientation_mode"
        const val KEY_TAP_PAGING = "tap_paging"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_CHROME_AUTO_HIDE = "chrome_auto_hide_ms"
        const val KEY_HOME_LAYOUT = "home_layout"
        const val KEY_ANIMATION_SCALE = "animation_scale"
        const val KEY_BACKGROUND_MODE = "background_mode"
        const val KEY_BACKGROUND_BLUR = "background_blur"
        const val KEY_GRID_COLUMNS = "grid_columns"
        const val KEY_INCLUDE_PATHS = "include_paths"
        const val KEY_EXCLUDE_PATHS = "exclude_paths"
        const val KEY_LAST_ALBUM_KEY = "last_album_key"
        const val KEY_LAST_ALBUM_NAME = "last_album_name"
        const val KEY_LAST_ALBUM_AT = "last_album_at"
    }
}
