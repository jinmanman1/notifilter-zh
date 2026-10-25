package com.wxmsglog

import android.app.DatePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * 历史待办。
 *
 * 主待办页只显示「还要做什么」，这一页回答「那天我记了什么」。
 * 按创建时间归到某一天——用户回忆时想的是「上周三」，不是截止日期。
 */
class TodoHistoryActivity : AppCompatActivity() {

    private lateinit var db: MessageDb
    private lateinit var adapter: TodoAdapter
    private lateinit var tvDate: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var date: LocalDate = LocalDate.now()
    private var items: List<Todo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁着的状态下从最近任务直接进来，直接退出去让主界面验证
        if (Ui.closeIfLocked(this)) return
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_todo_history)
        val rv = findViewById<RecyclerView>(R.id.rvHis)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), rv)

        db = MessageDb.get(this)
        tvDate = findViewById(R.id.tvDate)
        tvCount = findViewById(R.id.tvCount)
        tvEmpty = findViewById(R.id.tvHisEmpty)

        adapter = TodoAdapter(
            { t -> toggle(t) },
            { t -> edit(t) },
            { t -> confirmDelete(t) },
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.rowDate).setOnClickListener { pickDate() }

        load()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 数据 ----------

    private fun load() {
        tvDate.text = dateText(date)
        io.execute {
            val from = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val to = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val list = runCatching { db.todosBetween(from, to) }.getOrDefault(emptyList())
            ui.post {
                if (isFinishing || isDestroyed) return@post
                items = list
                adapter.submit(list)
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                tvCount.text = getString(
                    R.string.todo_history_count,
                    list.size,
                    list.count { it.done },
                )
            }
        }
    }

    private fun pickDate() {
        DatePickerDialog(
            this,
            { _, y, m, d ->
                date = LocalDate.of(y, m + 1, d)
                load()
            },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth,
        ).show()
    }

    // ---------- 操作 ----------

    private fun toggle(t: Todo) {
        val nowDone = !t.done
        io.execute {
            runCatching { db.setTodoDone(t.id, nowDone) }
            if (nowDone) {
                Alarm.cancel(this, t.id)
            } else if (t.due > System.currentTimeMillis()) {
                Alarm.schedule(this, t.id, t.due)
            }
            ui.post { load() }
        }
    }

    private fun edit(t: Todo) {
        val input = android.widget.EditText(this).apply {
            setText(t.text)
            setSingleLine(false)
            minLines = 2
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(R.string.todo_edit)
            .setView(input)
            .setPositiveButton(R.string.todo_save) { _, _ ->
                val v = input.text?.toString()?.trim().orEmpty()
                if (v.isEmpty()) {
                    toast(getString(R.string.todo_empty_text))
                    return@setPositiveButton
                }
                io.execute {
                    runCatching { db.updateTodo(t.id, v, t.due) }
                    ui.post { load() }
                }
            }
            .setNeutralButton(R.string.todo_delete) { _, _ -> confirmDelete(t) }
            .setNegativeButton(R.string.todo_cancel, null)
            .show()
    }

    private fun confirmDelete(t: Todo) {
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setMessage(getString(R.string.todo_delete_confirm, t.text))
            .setPositiveButton(R.string.delete) { _, _ ->
                io.execute {
                    runCatching {
                        db.deleteTodo(t.id)
                        Alarm.cancel(this, t.id)
                    }
                    ui.post { load() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 小工具 ----------

    private fun dateText(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> getString(R.string.todo_history_today)
            today.minusDays(1) -> "昨天"
            else -> d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
