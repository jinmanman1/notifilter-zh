package com.wxmsglog

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** 列表里的一行：要么是分组标题，要么是一条消息。 */
sealed class Row {
    /**
     * @param title    标题文字（应用名 或 发信人）
     * @param iconPkg  用哪个应用的图标，null 表示不显示图标
     * @param kind     这一组是按应用分的还是按人分的，决定「复制」怎么取数据
     * @param value    对应的 pkg 或 sender
     */
    class Header(
        val title: String,
        val count: Int,
        val iconPkg: String?,
        val kind: Kind,
        val value: String,
    ) : Row() {
        enum class Kind { APP, SENDER }
    }

    class Msg(val message: Message) : Row()
}

/**
 * 分组列表。两种分组方式（按应用 / 按人）共用这一个 adapter，
 * 差别只在标题怎么来。
 *
 * 用 notifyDataSetChanged 而不是 DiffUtil——数据量在几百条，够用，
 * 也少一个容易在 CI 上出问题的依赖。
 */
class GroupedAdapter(
    private val onMessageClick: (Message) -> Unit,
    private val onMessageLongClick: (Message) -> Unit,
    private val onCopyGroup: (Row.Header) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = ArrayList<Row>()

    /** 多选状态由 Activity 持有，adapter 只负责画。 */
    private var selecting = false
    private var selected: Set<Long> = emptySet()

    /** 按人分组时标题已经是发信人，行内就不用再重复一遍。 */
    private var hideSender = false

    /** 当前搜索词。列表里把命中的片段标出来，不然几百条里根本看不出命中在哪。 */
    private var highlights: List<String> = emptyList()

    fun submit(
        list: List<Row>,
        selectingNow: Boolean,
        selectedIds: Set<Long>,
        keywords: List<String> = emptyList(),
    ) {
        rows.clear()
        rows.addAll(list)
        selecting = selectingNow
        selected = selectedIds
        highlights = keywords
        notifyDataSetChanged()
    }

    fun setGroupMode(hideSenderName: Boolean) {
        hideSender = hideSenderName
    }

    fun updateSelection(selectingNow: Boolean, selectedIds: Set<Long>) {
        selecting = selectingNow
        selected = selectedIds
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_MSG

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inf.inflate(R.layout.item_header, parent, false))
        } else {
            MsgVH(inf.inflate(R.layout.item_message, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(r)
            is Row.Msg -> (holder as MsgVH).bind(r.message)
        }
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {

        private val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val tvCount: TextView = view.findViewById(R.id.tvCount)
        private val tvCopy: TextView = view.findViewById(R.id.tvCopy)

        fun bind(h: Row.Header) {
            val ctx = itemView.context
            tvName.text = h.title
            tvCount.text = ctx.getString(R.string.group_count, h.count)
            val icon = h.iconPkg?.let { AppInfo.icon(ctx, it) }
            if (icon == null) {
                ivIcon.visibility = View.GONE
            } else {
                ivIcon.visibility = View.VISIBLE
                ivIcon.setImageDrawable(icon)
            }
            // 多选时整组复制容易误操作，先收起来
            tvCopy.visibility = if (selecting) View.GONE else View.VISIBLE
            tvCopy.setOnClickListener { onCopyGroup(h) }
        }
    }

    inner class MsgVH(view: View) : RecyclerView.ViewHolder(view) {

        private val tvSource: TextView = view.findViewById(R.id.tvSource)
        private val tvSender: TextView = view.findViewById(R.id.tvSender)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvBody: TextView = view.findViewById(R.id.tvBody)

        fun bind(m: Message) {
            val ctx = itemView.context
            val bg = ContextCompat.getColor(ctx, R.color.hl_bg)
            tvSource.visibility = View.GONE
            tvSender.text = mark(m.sender, bg)
            tvSender.visibility = if (hideSender) View.GONE else View.VISIBLE
            tvTime.text = TimeFmt.friendly(m.ts)
            tvBody.text = mark(m.body, bg)

            val picked = selected.contains(m.id)
            itemView.setBackgroundColor(
                if (picked) ContextCompat.getColor(ctx, R.color.sel_bg) else 0
            )
            // 多选状态下打勾用时间那一栏改画勾，位置固定，不会让标题跳来跳去
            tvTime.text = if (selecting) {
                if (picked) "✓ 已选" else TimeFmt.friendly(m.ts)
            } else {
                TimeFmt.friendly(m.ts)
            }
            if (selecting && picked) {
                tvTime.setTextColor(ContextCompat.getColor(ctx, R.color.primary))
            } else {
                tvTime.setTextColor(ContextCompat.getColor(ctx, R.color.hint))
            }

            itemView.setOnClickListener { onMessageClick(m) }
            itemView.setOnLongClickListener {
                onMessageLongClick(m)
                true
            }
        }
    }

    /**
     * 给命中的片段套一层半透明背景。
     * 只改背景不改文字颜色：黑底上如果改字色，很容易配出看不清的组合。
     */
    private fun mark(src: String, bg: Int): CharSequence {
        if (highlights.isEmpty() || src.isEmpty()) return src
        val sp = SpannableString(src)
        for (k in highlights) {
            if (k.isEmpty()) continue
            var i = src.indexOf(k, 0, ignoreCase = true)
            while (i >= 0) {
                sp.setSpan(
                    BackgroundColorSpan(bg),
                    i,
                    i + k.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                i = src.indexOf(k, i + k.length, ignoreCase = true)
            }
        }
        return sp
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MSG = 1
    }
}
