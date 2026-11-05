package com.qoder.swiftpic.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.qoder.swiftpic.R
import com.qoder.swiftpic.data.Photo
import com.qoder.swiftpic.data.ThumbnailLoader
import com.qoder.swiftpic.databinding.ItemAlbumBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DestinationAdapter(
    private val scope: CoroutineScope,
    private val thumbnails: ThumbnailLoader,
    private val onClick: (DestinationAdapter.Target) -> Unit
) : RecyclerView.Adapter<DestinationAdapter.Holder>() {

    data class Target(
        val name: String,
        val relativePath: String,
        val volume: String,
        val count: Int,
        val cover: Photo?,
        val key: String
    )

    private val items = ArrayList<Target>()

    fun submit(targets: List<Target>) {
        items.clear()
        items.addAll(targets)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAlbumBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: Holder) {
        holder.cancel()
        super.onViewRecycled(holder)
    }

    inner class Holder(
        private val binding: ItemAlbumBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var job: Job? = null

        fun bind(target: Target) {
            val context = binding.root.context
            binding.albumName.text = target.name
            binding.albumSubtitle.text = target.relativePath.ifBlank { "/" }
            binding.albumCount.text = context.getString(R.string.photo_count, target.count)
            binding.pinnedBadge.visibility = View.GONE
            binding.albumTags.visibility = View.GONE
            binding.albumStack.configure(0)
            binding.root.contentDescription = target.name
            binding.root.setOnClickListener { onClick(target) }
            binding.root.setOnLongClickListener(null)

            binding.albumCover.setImageDrawable(null)
            val cover = target.cover
            val tag = cover?.uri?.toString()
            binding.albumCover.tag = tag
            job?.cancel()
            job = null
            if (cover == null) return
            thumbnails.peek(cover, 320)?.let {
                binding.albumCover.setImageBitmap(it)
                return
            }
            job = scope.launch {
                val bitmap = thumbnails.load(cover, 320)
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
}
