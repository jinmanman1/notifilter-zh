package com.wxmsglog

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * 日报 / 周报。
 *
 * 报告是先落库再展示的：生成一次要几十秒，不能让用户停在页面上干等。
 * 所以「生成」按钮只负责把任务丢给服务，报告出来之后进这一页就能看到；
 * 定时任务生成的报告也走同一条路，不需要另外一套逻辑。
 */
class ReportActivity : AppCompatActivity() {

    private lateinit var db: MessageDb
    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tvEmpty: TextView
    private lateinit var btnDay: TextView
    private lateinit var btnWeek: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var kind = "day"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_report)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), null)

        db = MessageDb.get(this)
        container = findViewById(R.id.container)
        scroll = findViewById(R.id.scroll)
        tvEmpty = findViewById(R.id.tvReportEmpty)
        btnDay = findViewById(R.id.btnTabDay)
        btnWeek = findViewById(R.id.btnTabWeek)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnGen).setOnClickListener { generate() }
        btnDay.setOnClickListener { switchTo("day") }
        btnWeek.setOnClickListener { switchTo("week") }

        renderTabs()
        load()
    }

    override fun onResume() {
        super.onResume()
        // 离开太久就退回主界面，由它统一弹验证
        if (Ui.closeIfLocked(this)) return
        load()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 页签 ----------

    private fun switchTo(k: String) {
        if (kind == k) return
        kind = k
        renderTabs()
        load()
    }

    private fun renderTabs() {
        val onBg = R.drawable.bg_chip_on
        val offBg = R.drawable.bg_chip
        btnDay.setBackgroundResource(if (kind == "day") onBg else offBg)
        btnWeek.setBackgroundResource(if (kind == "week") onBg else offBg)
        btnDay.setTextColor(getColor(if (kind == "day") R.color.primary else R.color.text_secondary))
        btnWeek.setTextColor(getColor(if (kind == "week") R.color.primary else R.color.text_secondary))
    }

    // ---------- 列表 ----------

    private fun load() {
        val k = kind
        io.execute {
            val all = runCatching { db.reports() }.getOrDefault(emptyList())
            val list = all.filter { it.kind == k }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                render(list)
            }
        }
    }

    private fun render(list: List<Report>) {
        container.removeAllViews()
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        for (r in list) {
            container.addView(card(r))
        }
    }

    private fun card(r: Report): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { showFull(r) }
            setOnLongClickListener {
                confirmDelete(r)
                true
            }
        }

        val title = TextView(this).apply {
            text = rangeText(r)
            setTextColor(getColor(R.color.primary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        box.addView(title)

        val preview = TextView(this).apply {
            // 只露前几行，点开看全文。列表里塞整篇会很难翻
            val lines = r.body.split('\n').filter { it.isNotBlank() }.take(4).joinToString("\n")
            text = lines
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1.25f)
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(preview)

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(box)
        }
        val divider = View(this).apply { setBackgroundColor(getColor(R.color.divider)) }
        wrap.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        return wrap
    }

    private fun showFull(r: Report) {
        val tv = TextView(this).apply {
            text = r.body
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1.35f)
            setPadding(dp(20), dp(16), dp(20), dp(8))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(rangeText(r))
            .setView(sv)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun confirmDelete(r: Report) {
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setMessage(R.string.report_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                io.execute {
                    runCatching { db.deleteReport(r.id) }
                    ui.post { load() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 生成 ----------

    private fun generate() {
        if (ModelStore.current(this) == null) {
            // 没有模型也能出统计报告，这里不拦，只是提醒一句
            toast(getString(R.string.report_need_model_hint))
        }
        val action = if (kind == "week") AiService.ACTION_REPORT_WEEK else AiService.ACTION_REPORT_DAY
        AiService.start(this, action)
        toast(getString(R.string.ai_scan_started))
        // 生成在服务里跑，回来时会重新加载，这里稍等一下再刷一次
        ui.postDelayed({ if (!isFinishing && !isDestroyed) load() }, 4000)
    }

    // ---------- 小工具 ----------

    private fun rangeText(r: Report): String {
        val zone = ZoneId.systemDefault()
        val f = Instant.ofEpochMilli(r.from).atZone(zone).toLocalDate()
        val t = Instant.ofEpochMilli(r.to).atZone(zone).toLocalDate().minusDays(1)
        return if (r.isWeek) {
            f.format(DateTimeFormatter.ofPattern("M月d日")) + " — " +
                t.format(DateTimeFormatter.ofPattern("M月d日"))
        } else {
            f.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
