package com.wxmsglog

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 列表里的一行：一个可被监听的应用。 */
data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
)

class AppPickerAdapter(
    private val selected: MutableSet<String>,
    private val onToggle: (AppEntry, Boolean) -> Unit,
) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

    private val items = ArrayList<AppEntry>()

    fun submit(list: List<AppEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {

        private val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val tvPkg: TextView = view.findViewById(R.id.tvPkg)
        private val cb: CheckBox = view.findViewById(R.id.cb)

        fun bind(e: AppEntry) {
            tvName.text = e.label
            tvPkg.text = e.pkg
            ivIcon.setImageDrawable(e.icon)

            // 先摘掉监听器，否则 setChecked 会回调一次，造成多余写入
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = selected.contains(e.pkg)
            cb.setOnCheckedChangeListener { _, checked -> onToggle(e, checked) }

            itemView.setOnClickListener { cb.isChecked = !cb.isChecked }
        }
    }
}
