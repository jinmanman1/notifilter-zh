package com.wxmsglog

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 模型管理页。
 *
 * 为什么把模型放在这里而不是打进 APK：1.3GB 的安装包传不动、装不下、也换不了。
 * 拆出来之后这个页面就成了「模型商店」——下载、从本地导入、切换、删除，
 * 都不用重新编译一个 APK。
 */
class ModelActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** specId -> 列表条目。下载进度靠它就地刷新，不用整页重建。 */
    private val cards = HashMap<String, View>()

    /** 模型目录。进页面时解析一次，避免下载时每 200ms 反复 stat 同一个路径。 */
    private val modelsDir: File by lazy { ModelStore.dir(this) }

    /** 已经提示过「下载完成」的模型 id，防止进出页面时重复弹提示。 */
    private var lastDoneId: String? = null

    /** 系统文件选择器。用它拿到的授权只够读一次，所以选完立刻复制进应用目录。 */
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_model)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), null)

        container = findViewById(R.id.container)
        scroll = findViewById(R.id.scroll)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        ModelTask.observe { snap -> onTask(snap) }

        // 进页面时如果上一次下载早就完成了，别一进来就弹个「下载完成」
        lastDoneId = ModelTask.current().let { if (it.finished) it.specId else null }
    }

    override fun onResume() {
        super.onResume()
        val y = if (::scroll.isInitialized) scroll.scrollY else 0
        render()
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    override fun onDestroy() {
        // 只断开观察，不打断下载：1.3GB 下到一半被切页面不该前功尽弃
        ModelTask.clearObserver()
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 界面 ----------

    private fun render() {
        container.removeAllViews()
        cards.clear()

        val current = ModelStore.current(this)

        // 顶部一句话说清现状
        section(getString(R.string.model_section_status))
        body(
            if (current != null) {
                getString(
                    R.string.model_status_on,
                    current.name,
                    ModelCatalog.sizeText(current.length()),
                )
            } else {
                getString(R.string.model_status_off)
            }
        )

        section(getString(R.string.model_section_builtin))
        for (spec in ModelCatalog.ALL) {
            container.addView(buildCard(spec))
            divider()
        }

        // 用户自己放进来或者导入的、不在清单里的文件
        val extras = ModelStore.files(this).filter { f ->
            ModelCatalog.ALL.none { it.fileName == f.name }
        }
        if (extras.isNotEmpty()) {
            section(getString(R.string.model_section_extra))
            for (f in extras) container.addView(buildLocalCard(f))
        }

        section(getString(R.string.model_section_import))
        body(getString(R.string.model_import_hint, modelsDir.absolutePath))
        actionRow(getString(R.string.model_import_btn)) {
            try {
                pickFile.launch(arrayOf("*/*"))
            } catch (t: Throwable) {
                toast(getString(R.string.model_import_no_picker))
            }
        }

        section(getString(R.string.model_section_ai))
        aiRow(current != null)
    }

    /** 内置模型条目。真正的内容由 [bind] 填，进度刷新时整体重绑一次就够了。 */
    private fun buildCard(spec: ModelCatalog.Spec): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_model, container, false)
        cards[spec.id] = v
        bind(v, spec)
        return v
    }

    /**
     * 按当前状态刷新一个卡片。
     *
     * 只改控件内容、不重建 View：下载时这个函数每 200 毫秒被调一次，
     * 如果每次都 addView/removeView，列表会闪到没法看。
     */
    private fun bind(v: View, spec: ModelCatalog.Spec) {
        v.findViewById<TextView>(R.id.tvName).text =
            if (spec.recommended) getString(R.string.model_name_rec, spec.name) else spec.name
        v.findViewById<TextView>(R.id.tvSize).text = ModelCatalog.sizeText(spec.bytes)
        v.findViewById<TextView>(R.id.tvNote).text = spec.note

        val state = v.findViewById<TextView>(R.id.tvState)
        val action = v.findViewById<TextView>(R.id.btnAction)
        val bar = v.findViewById<ProgressBar>(R.id.progress)

        val file = File(modelsDir, spec.fileName)
        val part = File(modelsDir, spec.fileName + ".part")
        val snap = ModelTask.current()
        val downloading = !snap.finished && snap.specId == spec.id
        val current = ModelStore.current(this)

        when {
            downloading -> {
                val done = snap.done
                val total = if (snap.total > 0) snap.total else spec.bytes
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                bar.visibility = View.VISIBLE
                bar.progress = pct
                state.text = getString(
                    R.string.model_state_downloading,
                    ModelCatalog.sizeText(done),
                    ModelCatalog.sizeText(total),
                )
                action.text = getString(R.string.model_btn_cancel)
                action.setOnClickListener { ModelTask.cancel() }
            }

            file.exists() && current?.absolutePath == file.absolutePath -> {
                bar.visibility = View.GONE
                state.text = getString(R.string.model_state_in_use)
                action.text = getString(R.string.model_btn_delete)
                action.setOnClickListener { confirmDelete(file, spec.name) }
            }

            file.exists() -> {
                bar.visibility = View.GONE
                state.text = getString(R.string.model_state_ready)
                action.text = getString(R.string.model_btn_use)
                action.setOnClickListener { use(file, spec.name) }
            }

            part.exists() && part.length() > 0 -> {
                bar.visibility = View.GONE
                state.text = getString(R.string.model_state_partial, ModelCatalog.sizeText(part.length()))
                action.text = getString(R.string.model_btn_resume)
                action.setOnClickListener { confirmDownload(spec) }
            }

            else -> {
                bar.visibility = View.GONE
                state.text = getString(R.string.model_state_absent)
                action.text = getString(R.string.model_btn_download)
                action.setOnClickListener { confirmDownload(spec) }
            }
        }
    }

    /** 清单之外的本地文件。 */
    private fun buildLocalCard(f: File): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_model, container, false)
        v.findViewById<TextView>(R.id.tvName).text = f.name
        v.findViewById<TextView>(R.id.tvSize).text = ModelCatalog.sizeText(f.length())
        v.findViewById<TextView>(R.id.tvNote).text = getString(R.string.model_local_note)
        v.findViewById<ProgressBar>(R.id.progress).visibility = View.GONE

        val state = v.findViewById<TextView>(R.id.tvState)
        val action = v.findViewById<TextView>(R.id.btnAction)
        val current = ModelStore.current(this)

        if (current?.absolutePath == f.absolutePath) {
            state.text = getString(R.string.model_state_in_use)
            action.text = getString(R.string.model_btn_delete)
            action.setOnClickListener { confirmDelete(f, f.name) }
        } else {
            state.text = if (ModelStore.looksLikeGguf(f)) {
                getString(R.string.model_state_ready)
            } else {
                getString(R.string.model_state_bad)
            }
            action.text = getString(R.string.model_btn_use)
            action.setOnClickListener { use(f, f.name) }
        }
        return v
    }

    private fun aiRow(hasModel: Boolean) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, container, false)
        v.findViewById<TextView>(R.id.tvTitle).text = getString(R.string.model_ai_title)
        v.findViewById<TextView>(R.id.tvSub).text = when {
            !hasModel -> getString(R.string.model_ai_need_model)
            Prefs.aiTodoEnabled(this) -> getString(R.string.model_ai_on)
            else -> getString(R.string.model_ai_off)
        }
        v.findViewById<TextView>(R.id.tvArrow).visibility = View.GONE
        v.isEnabled = hasModel
        v.alpha = if (hasModel) 1f else 0.45f
        v.setOnClickListener {
            if (!hasModel) {
                toast(getString(R.string.model_ai_need_model))
                return@setOnClickListener
            }
            Prefs.setAiTodoEnabled(this, !Prefs.aiTodoEnabled(this))
            render()
        }
        container.addView(v)
        divider()
    }

    // ---------- 下载 ----------

    private fun confirmDownload(spec: ModelCatalog.Spec) {
        AlertDialog.Builder(this)
            .setTitle(spec.name)
            .setMessage(getString(R.string.model_download_confirm, ModelCatalog.sizeText(spec.bytes)))
            .setPositiveButton(R.string.model_btn_download) { _, _ ->
                lastDoneId = null
                ModelTask.start(this, spec)
                toast(getString(R.string.model_download_started))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onTask(snap: ModelTask.Snap) {
        if (isFinishing || isDestroyed) return

        // 就地刷新，不增删视图。地图本身不动，避免遍历时被改
        for ((id, v) in cards) {
            val spec = ModelCatalog.byId(id) ?: continue
            bind(v, spec)
        }

        val id = snap.specId
        if (snap.finished && id != null && id != lastDoneId) {
            lastDoneId = id
            val spec = ModelCatalog.byId(id)
            if (spec != null) {
                val file = File(modelsDir, spec.fileName)
                when {
                    snap.error != null -> toast(getString(R.string.model_download_fail))
                    file.exists() && file.length() > 0 -> {
                        // 下好了但用户还没指定用哪个，就直接用上，省一步
                        if (ModelStore.current(this) == null) {
                            ModelStore.setCurrent(this, file)
                        }
                        toast(getString(R.string.model_download_done, spec.name))
                        render()
                    }
                    else -> toast(getString(R.string.model_download_cancelled))
                }
            }
        }
    }

    // ---------- 选用 / 删除 ----------

    /**
     * 设为当前模型。
     * 刻意不在这里加载：加载会立刻占掉 1.5G 内存，而用户可能只是先配好，
     * 真正要用的时候（识别待办）再懒加载。
     */
    private fun use(file: File, name: String) {
        if (!ModelStore.looksLikeGguf(file)) {
            toast(getString(R.string.model_state_bad))
            return
        }
        ModelStore.setCurrent(this, file)
        toast(getString(R.string.model_use_done, name))
        render()
    }

    private fun confirmDelete(file: File, name: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.model_delete_confirm, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Llm.unload()
                val ok = ModelStore.delete(file)
                if (ok) {
                    if (ModelStore.current(this) == null) Prefs.setModelPath(this, null)
                    Prefs.setAiTodoEnabled(this, false)
                    toast(getString(R.string.model_deleted))
                } else {
                    toast(getString(R.string.model_delete_fail))
                }
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 导入 ----------

    /**
     * 从系统文件选择器导入。
     *
     * 复制而不是直接引用 URI：SAF 给的持久化授权在个别国产 ROM 上重启后会失效，
     * 而且有的 Provider 不支持 mmap。复制一份到应用目录最稳，代价是多占一份空间，
     * 导入完成后会提示用户原文件已经可以删了。
     */
    private fun importFrom(uri: Uri) {
        val display = queryName(uri) ?: "imported.gguf"
        val safe = if (display.lowercase().endsWith(".gguf")) display else "$display.gguf"

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        val msg = view.findViewById<TextView>(R.id.tvMsg)
        val bar = view.findViewById<ProgressBar>(R.id.bar)
        msg.text = getString(R.string.model_importing)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        val total = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (t: Throwable) {
            -1L
        }

        dialog.show()

        io.execute {
            var result: File? = null
            try {
                val target = File(modelsDir, safe)
                contentResolver.openInputStream(uri)?.use { ins ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 150) {
                                last = now
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                ui.post {
                                    bar.isIndeterminate = total <= 0
                                    if (total > 0) bar.progress = pct
                                    msg.text = getString(
                                        R.string.model_importing_progress,
                                        ModelCatalog.sizeText(done),
                                    )
                                }
                            }
                        }
                    }
                }
                if (ModelStore.looksLikeGguf(target)) result = target else target.delete()
            } catch (t: Throwable) {
                result = null
            }

            ui.post {
                // 先无条件关掉对话框。如果页面已经销毁就跳过关闭，这个 Dialog 会
                // 变成没人管的窗口，系统报 WindowLeaked 而且一直浮在屏幕上。
                try {
                    dialog.dismiss()
                } catch (t: Throwable) {
                    // 已经关掉了，或者窗口已失效
                }
                if (!isFinishing && !isDestroyed) {
                    if (result != null) {
                        ModelStore.setCurrent(this, result)
                        toast(getString(R.string.model_import_done, safe))
                        render()
                    } else {
                        toast(getString(R.string.model_import_fail))
                    }
                }
            }
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
    } catch (t: Throwable) {
        null
    }

    // ---------- 小工具 ----------

    private fun section(title: String) {
        val tv = TextView(this)
        tv.text = title
        tv.textSize = 12f
        tv.setTextColor(getColor(R.color.primary))
        val pad = dp(16)
        tv.setPadding(pad, dp(18), pad, dp(6))
        container.addView(tv)
    }

    private fun body(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(getColor(R.color.hint))
        tv.setLineSpacing(0f, 1.3f)
        val pad = dp(16)
        tv.setPadding(pad, dp(6), pad, dp(10))
        container.addView(tv)
    }

    private fun actionRow(text: String, onClick: () -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, container, false)
        v.findViewById<TextView>(R.id.tvTitle).text = text
        v.findViewById<TextView>(R.id.tvSub).visibility = View.GONE
        container.addView(v)
        v.setOnClickListener { onClick() }
        divider()
    }

    private fun divider() {
        val v = View(this)
        v.setBackgroundColor(getColor(R.color.divider))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        lp.leftMargin = dp(16)
        v.layoutParams = lp
        container.addView(v)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
