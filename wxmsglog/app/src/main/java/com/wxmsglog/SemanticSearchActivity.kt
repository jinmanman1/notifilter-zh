package com.wxmsglog

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * 语义搜索。
 *
 * 和主界面那个关键字搜索互补：那边比字面，这边比意思。
 * 搜「那个钱的事」，关键字一条都找不到，这里能翻出「报销还没交」。
 */
class SemanticSearchActivity : AppCompatActivity() {

    private lateinit var db: MessageDb
    private lateinit var etQuery: EditText
    private lateinit var tvStatus: TextView
    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var searching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_semantic)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), null)

        db = MessageDb.get(this)
        etQuery = findViewById(R.id.etQuery)
        tvStatus = findViewById(R.id.tvStatus)
        container = findViewById(R.id.container)
        scroll = findViewById(R.id.scroll)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { doSearch() }

        etQuery.setOnEditorActionListener { _, _, _ ->
            doSearch()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // 离开太久就退回主界面，由它统一弹验证
        if (Ui.closeIfLocked(this)) return
        // 每次回来都刷新状态：用户可能刚去设置页装了模型
        refreshStatus()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 状态 ----------

    private fun refreshStatus() {
        val hasModel = ModelStore.embedModel(this) != null
        val indexed = SemanticSearch.indexedCount(this)

        when {
            !hasModel -> {
                tvStatus.text = getString(R.string.vec_need_model)
                // 没有模型时点状态行直接跳去装
                tvStatus.setOnClickListener {
                    startActivity(Intent(this, ModelActivity::class.java))
                }
                tvStatus.setTextColor(getColor(R.color.warn_fg))
            }

            indexed == 0 -> {
                tvStatus.text = getString(R.string.vec_need_index)
                tvStatus.setTextColor(getColor(R.color.warn_fg))
                tvStatus.setOnClickListener { startIndex() }
            }

            else -> {
                tvStatus.text = getString(R.string.vec_ready, indexed)
                tvStatus.setTextColor(getColor(R.color.hint))
                tvStatus.setOnClickListener(null)
            }
        }
    }

    private fun startIndex() {
        if (ModelStore.embedModel(this) == null) {
            startActivity(Intent(this, ModelActivity::class.java))
            return
        }
        AiService.start(this, AiService.ACTION_INDEX)
        toast(getString(R.string.vec_index_started))
        // 索引是后台跑的，过几秒回来看看进度
        ui.postDelayed({ if (!isFinishing && !isDestroyed) refreshStatus() }, 6000)
    }

    // ---------- 搜索 ----------

    private fun doSearch() {
        if (searching) return
        val q = etQuery.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return

        if (ModelStore.embedModel(this) == null) {
            toast(getString(R.string.vec_need_model))
            return
        }

        searching = true
        tvStatus.text = getString(R.string.vec_searching)
        container.removeAllViews()

        io.execute {
            val hits = SemanticSearch.search(this, q, 30)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                searching = false
                render(hits, q)
            }
        }
    }

    private fun render(hits: List<SemanticSearch.Hit>, query: String) {
        container.removeAllViews()
        if (hits.isEmpty()) {
            tvStatus.text = getString(R.string.vec_no_hit, query)
            refreshStatusLater()
            return
        }
        tvStatus.text = getString(R.string.vec_hits, hits.size)
        for (h in hits) container.addView(card(h))
        scroll.post { scroll.scrollTo(0, 0) }
    }

    /** 搜完之后把状态行恢复成「可搜多少条」，不然一直停在「搜索中」。 */
    private fun refreshStatusLater() {
        ui.postDelayed({ if (!isFinishing && !isDestroyed) refreshStatus() }, 1200)
    }

    private fun card(h: SemanticSearch.Hit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { copy(h.msg) }
        }

        val head = TextView(this).apply {
            val score = (h.score * 100).toInt()
            val who = h.msg.sender.ifBlank { AppInfo.label(this@SemanticSearchActivity, h.msg.pkg) }
            text = "$score%  ·  ${timeText(h.msg.ts, this@SemanticSearchActivity)}  ·  $who"
            setTextColor(getColor(R.color.primary))
            textSize = 11f
        }
        box.addView(head)

        val body = TextView(this).apply {
            text = h.msg.body
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1.25f)
            setPadding(0, dp(5), 0, 0)
        }
        box.addView(body)

        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(box)
        wrap.addView(
            View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
        )
        return wrap
    }

    private fun copy(m: Message) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", m.body))
        toast(getString(R.string.vec_copied))
    }

    private fun timeText(ts: Long, ctx: android.content.Context): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
