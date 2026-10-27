package com.wxmsglog

import android.content.Intent
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
import java.util.concurrent.Executors

/**
 * 待办候选页。
 *
 * 从消息页选中若干条 → 这里按规则挑出可能要做的事 → 勾选 → 落成待办。
 * 全程不联网、不过模型：认得出的时间和祈使词才列出来，认不出就留空，
 * 让你在长按里自己补。
 */
class TodoPickActivity : AppCompatActivity() {

    private lateinit var db: MessageDb
    private lateinit var adapter: TodoPickAdapter
    private lateinit var tvCount: TextView
    private lateinit var btnAdd: TextView
    private lateinit var btnAll: TextView
    private lateinit var btnAi: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvEmpty: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var cands: List<TodoExtract.Cand> = emptyList()

    /** 抽不出候选时，把这些消息原样拼起来给用户手改，别让人白跑一趟。 */
    private var msgs: List<Message> = emptyList()

    private var aiRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁着的状态下从最近任务直接进来，直接退出去让主界面验证
        if (Ui.closeIfLocked(this)) return
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_todo_pick)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), findViewById(R.id.pickBottom))

        db = MessageDb.get(this)
        tvCount = findViewById(R.id.tvPickCount)
        btnAdd = findViewById(R.id.btnPickAdd)
        btnAll = findViewById(R.id.btnPickAll)
        btnAi = findViewById(R.id.btnPickAi)
        tvHint = findViewById(R.id.tvPickHint)
        tvEmpty = findViewById(R.id.tvPickEmpty)

        // 装了模型、并且用户在设置里打开了，才露出这个入口。
        // 默认不显示：没模型时它点不动，摆在那儿只会让人以为坏了。
        val aiReady = ModelStore.current(this) != null && Prefs.aiTodoEnabled(this)
        btnAi.visibility = if (aiReady) View.VISIBLE else View.GONE

        adapter = TodoPickAdapter(
            this,
            { i -> toggle(i) },
            { i -> editTime(i) },
        )
        val rv = findViewById<RecyclerView>(R.id.rvCand)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        btnAll.setOnClickListener { toggleAll() }
        btnAdd.setOnClickListener { save() }
        btnAi.setOnClickListener { aiScan() }
        tvEmpty.setOnClickListener { manual() }

        load()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 读消息 + 抽取 ----------

    private fun load() {
        val ids = intent?.getLongArrayExtra(EXTRA_IDS)?.toList().orEmpty()
        io.execute {
            val found: List<Message> = if (ids.isEmpty()) emptyList() else db.queryByIds(ids)
            val hits: List<TodoExtract.Cand> =
                if (found.isEmpty()) emptyList() else TodoExtract.extract(found)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                msgs = found
                cands = hits
                adapter.submit(hits, System.currentTimeMillis())
                tvEmpty.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE
                render()
            }
        }
    }

    /** 抽不出候选时点空态：把选中的消息拼成一条，带到待办页自己改。 */
    private fun manual() {
        if (msgs.isEmpty()) return
        val text = msgs.sortedBy { it.ts }
            .joinToString("\n") { m ->
                if (m.sender.isEmpty()) m.body else "${m.sender}: ${m.body}"
            }
            .trim()
        val i = Intent(this, TodoActivity::class.java)
        i.putExtra(TodoActivity.EXTRA_TEXT, text)
        startActivity(i)
        finish()
    }

    // ---------- AI 补漏 ----------

    /**
     * 让端侧模型再扫一遍规则漏掉的消息。
     *
     * 只跑「规则没抽出候选」的那些消息：规则已经认出好几条了，再让模型
     * 从头到尾跑一遍既慢又容易给出重复结果。一次最多跑 [AI_LIMIT] 条，
     * 单条 0.5~1 秒，再多用户就该以为卡死了。
     */
    private fun aiScan() {
        if (aiRunning) return
        val model = ModelStore.current(this)
        if (model == null) {
            toast(getString(R.string.model_ai_need_model))
            return
        }

        val known = cands.map { it.msgId }.toHashSet()
        val all = msgs.filter { it.id !in known && it.body.trim().length >= 4 }
        if (all.isEmpty()) {
            toast(getString(R.string.pick_ai_none))
            return
        }
        val todo = all.take(AI_LIMIT)
        if (all.size > AI_LIMIT) toast(getString(R.string.pick_ai_limit, AI_LIMIT))

        aiRunning = true
        btnAi.isEnabled = false
        btnAi.alpha = 0.4f
        // 「添加」必须一起锁住。save() 和这里共用同一个单线程池，AI 跑着的时候
        // 点添加只会排队等着，按钮变灰却半天没反应；更糟的是 AI 中途把新候选塞进
        // cands，等 save() 真正执行时会把用户没勾的东西一并存进去。
        btnAdd.isEnabled = false
        btnAdd.alpha = 0.4f
        btnAll.isEnabled = false
        tvHint.text = getString(R.string.pick_ai_loading)

        io.execute {
            // 第一次跑要先把模型映射进内存，1.3G 大概几秒
            val err = Llm.load(model)
            if (err != null) {
                ui.post {
                    if (isFinishing || isDestroyed) return@post
                    endAi()
                    toast(err)
                }
                return@execute
            }

            val now = System.currentTimeMillis()
            val extra = ArrayList<TodoExtract.Cand>()

            for ((i, m) in todo.withIndex()) {
                val step = i + 1
                ui.post {
                    if (!isFinishing && !isDestroyed) {
                        tvHint.text = getString(R.string.pick_ai_progress, step, todo.size)
                    }
                }
                if (isFinishing || isDestroyed) break

                // 把 now 显式传进去：一轮补漏里「当前时间」必须一致，
                // 否则每条提示词的时间戳都可能不同，KV 前缀在那一处断开，
                // native 层的复用就白做了
                val hit = LlmExtract.extract(this, m.body, m.sender, m.ts, now) ?: continue
                // 同一件事可能被拆成两句，按正文去重
                if (cands.any { it.text == hit.text }) continue
                if (extra.any { it.text == hit.text }) continue

                extra.add(
                    TodoExtract.Cand(
                        msgId = m.id,
                        text = hit.text,
                        due = hit.due,
                        past = hit.due in 1L until now,
                        sender = m.sender,
                        ts = m.ts,
                    )
                )
            }

            ui.post {
                if (isFinishing || isDestroyed) return@post
                endAi()
                if (extra.isEmpty()) {
                    toast(getString(R.string.pick_ai_zero))
                    return@post
                }
                cands = TodoExtract.sort(cands + extra)
                adapter.submit(cands, System.currentTimeMillis())
                tvEmpty.visibility = View.GONE
                render()
                toast(getString(R.string.pick_ai_done, extra.size))
            }
        }
    }

    private fun endAi() {
        aiRunning = false
        btnAi.isEnabled = true
        btnAi.alpha = 1f
        btnAll.isEnabled = true
        tvHint.text = getString(R.string.pick_hint)
        // 交给 render 决定「添加」该不该亮：它按当前勾选数算，比在这里猜准
        render()
    }

    // ---------- 界面状态 ----------

    private fun render() {
        val n = cands.count { it.picked }
        tvCount.text = getString(R.string.pick_count, n)
        btnAdd.isEnabled = n > 0
        btnAdd.alpha = if (n > 0) 1f else 0.4f
        val allOn = cands.isNotEmpty() && cands.all { it.picked }
        btnAll.setText(if (allOn) R.string.pick_none else R.string.pick_all)
    }

    private fun toggle(i: Int) {
        val c = cands.getOrNull(i) ?: return
        c.picked = !c.picked
        adapter.refreshCheck(i)
        render()
    }

    private fun toggleAll() {
        if (cands.isEmpty()) return
        val allOn = cands.all { it.picked }
        for (c in cands) c.picked = !allOn
        adapter.submit(cands, System.currentTimeMillis())
        render()
    }

    /** 长按改这条的截止时间。解析错了就地改，不用先加进去再翻回来。 */
    private fun editTime(i: Int) {
        val c = cands.getOrNull(i) ?: return
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(R.string.pick_edit_time)
            .setMessage(c.text)
            .setPositiveButton(R.string.pick_edit_pick) { _, _ ->
                TimePick.dateTime(this, c.due) { ts ->
                    c.due = ts
                    c.past = ts < System.currentTimeMillis()
                    c.picked = true
                    cands = TodoExtract.sort(cands)
                    adapter.submit(cands, System.currentTimeMillis())
                    render()
                }
            }
            .setNeutralButton(R.string.pick_edit_clear) { _, _ ->
                c.due = 0L
                c.past = false
                cands = TodoExtract.sort(cands)
                adapter.submit(cands, System.currentTimeMillis())
                render()
            }
            .setNegativeButton(R.string.todo_cancel, null)
            .show()
    }

    // ---------- 落库 ----------

    private fun save() {
        val chosen = cands.filter { it.picked }
        if (chosen.isEmpty()) {
            toast(getString(R.string.pick_none_picked))
            return
        }
        btnAdd.isEnabled = false
        val now = System.currentTimeMillis()
        io.execute {
            var added = 0
            for (c in chosen) {
                val id = db.addTodo(c.text, c.due, c.msgId)
                if (id > 0L) {
                    added++
                    // 过去的时间不排闹钟，Alarm.schedule 内部也会挡一次
                    if (c.due > now) Alarm.schedule(this, id, c.due)
                }
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                toast(getString(R.string.pick_added, added))
                startActivity(Intent(this, TodoActivity::class.java))
                finish()
            }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 选中消息的 id。 */
        const val EXTRA_IDS = "pick_ids"

        /** 一次最多让模型看这么多条，多了手机等不起。 */
        private const val AI_LIMIT = 20
    }
}
