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
        // NO_POSITION 的 holder 不能作为激活态候选，否则激活状态会串到别的页。
        if (position == RecyclerView.NO_POSITION || position < 0) return null
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
        releaseAll()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun getItemCount(): Int = photos.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoPageHolder {
        val binding = ViewPhotoPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PhotoPageHolder(binding, activity, callback)
    }

    override fun onBindViewHolder(holder: PhotoPageHolder, position: Int) {
        holder.bind(photos[position], position, position == activeIndex)
    }

    override fun onViewAttachedToWindow(holder: PhotoPageHolder) {
        super.onViewAttachedToWindow(holder)
        holders.add(holder)
        val position = holder.bindingAdapterPosition
        holder.setActive(
            position != RecyclerView.NO_POSITION &&
                position == activeIndex &&
                holderAt(position) === holder
        )
    }

    override fun onViewDetachedFromWindow(holder: PhotoPageHolder) {
        holder.setActive(false)
        holders.remove(holder)
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
        val newCount = photos.size
        activeIndex = when {
            newCount == 0 -> 0
            activeIndex > index -> activeIndex - 1
            activeIndex >= newCount -> newCount - 1
            else -> activeIndex
        }
        notifyItemRemoved(index)
        // 让删除点之后的 holder 重新绑定，修正它们的 bindingAdapterPosition，
        // 避免 activeHolder 落到错位的页上。
        if (index < newCount) {
            notifyItemRangeChanged(index, newCount - index)
        }
    }

    fun releaseAll() {
        holders.forEach { it.release() }
        holders.clear()
    }
}
