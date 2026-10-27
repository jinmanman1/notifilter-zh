package com.wxmsglog

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 待办提示词编辑器。
 *
 * 为什么做成可编辑：模型抽得准不准，八成看提示词。而提示词的效果又跟具体的
 * 聊天内容强相关——别人群里「收到」多，你群里「这周把那个事办一下」多，最好的
 * 写法就不一样。写死在代码里意味着每调一次都要重新编译、重新装包，试错成本
 * 高到根本没人愿意调。
 *
 * 下面那个「试一下」是关键：改完立刻拿一条真实消息跑一遍，看模型到底吐了什么。
 * 没有它，用户只能等下一次整理几十条才知道改坏了没有。
 *
 * 改坏了也不会出事——输出格式由 native 层的 GBNF 语法硬约束，提示词写得再离谱，
 * 模型也吐不出非法 JSON，最多是抽得不对。点右上角就能恢复默认。
 */
class PromptActivity : AppCompatActivity() {

    private lateinit var etPrompt: EditText
    private lateinit var etTest: EditText
    private lateinit var tvResult: TextView
    private lateinit var tvState: TextView
    private lateinit var btnTrial: Button

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Ui.closeIfLocked(this)) return
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_prompt)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), null)

        etPrompt = findViewById(R.id.etPrompt)
        etTest = findViewById(R.id.etTest)
        tvResult = findViewById(R.id.tvResult)
        tvState = findViewById(R.id.tvState)
        btnTrial = findViewById(R.id.btnTrial)

        // 编辑框里放的是用户改过的那份；没改过就是默认的完整模板
        etPrompt.setText(LlmExtract.template(this))
        renderState()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnReset).setOnClickListener { confirmReset() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        btnTrial.setOnClickListener { runTrial() }
    }

    override fun onResume() {
        super.onResume()
        // 离开太久就退回主界面，由它统一弹验证
        if (Ui.closeIfLocked(this)) return
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 状态 ----------

    private fun renderState() {
        tvState.text = if (LlmExtract.isCustomized(this)) {
            getString(R.string.prompt_state_custom)
        } else {
            getString(R.string.prompt_state_default)
        }
    }

    // ---------- 保存 ----------

    private fun save() {
        val text = etPrompt.text.toString()

        if (text.isBlank()) {
            toast(getString(R.string.prompt_err_empty))
            return
        }
        // 没有 {{body}} 模型就看不到消息内容，必然全判「不是待办」。
        // 这是最容易犯的错，直接拦下来而不是等用户自己发现
        if (!text.contains("{{body}}")) {
            toast(getString(R.string.prompt_err_no_body))
            return
        }
        if (text.length > MAX_CHARS) {
            toast(getString(R.string.prompt_err_too_long, MAX_CHARS))
            return
        }

        // 内容跟默认一样就当作「没改过」，这样以后默认模板升级了能自动跟上
        if (text.trim() == LlmExtract.DEFAULT_PROMPT.trim()) {
            Prefs.setPromptTemplate(this, null)
        } else {
            Prefs.setPromptTemplate(this, text)
        }

        renderState()
        toast(getString(R.string.prompt_saved))
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(R.string.prompt_reset)
            .setMessage(R.string.prompt_reset_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                Prefs.setPromptTemplate(this, null)
                etPrompt.setText(LlmExtract.DEFAULT_PROMPT)
                renderState()
                tvResult.visibility = android.view.View.GONE
                toast(getString(R.string.prompt_reset_done))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 试跑 ----------

    private fun runTrial() {
        val msg = etTest.text.toString().trim()
        if (msg.isEmpty()) {
            toast(getString(R.string.prompt_trial_need_msg))
            return
        }

        val model = ModelStore.current(this)
        if (model == null) {
            toast(getString(R.string.prompt_trial_need_model))
            return
        }

        // 用编辑框里的当前内容，不是已保存的那份——改完不用先存就能试
        val template = etPrompt.text.toString()
        if (!template.contains("{{body}}")) {
            toast(getString(R.string.prompt_err_no_body))
            return
        }

        btnTrial.isEnabled = false
        btnTrial.text = getString(R.string.prompt_trial_running)
        tvResult.visibility = android.view.View.VISIBLE
        tvResult.text = getString(R.string.prompt_trial_loading)

        val now = System.currentTimeMillis()
        io.execute {
            // 模型可能还没加载（用户直接从设置点进来），这里顺手加载
            val err = Llm.load(model)
            val trial = if (err != null) {
                null
            } else {
                LlmExtract.trial(template, msg, "", now)
            }

            ui.post {
                if (isFinishing || isDestroyed) return@post
                btnTrial.isEnabled = true
                btnTrial.text = getString(R.string.prompt_trial_run)

                if (trial == null) {
                    tvResult.text = getString(R.string.prompt_trial_load_fail, err ?: "")
                } else {
                    renderTrial(trial)
                }
            }
        }
    }

    /**
     * 结果分两段：上面是人话结论，下面是模型的原始输出。
     *
     * 原始输出必须露出来——用户调整提示词时最需要看的就是「它到底吐了什么」，
     * 只看一个「抽到了」是没法判断该往哪个方向改的。
     */
    private fun renderTrial(t: LlmExtract.Trial) {
        val head = if (t.hit == null) {
            getString(R.string.prompt_trial_no)
        } else {
            val due = if (t.hit.due > 0L) {
                getString(R.string.prompt_trial_due, fmt(t.hit.due))
            } else {
                getString(R.string.prompt_trial_no_due)
            }
            getString(R.string.prompt_trial_yes, t.hit.text, due)
        }

        val raw = if (t.raw.isBlank()) getString(R.string.prompt_trial_empty) else t.raw
        val full = buildString {
            append(head)
            append("\n\n")
            append(getString(R.string.prompt_trial_raw))
            append("\n")
            append(raw)
            // 提示词多少字也告诉用户，便于判断是不是太长拖慢了
            append("\n\n")
            append(getString(R.string.prompt_trial_len, t.prompt.length))
        }

        val sp = SpannableString(full)
        val color = getColor(if (t.hit == null) R.color.text_secondary else R.color.primary)
        sp.setSpan(
            ForegroundColorSpan(color),
            0,
            head.length.coerceAtMost(full.length),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        tvResult.text = sp
    }

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * 模板长度上限。
         *
         * 上下文一共 2048 token，要同时装下：模板本身、这条消息、还要留 96 个
         * 给输出。中文大约 1 字 0.7 token，3000 字约 2100 token —— 到这儿已经
         * 快到顶了。再长会触发 native 层的裁剪（保留头尾、砍中间），模型可能
         * 看不到消息正文，那就成了「怎么调都不对」。
         */
        private const val MAX_CHARS = 3000
    }
}
