package com.wxmsglog

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** 待办列表。新建 / 编辑 / 勾完成 / 删除都在这一页，提醒走 AlarmManager。 */
class TodoActivity : AppCompatActivity() {

    private lateinit var db: MessageDb
    private lateinit var adapter: TodoAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var warnBar: LinearLayout
    private lateinit var tvWarn: TextView
    private lateinit var btnWarn: Button
    private lateinit var searchRow: View
    private lateinit var etSearch: android.widget.EditText

    /** 全量待办。搜索只是在上面对一遍，不重新查库。 */
    private var allTodos: List<Todo> = emptyList()
    private var query: String = ""

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** 提示条上那个按钮该干什么，由 updateWarn 决定。 */
    private var warnAction: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_todo)
        val rv = findViewById<RecyclerView>(R.id.rvTodo)
        // 底部把 rv 传进去，让它的内边距顶住导航栏，否则最后一条会被压住
        Ui.edgeToEdge(this, findViewById(R.id.appBar), rv)

        db = MessageDb.get(this)
        tvEmpty = findViewById(R.id.tvTodoEmpty)
        warnBar = findViewById(R.id.todoWarn)
        tvWarn = findViewById(R.id.tvTodoWarn)
        btnWarn = findViewById(R.id.btnTodoPerm)

        searchRow = findViewById(R.id.searchRow)
        etSearch = findViewById(R.id.etTodoSearch)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        findViewById<View>(R.id.btnSearchClose).setOnClickListener {
            query = ""
            etSearch.setText("")
            etSearch.clearFocus()
            searchRow.visibility = View.GONE
        }

        adapter = TodoAdapter(
            { t -> toggle(t) },
            { t -> showEditor(t) },
            { t -> confirmDelete(t) },
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnAdd).setOnClickListener { showEditor(null) }
        btnWarn.setOnClickListener { warnAction() }
        findViewById<TextView>(R.id.btnAiScan).setOnClickListener { aiScan() }
        findViewById<TextView>(R.id.btnMore).setOnClickListener { anchor -> showMore(anchor) }

        TodoNotify.ensureChannel(this)
        // 闹钟不跨重启保留，每次进这一页都重排一遍，成本可以忽略
        Alarm.rescheduleAll(this, db)

        // 从消息页「待办」按钮跳过来的：直接开编辑框，内容已经拼好
        val preset = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        if (savedInstanceState == null && preset.isNotEmpty()) {
            showEditor(null, preset)
        }
    }

    override fun onResume() {
        super.onResume()
        // 离开太久就退回主界面，由它统一弹验证
        if (Ui.closeIfLocked(this)) return
        refresh()
        updateWarn()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- AI 整理 / 更多菜单 ----------

    /**
     * 让 AI 把上次扫过之后的新消息过一遍。
     *
     * 真正的活在 [AiService] 里跑：加载模型要 1.5G 内存、跑几十秒，
     * 在页面线程里做只会卡住界面，切走还会被系统回收。
     */
    private fun aiScan() {
        if (ModelStore.current(this) == null || !Llm.available()) {
            toast(getString(R.string.ai_scan_no_model))
            startActivity(Intent(this, ModelActivity::class.java))
            return
        }
        if (AiAggregate.backlog(this) <= 0) {
            toast(getString(R.string.ai_scan_no_new))
            return
        }
        AiService.start(this, AiService.ACTION_SCAN)
        toast(getString(R.string.ai_scan_started))
    }

    private fun showMore(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, MENU_SEARCH, 0, getString(R.string.todo_search))
        popup.menu.add(0, MENU_HISTORY, 1, getString(R.string.todo_history_title))
        popup.menu.add(0, MENU_REPORT, 2, getString(R.string.report_entry))
        val strong = if (Prefs.strongAlarm(this)) "✓ " else ""
        popup.menu.add(0, MENU_STRONG, 3, strong + getString(R.string.set_strong_alarm))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SEARCH -> {
                    searchRow.visibility = View.VISIBLE
                    etSearch.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }

                MENU_HISTORY -> startActivity(Intent(this, TodoHistoryActivity::class.java))
                MENU_REPORT -> startActivity(Intent(this, ReportActivity::class.java))
                MENU_STRONG -> {
                    val on = !Prefs.strongAlarm(this)
                    Prefs.setStrongAlarm(this, on)
                    toast(
                        getString(
                            if (on) R.string.set_strong_alarm_on else R.string.set_strong_alarm_off
                        )
                    )
                }
            }
            true
        }
        popup.show()
    }

    // ---------- 列表 ----------
    private fun refresh() {
        io.execute { load() }
    }

    /**
     * 真正的读库 + 刷界面。
     * 已经在 io 线程里的地方直接调它，别再往线程池塞一个任务——
     * 页面正在销毁时二次提交会被线程池拒绝，白白抛一个异常。
     */
    private fun load() {
        val list = db.todos()
        ui.post {
            if (isFinishing || isDestroyed) return@post
            allTodos = list
            applyFilter()
        }
    }

    /**
     * 按搜索词过滤。
     *
     * 搜索框收起时 query 是空串，等同于不过滤。不重新查库是因为待办本身
     * 不会太多（几百条封顶），在内存里过一遍比每次敲键盘都打一次 SQL 快。
     */
    private fun applyFilter() {
        val q = query.trim()
        val shown = if (q.isEmpty()) {
            allTodos
        } else {
            allTodos.filter { it.text.contains(q, ignoreCase = true) }
        }
        adapter.submit(shown)
        tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (shown.isEmpty() && q.isNotEmpty()) {
            tvEmpty.text = getString(R.string.todo_search_empty, q)
        } else if (shown.isEmpty()) {
            tvEmpty.text = getString(R.string.todo_empty)
        }
    }

    private fun toggle(t: Todo) {
        val nowDone = !t.done
        io.execute {
            db.setTodoDone(t.id, nowDone)
            if (nowDone) {
                // 勾完了就别再提醒
                Alarm.cancel(this, t.id)
            } else if (t.due > System.currentTimeMillis()) {
                // 撤销完成，时间还没到，把闹钟排回去
                Alarm.schedule(this, t.id, t.due)
            }
            load()
        }
    }

    // ---------- 新建 / 编辑 ----------

    private fun showEditor(t: Todo?, preset: String = "") {
        val box = LayoutInflater.from(this).inflate(R.layout.todo_edit, null)
        val et = box.findViewById<EditText>(R.id.etTodoText)
        val tvDue = box.findViewById<TextView>(R.id.tvTodoDue)
        val btnPick = box.findViewById<TextView>(R.id.btnPickDue)
        val btnClear = box.findViewById<TextView>(R.id.btnClearDue)

        var due = t?.due ?: 0L
        et.setText(if (t != null) t.text else preset)
        et.setSelection(et.text?.length ?: 0)

        val renderDue = {
            if (due > 0L) {
                tvDue.text = getString(R.string.todo_due_at, TimeFmt.due(due))
                tvDue.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                btnClear.visibility = View.VISIBLE
            } else {
                tvDue.setText(R.string.todo_due_none)
                tvDue.setTextColor(ContextCompat.getColor(this, R.color.hint))
                btnClear.visibility = View.GONE
            }
        }
        renderDue()

        btnPick.setOnClickListener {
            TimePick.dateTime(this, due) { picked ->
                due = picked
                renderDue()
            }
        }
        btnClear.setOnClickListener {
            due = 0L
            renderDue()
        }

        val d = AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(if (t == null) R.string.todo_new else R.string.todo_edit)
            .setView(box)
            .setPositiveButton(R.string.todo_save, null)
            .setNegativeButton(R.string.todo_cancel, null)
            .create()

        // 自己接管「保存」：内容为空时留在对话框里，而不是关掉再弹一句提示
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = et.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    toast(getString(R.string.todo_empty_text))
                    return@setOnClickListener
                }
                save(t, text, due)
                d.dismiss()
            }
        }
        d.show()
        et.requestFocus()
    }

    private fun save(t: Todo?, text: String, due: Long) {
        val isNew = t == null
        val id = t?.id ?: 0L
        io.execute {
            if (isNew) {
                val newId = db.addTodo(text, due, 0L)
                if (due > System.currentTimeMillis()) Alarm.schedule(this, newId, due)
            } else {
                // 时间可能改了，先把旧闹钟撤掉再按新时间排
                Alarm.cancel(this, id)
                db.updateTodo(id, text, due)
                val stillOpen = t != null && !t.done
                if (stillOpen && due > System.currentTimeMillis()) {
                    Alarm.schedule(this, id, due)
                }
            }
            load()
        }
    }

    private fun confirmDelete(t: Todo) {
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setMessage(getString(R.string.todo_delete_confirm, t.text))
            .setPositiveButton(R.string.todo_delete) { _, _ ->
                io.execute {
                    db.deleteTodo(t.id)
                    Alarm.cancel(this, t.id)
                    load()
                }
            }
            .setNegativeButton(R.string.todo_cancel, null)
            .show()
    }

    // ---------- 权限提示 ----------

    /**
     * 两个会让提醒静默失效的前提：通知权限没给、精确闹钟没授权。
     * 这两件事用户从界面上看不出来，发现不了就会以为应用坏了。
     */
    private fun updateWarn() {
        val noNotify = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val noExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()

        when {
            noNotify -> {
                warnBar.visibility = View.VISIBLE
                tvWarn.setText(R.string.todo_warn_notify)
                btnWarn.setText(R.string.todo_warn_notify_btn)
                warnAction = { requestNotify() }
            }
            noExact -> {
                warnBar.visibility = View.VISIBLE
                tvWarn.setText(R.string.todo_warn_exact)
                btnWarn.setText(R.string.btn_grant)
                warnAction = { openExactSettings() }
            }
            else -> warnBar.visibility = View.GONE
        }
    }

    private fun requestNotify() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) updateWarn()
    }

    private fun openExactSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val direct = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:$packageName"),
        )
        val opened = runCatching { startActivity(direct) }.isSuccess
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 从消息页带过来的预填内容。 */
        const val EXTRA_TEXT = "prefill_text"

        private const val REQ_NOTIFY = 201

        private const val MENU_SEARCH = 4
        private const val MENU_HISTORY = 1
        private const val MENU_REPORT = 2
        private const val MENU_STRONG = 3
    }
}
