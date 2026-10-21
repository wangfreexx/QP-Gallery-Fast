package com.qoder.swiftpic.ui.viewer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.databinding.ViewPhotoPageBinding

class PhotoPagerAdapter(
    private val activity: ViewerActivity,
    private val photos: MutableList<Photo>,
    private val callback: PhotoPageHolder.Callback
) : RecyclerView.Adapter<PhotoPageHolder>() {

    var activeIndex: Int = 0

    /** Resolved from the live list rather than a cached field: the pager can ask for the
     *  active holder before the current page has any ViewHolder, and a field then stays
     *  null, which silently breaks taps, chrome and live/GIF playback. */
    val activeHolder: PhotoPageHolder? get() = holderAt(activeIndex)

    private val holders = LinkedHashSet<PhotoPageHolder>()
    private var list: RecyclerView? = null

    private fun holderAt(position: Int): PhotoPageHolder? {
        val cached = holders.firstOrNull { it.bindingAdapterPosition == position }
        if (cached != null) return cached
        return list?.findViewHolderForAdapterPosition(position) as? PhotoPageHolder
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        list = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (list === recyclerView) list = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun getItemCount(): Int = photos.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoPageHolder {
        val binding = ViewPhotoPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        val holder = PhotoPageHolder(binding, activity, callback)
        holders.add(holder)
        return holder
    }

    override fun onBindViewHolder(holder: PhotoPageHolder, position: Int) {
        // A ViewHolder can come back from the recycled pool; re-register it so
        // active-page switching and releaseAll still see it.
        holders.add(holder)
        holder.bind(photos[position], position, position == activeIndex)
    }

    override fun onViewAttachedToWindow(holder: PhotoPageHolder) {
        super.onViewAttachedToWindow(holder)
        holders.add(holder)
        holder.setActive(holderAt(holder.bindingAdapterPosition) === holder)
    }

    override fun onViewDetachedFromWindow(holder: PhotoPageHolder) {
        holder.setActive(false)
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: PhotoPageHolder) {
        holder.release()
        holders.remove(holder)
        super.onViewRecycled(holder)
    }

    fun setActivePosition(position: Int) {
        activeIndex = position
        val found = activeHolder
        holders.forEach { holder ->
            if (holder !== found) holder.setActive(false)
        }
        found?.setActive(true)
    }

    fun removeAt(index: Int) {
        if (index !in photos.indices) return
        photos.removeAt(index)
        notifyItemRemoved(index)
    }

    fun releaseAll() {
        holders.forEach { it.release() }
        holders.clear()
    }
}
