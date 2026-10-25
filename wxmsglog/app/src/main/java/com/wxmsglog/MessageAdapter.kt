package com.wxmsglog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MessageAdapter(
    private val onClick: (Message) -> Unit,
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = ArrayList<Message>()

    fun submit(list: List<Message>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {

        private val tvSender: TextView = view.findViewById(R.id.tvSender)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvBody: TextView = view.findViewById(R.id.tvBody)

        fun bind(m: Message) {
            tvSender.text = m.sender
            tvTime.text = FMT.format(Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()))
            tvBody.text = m.body
            itemView.setOnClickListener { onClick(m) }
        }
    }

    private companion object {
        val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}
