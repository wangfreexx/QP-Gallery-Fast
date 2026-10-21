package com.qoder.swiftpic.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.Album
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.data.ThumbnailLoader
import com.qoder.swiftpic.databinding.ItemAlbumBannerBinding
import com.qoder.swiftpic.databinding.ItemAlbumBinding
import com.qoder.swiftpic.databinding.ItemAlbumListBinding
import com.qoder.swiftpic.databinding.ItemSectionBinding
import com.qoder.swiftpic.swiftPic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min

sealed class MainItem {
    data class Banner(val photo: Photo?, val count: Int) : MainItem()
    data class Section(val title: String) : MainItem()
    data class AlbumRow(
        val album: Album,
        val cover: Photo? = null,
        val pinned: Boolean = false
    ) : MainItem()
}

class MainAdapter(
    private val scope: CoroutineScope,
    private val thumbnails: ThumbnailLoader,
    private val onClick: (MainItem) -> Unit,
    private val onLongClick: (MainItem.AlbumRow) -> Unit,
    private val stackedCovers: Boolean,
    private val listMode: Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<MainItem>()

    fun submit(newItems: List<MainItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): MainItem = items[position]

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is MainItem.Banner -> TYPE_BANNER
        is MainItem.Section -> TYPE_SECTION
        is MainItem.AlbumRow -> if (listMode) TYPE_ALBUM_LIST else TYPE_ALBUM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BANNER -> BannerHolder(
                ItemAlbumBannerBinding.inflate(inflater, parent, false)
            )
            TYPE_SECTION -> SectionHolder(
                ItemSectionBinding.inflate(inflater, parent, false)
            )
            TYPE_ALBUM_LIST -> AlbumListHolder(
                ItemAlbumListBinding.inflate(inflater, parent, false)
            )
            else -> AlbumHolder(
                ItemAlbumBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is MainItem.Banner -> (holder as BannerHolder).bind(item)
            is MainItem.Section -> (holder as SectionHolder).bind(item)
            is MainItem.AlbumRow -> {
                if (holder is AlbumListHolder) holder.bind(item)
                else (holder as AlbumHolder).bind(item)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is BannerHolder -> holder.cancel()
            is AlbumHolder -> holder.cancel()
            is AlbumListHolder -> holder.cancel()
        }
        super.onViewRecycled(holder)
    }

    private inner class BannerHolder(
        private val binding: ItemAlbumBannerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var job: Job? = null

        fun bind(item: MainItem.Banner) {
            binding.bannerCount.text = binding.root.context.getString(R.string.photo_count, item.count)
            binding.bannerCard.contentDescription = binding.root.context.getString(R.string.all_photos)
            binding.bannerCard.setOnClickListener { onClick(item) }
            binding.bannerCover.setImageDrawable(null)
            val photo = item.photo
            binding.bannerCover.tag = photo?.uri?.toString()
            cancel()
            if (photo == null) return
            thumbnails.peek(photo, 256)?.let {
                binding.bannerCover.setImageBitmap(it)
                return
            }
            val tag = photo.uri.toString()
            job = scope.launch {
                val bitmap = thumbnails.load(photo, 256)
                if (bitmap != null && binding.bannerCover.tag == tag) {
                    binding.bannerCover.setImageBitmap(bitmap)
                }
            }
        }

        fun cancel() {
            job?.cancel()
            job = null
        }
    }

    private inner class SectionHolder(
        private val binding: ItemSectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MainItem.Section) {
            binding.sectionTitle.text = item.title
        }
    }

    private inner class AlbumHolder(
        private val binding: ItemAlbumBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val jobs = ArrayList<Job>(3)

        fun bind(item: MainItem.AlbumRow) {
            val context = binding.root.context
            val album = item.album
            binding.albumName.text = album.name
            binding.albumSubtitle.visibility = View.GONE
            binding.albumCount.text = context.getString(R.string.photo_count, album.count)
            binding.pinnedBadge.visibility = if (item.pinned) View.VISIBLE else View.GONE
            bindTags(album)
            binding.root.contentDescription = buildString {
                append(album.name)
                append("，")
                append(context.getString(R.string.photo_count, album.count))
                if (item.pinned) append("，").append(context.getString(R.string.pinned_badge))
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }

            val front = item.cover ?: album.cover
            val backs = ArrayList<Photo>(2)
            if (stackedCovers) {
                album.photos.forEach { photo ->
                    if (backs.size < 2 && photo.stableKey != front?.stableKey) {
                        backs.add(photo)
                    }
                }
            }
            val extra = min(2, backs.size)
            binding.albumStack.configure(extra)

            cancel()
            bindImage(binding.albumCover, front, 384)
            bindImage(binding.albumStackBack1, backs.getOrNull(0), 320)
            bindImage(binding.albumStackBack2, backs.getOrNull(1), 320)
        }

        private fun bindTags(album: Album) {
            val tags = binding.root.context.swiftPic.userData.albumTags(album.key).take(2)
            binding.albumTags.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
            val views = arrayOf(binding.albumTag1, binding.albumTag2)
            views.forEachIndexed { index, view ->
                val tag = tags.getOrNull(index)
                if (tag == null) {
                    view.visibility = View.GONE
                } else {
                    view.text = tag
                    view.visibility = View.VISIBLE
                }
            }
        }

        private fun bindImage(view: ImageView, photo: Photo?, size: Int) {
            view.setImageDrawable(null)
            view.tag = photo?.uri?.toString()
            if (photo == null) return
            thumbnails.peek(photo, size)?.let {
                view.setImageBitmap(it)
                return
            }
            val tag = photo.uri.toString()
            jobs.add(scope.launch {
                val bitmap = thumbnails.load(photo, size)
                if (bitmap != null && view.tag == tag) {
                    view.setImageBitmap(bitmap)
                }
            })
        }

        fun cancel() {
            jobs.forEach { it.cancel() }
            jobs.clear()
        }
    }

    private inner class AlbumListHolder(
        private val binding: ItemAlbumListBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var job: Job? = null

        fun bind(item: MainItem.AlbumRow) {
            val context = binding.root.context
            val album = item.album
            binding.albumName.text = album.name
            binding.albumSubtitle.text = context.getString(R.string.photo_count, album.count)
            binding.pinnedBadge.visibility = if (item.pinned) View.VISIBLE else View.GONE
            binding.root.contentDescription = buildString {
                append(album.name)
                append("，")
                append(context.getString(R.string.photo_count, album.count))
                if (item.pinned) append("，").append(context.getString(R.string.pinned_badge))
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
            binding.albumCover.setImageDrawable(null)
            val cover = item.cover ?: album.cover
            binding.albumCover.tag = cover?.uri?.toString()
            cancel()
            if (cover == null) return
            thumbnails.peek(cover, 256)?.let {
                binding.albumCover.setImageBitmap(it)
                return
            }
            val tag = cover.uri.toString()
            job = scope.launch {
                val bitmap = thumbnails.load(cover, 256)
                if (bitmap != null && binding.albumCover.tag == tag) {
                    binding.albumCover.setImageBitmap(bitmap)
                }
            }
        }

        fun cancel() {
            job?.cancel()
            job = null
        }
    }

    private companion object {
        const val TYPE_BANNER = 0
        const val TYPE_SECTION = 1
        const val TYPE_ALBUM = 2
        const val TYPE_ALBUM_LIST = 3
    }
}
