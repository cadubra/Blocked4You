package com.blockyou.firestick

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class HomeRow(val title: String, val items: List<StreamInfoItem>)

/** Lista vertical de fileiras da Home; cada fileira é uma lista horizontal de vídeos. */
class RowAdapter(
    private val onClick: (StreamInfoItem) -> Unit,
    private val itemWidthPx: Int,
) : RecyclerView.Adapter<RowAdapter.Holder>() {
    private val rows = mutableListOf<HomeRow>()
    private val sharedPool = RecyclerView.RecycledViewPool()

    fun submit(newRows: List<HomeRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    fun insert(position: Int, row: HomeRow) {
        rows.add(position, row)
        notifyItemInserted(position)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.row_title)
        private val list: RecyclerView = view.findViewById(R.id.row_list)
        private val adapter = VideoAdapter(onClick = onClick, itemWidthPx = itemWidthPx)

        init {
            list.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            list.setRecycledViewPool(sharedPool)
            list.adapter = adapter
        }

        fun bind(row: HomeRow) {
            title.text = row.title
            adapter.submit(row.items)
            list.scrollToPosition(0)
        }
    }
}
