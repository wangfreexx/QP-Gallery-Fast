package com.qoder.swiftpic.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.HdrDetector
import com.qoder.swiftpic.data.LiveDetector
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.data.ThumbnailLoader
import com.qoder.swiftpic.data.TransparencyBackdrop
import com.qoder.swiftpic.databinding.ItemPhotoBinding
import com.qoder.swiftpic.databinding.ItemSectionBinding
import com.qoder.swiftpic.swiftPic
import com.qoder.swiftpic.ui.widget.CheckerPattern
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

    var coverKey: String? = null

    private val items = ArrayList<PhotoGridItem>()
    private val selected = LinkedHashSet<String>()

    // submit 时重建，供 notifyPhotoChanged / adapterPositionOfPhoto / selectedPhotos O(1) 查询。
    private val keyToPosition = HashMap<String, Int>()
    private val photoIndexToPosition = SparseIntArray()
    private val keyToPhoto = HashMap<String, Photo>()
    private val dayLabelCache = HashMap<Long, String>()

    // 主题颜色与棋盘格背景在所有行之间共享，避免每次 bind 重新解析/创建。
    private var surfaceColor = 0
    private var surfaceResolved = false
    private var checkerDarkDrawable: Drawable? = null
    private var checkerLightDrawable: Drawable? = null

    var selectionMode: Boolean = false
        private set

    var onSelectionChanged: (() -> Unit)? = null

    fun submit(photos: List<Photo>, withDateHeaders: Boolean) {
        val newItems = ArrayList<PhotoGridItem>(photos.size + 16)
        var lastDay: String? = null
        photos.forEachIndexed { index, photo ->
            if (withDateHeaders) {
                // 按「天」缓存标签：同一天的成百上千张照片只算一次 dayLabel。
                val dayKey = photo.sortTimeMs / DAY_MS
                val day = dayLabelCache.getOrPut(dayKey) { Formats.dayLabel(photo.sortTimeMs) }
                if (day != lastDay) {
                    newItems.add(PhotoGridItem.Header(day))
                    lastDay = day
                }
            }
            newItems.add(PhotoGridItem.PhotoRow(photo, index))
        }

        val keys = photos.mapTo(HashSet(photos.size)) { it.stableKey }
        selected.retainAll(keys)

        val oldItems = ArrayList(items)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val a = oldItems[oldItemPosition]
                val b = newItems[newItemPosition]
                return when {
                    a is PhotoGridItem.Header && b is PhotoGridItem.Header -> a.label == b.label
                    a is PhotoGridItem.PhotoRow && b is PhotoGridItem.PhotoRow ->
                        a.photo.stableKey == b.photo.stableKey
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition] == newItems[newItemPosition]
        })

        items.clear()
        items.addAll(newItems)
        rebuildMaps()
        diff.dispatchUpdatesTo(this)
        notifySelectionChanged()
    }

    private fun rebuildMaps() {
        keyToPosition.clear()
        photoIndexToPosition.clear()
        keyToPhoto.clear()
        for (i in items.indices) {
            val row = items[i] as? PhotoGridItem.PhotoRow ?: continue
            val key = row.photo.stableKey
            keyToPosition[key] = i
            photoIndexToPosition.put(row.photoIndex, i)
            keyToPhoto[key] = row.photo
        }
    }

    fun itemAt(position: Int): PhotoGridItem = items[position]

    fun adapterPositionOfPhoto(photoIndex: Int): Int =
        photoIndexToPosition.get(photoIndex, RecyclerView.NO_POSITION)

    /** stableKey 对应的 adapter position，供封面等局部刷新使用。 */
    fun positionOfKey(key: String): Int = keyToPosition[key] ?: RecyclerView.NO_POSITION

    fun isSelected(photo: Photo): Boolean = selected.contains(photo.stableKey)

    fun selectionCount(): Int = selected.size

    /** 直接返回 Adapter 维护的选中集合，避免 Activity 侧每次全量 filter。 */
    fun selectedPhotos(): List<Photo> = selected.mapNotNull { keyToPhoto[it] }

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selected.clear()
        notifyPhotoRows(PAYLOAD_SELECTION)
        notifySelectionChanged()
    }

    fun toggleSelection(photo: Photo) {
        if (selected.contains(photo.stableKey)) selected.remove(photo.stableKey)
        else selected.add(photo.stableKey)
        notifySelectionChanged()
        notifyPhotoChanged(photo)
    }

    fun selectAll() {
        selected.clear()
        for (item in items) {
            if (item is PhotoGridItem.PhotoRow) selected.add(item.photo.stableKey)
        }
        notifyPhotoRows(PAYLOAD_SELECTION)
        notifySelectionChanged()
    }

    fun clearSelection() {
        selected.clear()
        notifyPhotoRows(PAYLOAD_SELECTION)
        notifySelectionChanged()
    }

    /** 封面角标发生变化：逐行局部刷新。 */
    fun notifyCoversChanged() = notifyPhotoRows(PAYLOAD_COVER)

    /** 背景（透明棋盘格/主题色）或媒体标记发生变化：逐行局部刷新。 */
    fun notifyMediaChanged() = notifyPhotoRows(PAYLOAD_MEDIA)

    private fun notifyPhotoRows(payload: Any) {
        for (i in items.indices) {
            if (items[i] is PhotoGridItem.PhotoRow) notifyItemChanged(i, payload)
        }
    }

    private fun notifySelectionChanged() {
        onSelectionChanged?.invoke()
    }

    fun notifyPhotoChanged(photo: Photo) {
        val position = keyToPosition[photo.stableKey] ?: return
        notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    private fun resolveSurfaceColor(view: View): Int {
        if (!surfaceResolved) {
            surfaceColor = MaterialColors.getColor(
                view,
                com.google.android.material.R.attr.colorSurfaceVariant
            )
            surfaceResolved = true
        }
        return surfaceColor
    }

    private fun checkerBackdrop(context: Context, dark: Boolean): Drawable {
        val cached = if (dark) checkerDarkDrawable else checkerLightDrawable
        if (cached != null) return cached
        val created = CheckerPattern.drawable(context, dark)
        if (dark) checkerDarkDrawable = created else checkerLightDrawable = created
        return created
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
        if (payloads.isEmpty() || item !is PhotoGridItem.PhotoRow || holder !is PhotoHolder) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        // 未知 payload 一律回退全量绑定，避免漏刷任何装饰。
        if (payloads.any { it != PAYLOAD_SELECTION && it != PAYLOAD_COVER && it != PAYLOAD_MEDIA }) {
            holder.bind(item)
            return
        }
        payloads.forEach { payload ->
            when (payload) {
                PAYLOAD_SELECTION -> holder.bindSelection(item)
                PAYLOAD_COVER -> holder.bindCoverBadge(item.photo)
                PAYLOAD_MEDIA -> holder.bindMedia(item)
                else -> Unit
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? PhotoHolder)?.cancel()
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        (holder as? PhotoHolder)?.cancel()
        super.onViewDetachedFromWindow(holder)
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
        private val tagViews = arrayOf(binding.photoTag1, binding.photoTag2)

        fun bind(item: PhotoGridItem.PhotoRow) {
            val photo = item.photo
            binding.root.contentDescription = photo.name
            binding.root.setOnClickListener {
                if (selectionMode) toggleSelection(photo) else onClick(item.photoIndex)
            }
            binding.root.setOnLongClickListener {
                if (!selectionMode) setSelectionMode(true)
                if (!isSelected(photo)) toggleSelection(photo)
                true
            }
            bindSelection(item)
            bindCoverBadge(photo)
            bindMedia(item)
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

        fun bindCoverBadge(photo: Photo) {
            binding.photoCoverBadge.visibility =
                if (coverKey != null && photo.stableKey == coverKey) View.VISIBLE else View.GONE
        }

        /** 背景、GIF/Live/HDR 标记与缩略图：PAYLOAD_MEDIA 走这里局部重刷。 */
        fun bindMedia(item: PhotoGridItem.PhotoRow) {
            val photo = item.photo
            val context = binding.root.context
            val tag = photo.uri.toString()
            binding.photoImage.setImageDrawable(null)
            binding.photoImage.tag = tag

            val checker = context.swiftPic.prefs.transparencyBackdrop ==
                TransparencyBackdrop.CHECKER
            val surface = resolveSurfaceColor(binding.photoImage)
            if (checker) {
                binding.photoImage.background = checkerBackdrop(
                    context,
                    dark = !MaterialColors.isColorLight(surface)
                )
            } else {
                binding.photoImage.setBackgroundColor(surface)
            }

            cancel()
            binding.photoBadge.visibility = View.GONE
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

            thumbnails.peek(photo, preserveAlpha = checker)?.let {
                binding.photoImage.setImageBitmap(it)
                return
            }

            job = scope.launch {
                val bitmap = thumbnails.load(photo, preserveAlpha = checker)
                if (bitmap != null && binding.photoImage.tag == tag) {
                    binding.photoImage.setImageBitmap(bitmap)
                }
            }
        }

        private fun bindTags(photo: Photo) {
            val tags = binding.root.context.swiftPic.userData.tagsFor(photo)
            val visible = if (selectionMode) emptyList() else tags.take(2)
            binding.photoTags.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
            tagViews.forEachIndexed { index, view ->
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

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_PHOTO = 1
        const val PAYLOAD_SELECTION = "selection"
        const val PAYLOAD_COVER = "cover"
        const val PAYLOAD_MEDIA = "media"
        private const val DAY_MS = 86_400_000L
    }
}
