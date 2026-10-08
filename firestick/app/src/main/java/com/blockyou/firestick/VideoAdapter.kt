package com.blockyou.firestick

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Shorts: marcados pelo extractor ou com URL no formato /shorts/. */
val StreamInfoItem.isShort: Boolean
    get() = isShortFormContent || url.contains("/shorts/")

val StreamInfoItem.isPlaylist: Boolean
    get() = url.contains("/playlist?list=")

class VideoAdapter(
    private val onClick: (StreamInfoItem) -> Unit,
    private val onNearEnd: () -> Unit = {},
    /** Largura fixa do cartão (fileiras horizontais); null = ocupa a coluna da grade. */
    private val itemWidthPx: Int? = null,
) : RecyclerView.Adapter<VideoAdapter.Holder>() {
    private val items = mutableListOf<StreamInfoItem>()

    fun submit(newItems: List<StreamInfoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun append(more: List<StreamInfoItem>) {
        val start = items.size
        items.addAll(more)
        notifyItemRangeInserted(start, more.size)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        itemWidthPx?.let { view.layoutParams.width = it }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
        if (position >= items.size - LOAD_MORE_THRESHOLD) onNearEnd()
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
        private val duration: TextView = view.findViewById(R.id.duration)
        private val title: TextView = view.findViewById(R.id.title)
        private val channel: TextView = view.findViewById(R.id.channel)

        init {
            view.setOnClickListener { onClick(items[bindingAdapterPosition]) }
            // Destaque do item focado pelo controle remoto
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }
        }

        fun bind(item: StreamInfoItem) {
            title.text = item.name
            channel.text = item.uploaderName
            duration.text = if (item.isPlaylist) {
                "▶ " + (item.shortDescription ?: "PLAYLIST") // ex.: "▶ 49 vídeos"
            } else {
                formatDuration(item.duration)
            }
            // Escolhe a miniatura mais próxima de 480px de largura
            val thumb = item.thumbnails.minByOrNull { kotlin.math.abs(it.width - 480) }
            ThumbnailLoader.load(thumb?.url, thumbnail)
        }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return LIVE_LABEL
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    companion object {
        private const val LOAD_MORE_THRESHOLD = 8
        private const val LIVE_LABEL = "AO VIVO"
    }
}
