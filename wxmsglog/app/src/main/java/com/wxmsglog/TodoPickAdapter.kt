package com.wxmsglog

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * 待办候选列表。按「哪一天」分组，组内按时间升序，没识别到时间的单独放最后。
 *
 * 点一下整行切换勾选，长按改这一条的时间——解析错了要能就地修正，
 * 否则用户只能先加进去再进待办页改，太绕。
 */
class TodoPickAdapter(
    context: Context,
    private val onToggle: (Int) -> Unit,
    private val onEditTime: (Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val ctx: Context = context.applicationContext

    sealed class Row {
        class Header(val label: String, val count: Int) : Row()
        class Item(val index: Int, val cand: TodoExtract.Cand) : Row()
    }

    private val rows = ArrayList<Row>()

    fun submit(list: List<TodoExtract.Cand>, now: Long) {
        rows.clear()
        // 用 LinkedHashMap 保住顺序：extract 已经按时间排好，分组不能打乱它
        val groups = LinkedHashMap<String, MutableList<Int>>()
        for ((i, c) in list.withIndex()) {
            val label = if (c.due > 0L) {
                TimeFmt.dayLabel(c.due, now)
            } else {
                ctx.getString(R.string.pick_group_none)
            }
            groups.getOrPut(label) { ArrayList() }.add(i)
        }
        for ((label, idxs) in groups) {
            rows.add(Row.Header(label, idxs.size))
            for (i in idxs) rows.add(Row.Item(i, list[i]))
        }
        notifyDataSetChanged()
    }

    /** 勾选状态变了，只刷那一行，别整表重绑。 */
    fun refreshCheck(index: Int) {
        val pos = rows.indexOfFirst { it is Row.Item && it.index == index }
        if (pos >= 0) notifyItemChanged(pos)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inf.inflate(R.layout.item_todo_header, parent, false))
        } else {
            ItemVH(inf.inflate(R.layout.item_cand, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(r)
            is Row.Item -> (holder as ItemVH).bind(r)
        }
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvName: TextView = v.findViewById(R.id.tvName)
        private val tvCount: TextView = v.findViewById(R.id.tvCount)

        fun bind(h: Row.Header) {
            tvName.text = h.label
            tvCount.text = itemView.context.getString(R.string.pick_group_count, h.count)
        }
    }

    private inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvText: TextView = v.findViewById(R.id.tvCandText)
        private val tvMeta: TextView = v.findViewById(R.id.tvCandMeta)
        private val btnCheck: ImageButton = v.findViewById(R.id.btnCandCheck)

        fun bind(row: Row.Item) {
            val c = row.cand
            tvText.text = c.text
            btnCheck.setImageResource(
                if (c.picked) R.drawable.ic_check_on else R.drawable.ic_check_off
            )

            val head = if (c.due > 0L) TimeFmt.due(c.due) else ctx.getString(R.string.pick_no_time)
            val who = if (c.sender.isEmpty()) "" else c.sender + " "
            val sp = SpannableString(head + " · " + who + TimeFmt.friendly(c.ts))
            val color = when {
                c.due <= 0L -> R.color.hint
                c.past -> R.color.danger
                else -> R.color.primary
            }
            sp.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(ctx, color)),
                0,
                head.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            tvMeta.text = sp

            btnCheck.setOnClickListener { onToggle(row.index) }
            itemView.setOnClickListener { onToggle(row.index) }
            itemView.setOnLongClickListener {
                onEditTime(row.index)
                true
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }
}
