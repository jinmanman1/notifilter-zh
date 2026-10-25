package com.wxmsglog

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Comparator
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.text.Collator

/**
 * 选择要监听哪些应用。
 *
 * 用 queryIntentActivities 拿「有桌面图标的 App」而不是 getInstalledApplications：
 * 后者会把几百个系统组件全列出来，用户根本找不到微信在哪。
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var adapter: AppPickerAdapter
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnToggleAll: TextView

    private var all: List<AppEntry> = emptyList()
    private val chosen: MutableSet<String> = HashSet()

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁着的状态下从最近任务直接进来，直接退出去让主界面验证
        if (Ui.closeIfLocked(this)) return
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_app_picker)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), null)

        etSearch = findViewById(R.id.etSearch)
        tvCount = findViewById(R.id.tvCount)
        tvEmpty = findViewById(R.id.tvEmpty)
        val rv = findViewById<RecyclerView>(R.id.rv)
        btnToggleAll = findViewById(R.id.btnToggleAll)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        chosen.addAll(Prefs.apps(this))

        adapter = AppPickerAdapter(chosen) { entry, checked ->
            if (checked) chosen.add(entry.pkg) else chosen.remove(entry.pkg)
            persist()
            updateCount()
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnToggleAll.setOnClickListener { toggleAllVisible() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        updateCount()
        load()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun persist() {
        Prefs.setApps(this, HashSet(chosen))
    }

    private fun updateCount() {
        val n = chosen.size
        tvCount.text = if (n == 0) {
            getString(R.string.apps_selected_none)
        } else {
            getString(R.string.apps_selected_n, n)
        }
    }

    private fun visibleList(): List<AppEntry> {
        val q = etSearch.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        if (q.isEmpty()) return all
        return all.filter {
            it.label.lowercase(Locale.getDefault()).contains(q) ||
                it.pkg.lowercase(Locale.getDefault()).contains(q)
        }
    }

    private fun applyFilter() {
        val list = visibleList()
        adapter.submit(list)
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        updateToggleAllLabel()
    }

    /** 按钮文字跟着当前筛选结果变：都勾了就是「清空」，否则「全选」。 */
    private fun updateToggleAllLabel() {
        val list = visibleList()
        val allSelected = list.isNotEmpty() && list.all { chosen.contains(it.pkg) }
        btnToggleAll.text = getString(
            if (allSelected) R.string.apps_clear_all else R.string.apps_select_all
        )
    }

    /** 全选 / 清空。只作用于当前筛选出来的这一批，搜索之后用起来更顺手。 */
    private fun toggleAllVisible() {
        val list = visibleList()
        if (list.isEmpty()) return
        val allSelected = list.all { chosen.contains(it.pkg) }
        if (allSelected) {
            for (e in list) chosen.remove(e.pkg)
        } else {
            for (e in list) chosen.add(e.pkg)
        }
        persist()
        adapter.notifyDataSetChanged()
        updateCount()
        updateToggleAllLabel()
    }

    private fun load() {
        io.execute {
            val pm = packageManager
            val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = try {
                pm.queryIntentActivities(probe, 0)
            } catch (t: Throwable) {
                emptyList()
            }
            val map = LinkedHashMap<String, AppEntry>()
            for (ri in resolved) {
                val info = ri.activityInfo ?: continue
                val pkg = info.packageName ?: continue
                if (pkg == packageName) continue
                if (map.containsKey(pkg)) continue
                val label = try {
                    ri.loadLabel(pm).toString()
                } catch (t: Throwable) {
                    pkg
                }
                val icon = try {
                    ri.loadIcon(pm)
                } catch (t: Throwable) {
                    null
                }
                map[pkg] = AppEntry(pkg, label, icon)
            }
            val collator = Collator.getInstance(Locale.CHINA)
            val sorted = map.values.sortedWith(
                Comparator { a, b -> collator.compare(a.label, b.label) }
            )
            ui.post {
                all = sorted
                applyFilter()
            }
        }
    }
}
