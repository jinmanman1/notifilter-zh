package com.wxmsglog

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * 待办列表。分两段：未完成、已完成。
 *
 * 点和长按分别对应「编辑」和「删除」，圆圈按钮切换完成状态——
 * 这样勾一项只要一次点击，不用先进详情页。
 */
class TodoAdapter(
    private val onToggle: (Todo) -> Unit,
    private val onEdit: (Todo) -> Unit,
    private val onDelete: (Todo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class TodoRow {
        class Header(val done: Boolean, val count: Int) : TodoRow()
        class Item(val todo: Todo) : TodoRow()
    }

    private val rows = ArrayList<TodoRow>()

    fun submit(list: List<Todo>) {
        rows.clear()
        val open = list.filter { !it.done }
        val done = list.filter { it.done }
        if (open.isNotEmpty()) {
            rows.add(TodoRow.Header(false, open.size))
            for (t in open) rows.add(TodoRow.Item(t))
        }
        if (done.isNotEmpty()) {
            rows.add(TodoRow.Header(true, done.size))
            for (t in done) rows.add(TodoRow.Item(t))
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is TodoRow.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inf.inflate(R.layout.item_todo_header, parent, false))
        } else {
            ItemVH(inf.inflate(R.layout.item_todo, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is TodoRow.Header -> (holder as HeaderVH).bind(r)
            is TodoRow.Item -> (holder as ItemVH).bind(r.todo)
        }
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvName: TextView = v.findViewById(R.id.tvName)
        private val tvCount: TextView = v.findViewById(R.id.tvCount)

        fun bind(h: TodoRow.Header) {
            tvName.setText(if (h.done) R.string.todo_sec_done else R.string.todo_sec_open)
            tvCount.text = itemView.context.getString(R.string.todo_sec_count, h.count)
        }
    }

    private inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvText: TextView = v.findViewById(R.id.tvText)
        private val tvTime: TextView = v.findViewById(R.id.tvTime)
        private val btnCheck: ImageButton = v.findViewById(R.id.btnCheck)

        fun bind(t: Todo) {
            val ctx = itemView.context
            val flags = tvText.paintFlags
            tvText.text = t.text
            if (t.done) {
                tvText.paintFlags = flags or Paint.STRIKE_THRU_TEXT_FLAG
                tvText.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            } else {
                tvText.paintFlags = flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                tvText.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            }
            btnCheck.setImageResource(
                if (t.done) R.drawable.ic_check_on else R.drawable.ic_check_off
            )

            if (t.due > 0L && !t.done) {
                tvTime.visibility = View.VISIBLE
                tvTime.text = TimeFmt.due(t.due)
                // 过期的用红色，一眼能看出哪些已经错过了
                val late = t.due < System.currentTimeMillis()
                tvTime.setTextColor(
                    ContextCompat.getColor(ctx, if (late) R.color.danger else R.color.hint)
                )
            } else {
                tvTime.visibility = View.GONE
            }

            btnCheck.setOnClickListener { onToggle(t) }
            itemView.setOnClickListener { onEdit(t) }
            itemView.setOnLongClickListener {
                onDelete(t)
                true
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }
}
