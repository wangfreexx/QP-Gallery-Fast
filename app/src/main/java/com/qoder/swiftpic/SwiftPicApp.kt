package com.qoder.swiftpic

import android.app.Application
import android.content.Context
import android.util.Log
import com.qoder.swiftpic.data.AppPrefs
import com.qoder.swiftpic.data.GalleryRepository
import com.qoder.swiftpic.data.ImageLoader
import com.qoder.swiftpic.data.MotionPhotoProbe
import com.qoder.swiftpic.data.ThumbnailLoader
import com.qoder.swiftpic.data.UserDataStore

class SwiftPicApp : Application() {

    lateinit var prefs: AppPrefs
        private set
    lateinit var gallery: GalleryRepository
        private set
    lateinit var thumbnails: ThumbnailLoader
        private set
    lateinit var images: ImageLoader
        private set
    lateinit var motion: MotionPhotoProbe
        private set
    lateinit var userData: UserDataStore
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(
            "SwiftPic",
            "start version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "sdk=${android.os.Build.VERSION.SDK_INT}"
        )
        prefs = AppPrefs(this)
        userData = UserDataStore(this)
        thumbnails = ThumbnailLoader(this)
        images = ImageLoader(this)
        motion = MotionPhotoProbe(this)
        gallery = GalleryRepository(this)
        motion.trimCache()
    }
}

val Context.swiftPic: SwiftPicApp
    get() = applicationContext as SwiftPicApp
