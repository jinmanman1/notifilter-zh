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
        refresh()
        updateWarn()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
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
            adapter.submit(list)
            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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
    }
}
