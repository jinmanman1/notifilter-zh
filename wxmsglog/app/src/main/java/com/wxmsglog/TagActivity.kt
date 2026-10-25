package com.wxmsglog

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
 * 按标签浏览消息。
 *
 * 分类全用规则跑（[TagRules]），一次能把几万条过完。这不是「AI 分类」，
 * 是因为这类消息的特征太明显了——「验证码」「取件码」「账单」摆在那儿，
 * 正则比模型还准，而且快三个数量级。
 */
class TagActivity : AppCompatActivity() {

    private lateinit var db: MessageDb
    private lateinit var chipRow: LinearLayout
    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var btnClassify: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var selected: String = TagRules.CODE
    private var classifying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁着的状态下从最近任务直接进来，直接退出去让主界面验证
        if (Ui.closeIfLocked(this)) return
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_tag)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), null)

        db = MessageDb.get(this)
        chipRow = findViewById(R.id.chipRow)
        container = findViewById(R.id.container)
        scroll = findViewById(R.id.scroll)
        tvStatus = findViewById(R.id.tvTagStatus)
        btnClassify = findViewById(R.id.btnClassify)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        btnClassify.setOnClickListener { classifyAll() }

        loadChips()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 标签条 ----------

    private fun loadChips() {
        io.execute {
            val counts = runCatching { db.tagCounts() }.getOrDefault(emptyMap())
            ui.post {
                if (isFinishing || isDestroyed) return@post
                renderChips(counts)
                val untagged = counts[TagRules.NONE] ?: 0
                tvStatus.text = if (untagged > 0) {
                    getString(R.string.tag_untagged, untagged)
                } else {
                    getString(R.string.tag_all_done)
                }
                load(selected)
            }
        }
    }

    private fun renderChips(counts: Map<String, Int>) {
        chipRow.removeAllViews()
        for (tag in TagRules.ALL) {
            val n = counts[tag] ?: 0
            // 一条都没有的类别不显示，免得条里全是 0
            if (n == 0 && tag != selected) continue
            chipRow.addView(chip(tag, n))
        }
    }

    private fun chip(tag: String, count: Int): View {
        val on = tag == selected
        val tv = TextView(this).apply {
            text = "${TagRules.label(tag)} $count"
            textSize = 12f
            setTextColor(getColor(if (on) R.color.primary else R.color.text_secondary))
            setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                selected = tag
                loadChips()
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.rightMargin = dp(8)
        tv.layoutParams = lp
        return tv
    }

    // ---------- 列表 ----------

    private fun load(tag: String) {
        io.execute {
            val list = runCatching { db.messagesByTag(tag, 300) }.getOrDefault(emptyList())
            ui.post {
                if (isFinishing || isDestroyed) return@post
                render(list, tag)
            }
        }
    }

    private fun render(list: List<Message>, tag: String) {
        container.removeAllViews()
        if (list.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.tag_empty, TagRules.label(tag))
                    setTextColor(getColor(R.color.hint))
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(16), dp(40), dp(16), dp(40))
                }
            )
            return
        }
        for (m in list) container.addView(card(m))
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun card(m: Message): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener { copy(m) }
        }
        box.addView(
            TextView(this).apply {
                text = "${timeText(m.ts)}  ·  " +
                    m.sender.ifBlank { AppInfo.label(this@TagActivity, m.pkg) }
                setTextColor(getColor(R.color.primary))
                textSize = 11f
            }
        )
        box.addView(
            TextView(this).apply {
                text = m.body
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                setLineSpacing(dp(2).toFloat(), 1.25f)
                setPadding(0, dp(4), 0, 0)
            }
        )
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(box)
        wrap.addView(
            View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
        )
        return wrap
    }

    // ---------- 分类 ----------

    /**
     * 把所有还没分类的消息跑一遍规则。
     *
     * 一次 500 条批量更新，用事务包着，几万条也就几秒。
     * 不会死循环：查询只捞 6 个字以上的，而 [TagRules.classify] 对 6 个字
     * 以上一定给出非空标签。
     */
    private fun classifyAll() {
        if (classifying) return
        classifying = true
        btnClassify.isEnabled = false
        btnClassify.alpha = 0.4f
        tvStatus.text = getString(R.string.tag_classifying)

        io.execute {
            var done = 0
            try {
                while (true) {
                    val batch = db.untaggedMessages(500)
                    if (batch.isEmpty()) break
                    db.setTags(batch.map { it.id to TagRules.classify(it.body) })
                    done += batch.size
                    val d = done
                    ui.post {
                        if (!isFinishing && !isDestroyed) {
                            tvStatus.text = getString(R.string.tag_classifying_n, d)
                        }
                    }
                    // 兜底：正常几秒就完了，万一数据异常也别无限跑
                    if (done > 200_000) break
                }
            } catch (t: Throwable) {
                // 分类失败不影响浏览，界面照常刷新
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                classifying = false
                btnClassify.isEnabled = true
                btnClassify.alpha = 1f
                if (done > 0) toast(getString(R.string.tag_classified, done))
                loadChips()
            }
        }
    }

    // ---------- 小工具 ----------

    private fun copy(m: Message) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", m.body))
        toast(getString(R.string.vec_copied))
    }

    private fun timeText(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
