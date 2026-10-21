package com.qoder.swiftpic.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.HdrDetector
import com.qoder.swiftpic.data.LiveDetector
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.data.ThumbnailLoader
import com.qoder.swiftpic.databinding.ItemPhotoBinding
import com.qoder.swiftpic.databinding.ItemSectionBinding
import com.qoder.swiftpic.swiftPic
import com.qoder.swiftpic.util.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed class PhotoGridItem {
    data class Header(val label: String) : PhotoGridItem()
    data class PhotoRow(val photo: Photo, val photoIndex: Int) : PhotoGridItem()
}

class PhotoAdapter(
    private val scope: CoroutineScope,
    private val thumbnails: ThumbnailLoader,
    private val onClick: (photoIndex: Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<PhotoGridItem>()
    private val selected = LinkedHashSet<String>()

    var selectionMode: Boolean = false
        private set

    var onSelectionChanged: (() -> Unit)? = null

    fun submit(photos: List<Photo>, withDateHeaders: Boolean) {
        items.clear()
        var lastDay: String? = null
        photos.forEachIndexed { index, photo ->
            if (withDateHeaders) {
                val day = Formats.dayLabel(photo.sortTimeMs)
                if (day != lastDay) {
                    items.add(PhotoGridItem.Header(day))
                    lastDay = day
                }
            }
            items.add(PhotoGridItem.PhotoRow(photo, index))
        }
        val keys = photos.mapTo(HashSet(photos.size)) { it.stableKey }
        selected.retainAll(keys)
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    fun itemAt(position: Int): PhotoGridItem = items[position]

    fun isSelected(photo: Photo): Boolean = selected.contains(photo.stableKey)

    fun selectionCount(): Int = selected.size

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selected.clear()
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    fun toggleSelection(photo: Photo) {
        if (selected.contains(photo.stableKey)) selected.remove(photo.stableKey)
        else selected.add(photo.stableKey)
        notifySelectionChanged()
        notifyPhotoChanged(photo)
    }

    fun selectAll() {
        val visible = items.filterIsInstance<PhotoGridItem.PhotoRow>().map { it.photo }
        selected.clear()
        visible.forEach { selected.add(it.stableKey) }
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    private fun notifySelectionChanged() {
        onSelectionChanged?.invoke()
    }

    private fun notifyPhotoChanged(photo: Photo) {
        for (i in items.indices) {
            val row = items[i] as? PhotoGridItem.PhotoRow ?: continue
            if (row.photo.stableKey == photo.stableKey) {
                notifyItemChanged(i, PAYLOAD_SELECTION)
                return
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is PhotoGridItem.Header -> TYPE_HEADER
        is PhotoGridItem.PhotoRow -> TYPE_PHOTO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemSectionBinding.inflate(inflater, parent, false))
        } else {
            PhotoHolder(ItemPhotoBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PhotoGridItem.Header -> (holder as HeaderHolder).bind(item)
            is PhotoGridItem.PhotoRow -> (holder as PhotoHolder).bind(item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val item = items[position]
        if (payloads.isNotEmpty() && item is PhotoGridItem.PhotoRow && holder is PhotoHolder) {
            holder.bindSelection(item)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? PhotoHolder)?.cancel()
        super.onViewRecycled(holder)
    }

    private class HeaderHolder(
        private val binding: ItemSectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PhotoGridItem.Header) {
            binding.sectionTitle.text = item.label
        }
    }

    private inner class PhotoHolder(
        private val binding: ItemPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var job: Job? = null
        private var hdrJob: Job? = null
        private var liveJob: Job? = null

        fun bind(item: PhotoGridItem.PhotoRow) {
            val photo = item.photo
            val context = binding.root.context
            val tag = photo.uri.toString()
            binding.photoImage.setImageDrawable(null)
            binding.photoImage.tag = tag
            binding.root.contentDescription = photo.name

            binding.photoBadge.visibility = View.GONE

            binding.root.setOnClickListener {
                if (selectionMode) toggleSelection(photo) else onClick(item.photoIndex)
            }
            binding.root.setOnLongClickListener {
                if (!selectionMode) setSelectionMode(true)
                if (!isSelected(photo)) toggleSelection(photo)
                true
            }
            bindSelection(item)
            cancel()

            when {
                photo.isGif -> {
                    binding.photoBadge.visibility = View.VISIBLE
                    binding.photoBadge.text = context.getString(R.string.gif_badge)
                }
                photo.hasLivePair -> {
                    binding.photoBadge.visibility = View.VISIBLE
                    binding.photoBadge.text = context.getString(R.string.live_badge)
                }
                photo.canContainMotionPhoto -> {
                    val live = LiveDetector.cached(photo)
                    if (live == true) {
                        binding.photoBadge.visibility = View.VISIBLE
                        binding.photoBadge.text = context.getString(R.string.live_badge)
                    } else if (live == null) {
                        liveJob = scope.launch {
                            val isLive = LiveDetector.isLive(context.applicationContext, photo)
                            if (isLive && binding.photoImage.tag == tag) {
                                binding.photoBadge.visibility = View.VISIBLE
                                binding.photoBadge.text = context.getString(R.string.live_badge)
                            }
                        }
                    }
                }
            }

            binding.photoHdrBadge.visibility = View.GONE
            if (!photo.isGif) {
                hdrJob = scope.launch {
                    val hdr = HdrDetector.isHdr(context.applicationContext, photo)
                    if (hdr && binding.photoImage.tag == tag) {
                        binding.photoHdrBadge.visibility = View.VISIBLE
                    }
                }
            }

            thumbnails.peek(photo)?.let {
                binding.photoImage.setImageBitmap(it)
                return
            }

            job = scope.launch {
                val bitmap = thumbnails.load(photo)
                if (bitmap != null && binding.photoImage.tag == tag) {
                    binding.photoImage.setImageBitmap(bitmap)
                }
            }
        }

        fun bindSelection(item: PhotoGridItem.PhotoRow) {
            val photo = item.photo
            val isSelected = isSelected(photo)
            binding.photoCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.photoScrim.visibility = if (selectionMode && isSelected) View.VISIBLE else View.GONE
            binding.photoCheck.setImageResource(
                if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
            )
            binding.photoCheck.alpha = if (isSelected) 1f else 0.85f
            bindTags(photo)
        }

        private fun bindTags(photo: Photo) {
            val views = arrayOf(binding.photoTag1, binding.photoTag2)
            val tags = binding.root.context.swiftPic.userData.tagsFor(photo)
            val visible = if (selectionMode) emptyList() else tags.take(2)
            binding.photoTags.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
            views.forEachIndexed { index, view ->
                val tag = visible.getOrNull(index)
                if (tag == null) {
                    view.visibility = View.GONE
                } else {
                    view.text = tag
                    view.visibility = View.VISIBLE
                }
            }
        }

        fun cancel() {
            job?.cancel()
            job = null
            hdrJob?.cancel()
            hdrJob = null
            liveJob?.cancel()
            liveJob = null
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_PHOTO = 1
        const val PAYLOAD_SELECTION = "selection"
    }
}
