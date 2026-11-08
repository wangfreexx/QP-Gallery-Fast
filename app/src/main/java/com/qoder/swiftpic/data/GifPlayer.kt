package com.qoder.swiftpic.data

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GifPlayer(
    private val source: GifSource,
    private val onFrame: (bitmap: Bitmap, index: Int, frameCount: Int) -> Unit,
    private val onPlayingChanged: (playing: Boolean) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val provider = PooledBitmapProvider()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SwiftPic-GIF").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var released = false

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var currentIndex: Int = -1
        private set

    /** Bumped on every state change so stale decode tasks are ignored. */
    private val session = AtomicInteger(0)

    @Volatile
    private var decoder: StandardGifDecoder? = null

    /** Frame index [decoder] currently sits at; touched on the executor thread only. */
    @Volatile
    private var decoderFrameIndex: Int = -1

    val frameCount: Int = source.frameCount

    fun start() {
        if (released || isPlaying || frameCount <= 1) {
            if (!released && frameCount == 1) showSingleFrame()
            return
        }
        isPlaying = true
        Log.d(TAG, "start frames=$frameCount sample=${source.sampleSize} at=$currentIndex")
        onPlayingChanged(true)
        val token = session.incrementAndGet()
        executor.execute { runPlayback(token) }
    }

    fun pause() {
        if (!isPlaying) return
        Log.d(TAG, "pause at=$currentIndex")
        isPlaying = false
        session.incrementAndGet()
        onPlayingChanged(false)
    }

    fun seek(index: Int) {
        if (released) return
        isPlaying = false
        onPlayingChanged(false)
        val target = index.coerceIn(0, frameCount - 1)
        Log.d(TAG, "seek -> $target")
        currentIndex = target
        val token = session.incrementAndGet()
        executor.execute {
            try {
                val existing = decoder
                val from = decoderFrameIndex
                val frame: Bitmap?
                if (existing != null && from >= 0 && target > from) {
                    // Reuse the live decoder and decode only the delta instead of
                    // re-decoding every frame from 0 on every seek.
                    frame = advanceFrom(existing, from, target)
                    decoderFrameIndex = if (frame != null) target else -1
                } else {
                    val fresh = newDecoder()
                    frame = decodeTo(fresh, target)
                    val old = existing
                    decoder = fresh
                    decoderFrameIndex = if (frame != null) target else -1
                    old?.clear()
                }
                if (frame == null) throw IllegalStateException("GIF frame decode failed")
                postFrame(token, frame, target)
            } catch (e: Exception) {
                postError(token, e)
            }
        }
    }

    fun releaseDisplayed(bitmap: Bitmap) {
        provider.release(bitmap)
    }

    fun release() {
        if (released) return
        Log.d(TAG, "release")
        released = true
        isPlaying = false
        session.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            executor.execute {
                decoder?.clear()
                decoder = null
                decoderFrameIndex = -1
                provider.recycleAll()
            }
        } catch (e: Exception) {
            Log.w(TAG, "release cleanup rejected", e)
        }
        executor.shutdown()
    }

    private fun showSingleFrame() {
        if (released) return
        val token = session.incrementAndGet()
        executor.execute {
            try {
                if (decoder == null) {
                    val d = newDecoder()
                    val frame = decodeTo(d, 0)
                        ?: throw IllegalStateException("GIF frame decode failed")
                    decoder = d
                    decoderFrameIndex = 0
                    currentIndex = 0
                    postFrame(token, frame, 0)
                }
            } catch (e: Exception) {
                postError(token, e)
            }
        }
    }

    private fun runPlayback(token: Int) {
        try {
            val startIndex = currentIndex.coerceAtLeast(0).coerceAtMost(frameCount - 1)
            val newDecoder = newDecoder()
            val frame = decodeTo(newDecoder, startIndex)
                ?: throw IllegalStateException("GIF frame decode failed")
            val old = decoder
            decoder = newDecoder
            decoderFrameIndex = startIndex
            currentIndex = startIndex
            old?.clear()
            postFrame(token, frame, startIndex)
            if (frameCount <= 1) return

            while (token == session.get() && isPlaying && !released) {
                val d = decoder ?: break
                val indexNow = currentIndex.coerceAtLeast(0)
                val delay = normalizedDelay(d.getDelay(indexNow))
                sleepWithChecks(delay, token)
                if (token != session.get() || !isPlaying || released) break

                d.advance()
                val nextFrame = d.getNextFrame() ?: break
                val nextIndex = d.getCurrentFrameIndex()
                decoderFrameIndex = nextIndex
                currentIndex = nextIndex
                postFrame(token, nextFrame, nextIndex)
            }
        } catch (e: Exception) {
            postError(token, e)
        }
    }

    private fun sleepWithChecks(delayMs: Int, token: Int) {
        var remaining = delayMs.toLong()
        while (remaining > 0 && token == session.get() && isPlaying && !released) {
            val slice = remaining.coerceAtMost(20L)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    private fun decodeTo(decoder: StandardGifDecoder, target: Int): Bitmap? {
        decoder.resetFrameIndex()
        var result: Bitmap? = null
        for (i in 0..target) {
            decoder.advance()
            val frame = decoder.getNextFrame() ?: return result
            if (i == target) {
                result = frame
            } else {
                provider.release(frame)
            }
        }
        return result
    }

    /** Advances an already positioned decoder from [from] to [target], decoding only the delta. */
    private fun advanceFrom(decoder: StandardGifDecoder, from: Int, target: Int): Bitmap? {
        var result: Bitmap? = null
        for (i in (from + 1)..target) {
            decoder.advance()
            val frame = decoder.getNextFrame() ?: return result
            if (i == target) {
                result = frame
            } else {
                provider.release(frame)
            }
        }
        return result
    }

    private fun postFrame(token: Int, bitmap: Bitmap, index: Int) {
        mainHandler.post {
            var handled = false
            try {
                if (released || token != session.get()) {
                    provider.release(bitmap)
                    handled = true
                } else {
                    if (index == 0) Log.d(TAG, "frame 0/$frameCount")
                    onFrame(bitmap, index, frameCount)
                    handled = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "frame delivery failed", e)
            } finally {
                if (!handled) {
                    // Callback threw (or was stale): make sure the bitmap is returned
                    // to the pool and never leaks, without crashing the main thread.
                    try {
                        provider.release(bitmap)
                    } catch (releaseError: Exception) {
                        Log.w(TAG, "release after frame failure failed", releaseError)
                    }
                }
            }
        }
    }

    private fun postError(token: Int, throwable: Throwable) {
        Log.w(TAG, "GIF playback failed", throwable)
        mainHandler.post {
            try {
                if (!released && token == session.get()) {
                    isPlaying = false
                    onPlayingChanged(false)
                    onError(throwable)
                }
            } catch (e: Exception) {
                Log.w(TAG, "onError callback failed", e)
            }
        }
    }

    private fun normalizedDelay(raw: Int): Int {
        return when {
            raw <= 10 -> 100
            raw > 10_000 -> 10_000
            else -> raw
        }
    }

    private fun newDecoder(): StandardGifDecoder {
        val decoder = StandardGifDecoder(
            provider,
            source.header,
            source.buffer(),
            source.sampleSize
        )
        decoder.setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
        return decoder
    }

    private class PooledBitmapProvider : GifDecoder.BitmapProvider {

        private val pool = ArrayDeque<Bitmap>(4)
        private val closed = AtomicBoolean(false)

        @Synchronized
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap {
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (!candidate.isRecycled && candidate.width == width &&
                    candidate.height == height && candidate.config == config
                ) {
                    iterator.remove()
                    candidate.eraseColor(0)
                    return candidate
                }
            }
            val created = Bitmap.createBitmap(width, height, config)
            CacheStats.addGifFrame(created)
            return created
        }

        @Synchronized
        override fun release(bitmap: Bitmap) {
            if (closed.get() || bitmap.isRecycled) {
                if (!bitmap.isRecycled) {
                    CacheStats.removeGifFrame(bitmap)
                    bitmap.recycle()
                }
                return
            }
            if (pool.size < 3) {
                pool.addLast(bitmap)
            } else {
                CacheStats.removeGifFrame(bitmap)
                bitmap.recycle()
            }
        }

        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)

        override fun release(bytes: ByteArray) = Unit

        override fun obtainIntArray(size: Int): IntArray = IntArray(size)

        override fun release(array: IntArray) = Unit

        @Synchronized
        fun recycleAll() {
            closed.set(true)
            pool.forEach {
                if (!it.isRecycled) {
                    CacheStats.removeGifFrame(it)
                    it.recycle()
                }
            }
            pool.clear()
        }
    }

    private companion object {
        const val TAG = "SwiftPicGIF"
    }
}
