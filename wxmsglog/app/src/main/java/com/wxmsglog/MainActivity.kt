package com.wxmsglog

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var db: MessageDb
    private lateinit var adapter: GroupedAdapter
    private lateinit var rv: RecyclerView
    private lateinit var textBox: ScrollView
    private lateinit var tvText: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvFooter: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvNewHint: TextView
    private lateinit var permCard: LinearLayout
    private lateinit var staleBar: LinearLayout
    private lateinit var tvStale: TextView
    private lateinit var phBar: LinearLayout
    private lateinit var tvPh: TextView
    private lateinit var chipBar: View
    private lateinit var selectBar: LinearLayout
    private lateinit var tvSelCount: TextView
    private lateinit var etSearch: EditText
    private lateinit var tabList: TextView
    private lateinit var tabPerson: TextView
    private lateinit var tabText: TextView
    private lateinit var indList: View
    private lateinit var indPerson: View
    private lateinit var indText: View
    private lateinit var chipApp: TextView
    private lateinit var chipTime: TextView
    private lateinit var chipClear: TextView

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** 输入防抖：打字过程中不必每个字符都查一次库。 */
    private val searchTick = Runnable { refresh(resetPage = true) }

    /** 上一次看到的授权状态。用来判断「刚授权」，好让监听服务立刻生效。 */
    private var wasGranted: Boolean? = null

    // ---------- 筛选状态（列表、文本框、复制、导出共用） ----------

    private var filterPkg: String? = null
    private var rangeKey = RANGE_ALL
    private var rangeFrom = 0L
    private var rangeTo = Long.MAX_VALUE
    private var rangeLabel = ""
    private var mode = MODE_APP

    /**
     * 文本页签里来了新消息但还没有重新生成文本。
     * 之所以不当场刷新：用户可能正在框里拖选一段文字准备复制，
     * 一刷新 text 就被整体替换，选中直接断掉。
     */
    private var pendingText = false

    // ---------- 多选 ----------

    private var selecting = false
    private val selectedIds = LinkedHashSet<Long>()

    /** 当前列表里的消息，多选复制/删除从这里取，避免再查一次库。 */
    private var visibleMessages: List<Message> = emptyList()

    // ---------- 列表分页 ----------

    /** 本次要查多少条。滑到底就加一页，避免「库里几万条，界面永远只显示前 500 条」。 */
    private var displayLimit = PAGE_SIZE

    /** 上一次查询的总条数与实际拿到的条数，决定还能不能继续加载。 */
    private var lastTotal = 0
    private var lastShown = 0

    /**
     * 上次加载更多的时间。滚动会在一帧里连发好几个回调，用时间戳挡一下，
     * 比布尔标志安全——标志一旦因为异常没复位，分页就永久卡死了。
     */
    private var lastLoadAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_main)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), findViewById(R.id.bottomBox))
        db = MessageDb.get(this)

        bindViews()
        adapter = GroupedAdapter(
            onMessageClick = { m -> onMessageTapped(m) },
            onMessageLongClick = { m -> startSelection(m) },
            onCopyGroup = { h -> copyGroup(h) },
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        // 滑到接近底部自动加一页；底部那行字也是可点的入口
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = r.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 2) loadMore()
            }
        })

        restoreFilter(savedInstanceState)
        renderMode()
        updateChips()
        bindActions()

        // 每次启动顺手清一次旧记录，并把监听重新绑一下
        io.execute { runCatching { Maintenance.pruneIfDue(this, db) } }
        rebind()

        Bus.set { onDataChanged() }
        prepareLock()
    }

    private fun bindViews() {
        rv = findViewById(R.id.rv)
        textBox = findViewById(R.id.textBox)
        tvText = findViewById(R.id.tvText)
        tvStatus = findViewById(R.id.tvStatus)
        tvFooter = findViewById(R.id.tvFooter)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvNewHint = findViewById(R.id.tvNewHint)
        permCard = findViewById(R.id.permCard)
        staleBar = findViewById(R.id.staleBar)
        tvStale = findViewById(R.id.tvStale)
        phBar = findViewById(R.id.phBar)
        tvPh = findViewById(R.id.tvPh)
        chipBar = findViewById(R.id.chipBar)
        selectBar = findViewById(R.id.selectBar)
        tvSelCount = findViewById(R.id.tvSelCount)
        etSearch = findViewById(R.id.etSearch)
        tabList = findViewById(R.id.tabList)
        tabPerson = findViewById(R.id.tabPerson)
        tabText = findViewById(R.id.tabText)
        indList = findViewById(R.id.indList)
        indPerson = findViewById(R.id.indPerson)
        indText = findViewById(R.id.indText)
        chipApp = findViewById(R.id.chipApp)
        chipTime = findViewById(R.id.chipTime)
        chipClear = findViewById(R.id.chipClear)
    }

    private fun bindActions() {
        findViewById<Button>(R.id.btnPermission).setOnClickListener { openListenerSettings() }
        findViewById<ImageButton>(R.id.btnSettings)
            .setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<ImageButton>(R.id.btnTodo)
            .setOnClickListener { startActivity(Intent(this, TodoActivity::class.java)) }
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener { toggleSearch() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyCurrent() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareCurrent() }
        findViewById<Button>(R.id.btnMore).setOnClickListener { anchor -> showMenu(anchor) }
        findViewById<Button>(R.id.btnRebind).setOnClickListener { manualRebind() }
        findViewById<Button>(R.id.btnPh).setOnClickListener { explainPlaceholder() }
        tvPh.setOnClickListener { explainPlaceholder() }

        findViewById<View>(R.id.tabListBox).setOnClickListener { setMode(MODE_APP) }
        findViewById<View>(R.id.tabPersonBox).setOnClickListener { setMode(MODE_PERSON) }
        findViewById<View>(R.id.tabTextBox).setOnClickListener { setMode(MODE_TEXT) }

        chipApp.setOnClickListener { pickApp() }
        chipTime.setOnClickListener { pickTimeRange() }
        chipClear.setOnClickListener { clearFilter() }
        // 底部那行「共 X 条 · 已显示 Y 条」本身就是个「加载更多」的按钮
        tvFooter.setOnClickListener { loadMore() }
        tvNewHint.setOnClickListener {
            tvNewHint.visibility = View.GONE
            refresh()
        }

        findViewById<TextView>(R.id.btnSelCopy).setOnClickListener { copySelected() }
        findViewById<TextView>(R.id.btnSelTodo).setOnClickListener { selectionToTodo() }
        findViewById<TextView>(R.id.btnSelDelete).setOnClickListener { confirmDeleteSelected() }
        findViewById<TextView>(R.id.btnSelCancel).setOnClickListener { exitSelection() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                ui.removeCallbacks(searchTick)
                ui.postDelayed(searchTick, 250)
            }
        })
    }

    /**
     * 应用锁：开了锁且本进程还没验证过，就先把内容藏起来。
     * 弹验证框放到 onResume——onCreate 时窗口还没获得焦点，
     * 有些机型会把验证框弹在窗口后面或者直接不显示。
     */
    private var needLock = false

    private fun prepareLock() {
        if (!Prefs.lockEnabled(this) || Lock.unlocked) return
        needLock = true
        findViewById<View>(R.id.root).visibility = View.INVISIBLE
    }

    private fun runLock() {
        val started = Lock.prompt(
            this,
            onSuccess = { findViewById<View>(R.id.root).visibility = View.VISIBLE },
            onCancel = { finish() },
        )
        // 设备没有可用的验证方式：放行，否则用户永远进不来
        if (!started) findViewById<View>(R.id.root).visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (needLock) {
            needLock = false
            runLock()
        }
        val granted = hasAccess()
        // 刚拿到授权：主动重绑一次，不用重启手机就能开始收通知
        if (granted && wasGranted == false) rebind()
        wasGranted = granted
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(K_MODE, mode)
        outState.putString(K_PKG, filterPkg)
        outState.putInt(K_RANGE_KEY, rangeKey)
        outState.putLong(K_FROM, rangeFrom)
        outState.putLong(K_TO, rangeTo)
        outState.putString(K_LABEL, rangeLabel)
    }

    override fun onDestroy() {
        Bus.set(null)
        ui.removeCallbacks(searchTick)
        io.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selecting) {
            exitSelection()
            return
        }
        // 搜索开着的时候，返回键先收起搜索，而不是直接退出应用
        if (etSearch.visibility == View.VISIBLE) {
            closeSearch()
            return
        }
        super.onBackPressed()
    }

    private fun restoreFilter(state: Bundle?) {
        if (state == null) return
        mode = state.getInt(K_MODE, MODE_APP)
        filterPkg = state.getString(K_PKG)
        val key = state.getInt(K_RANGE_KEY, RANGE_ALL)
        if (key == RANGE_CUSTOM) {
            rangeKey = RANGE_CUSTOM
            rangeFrom = state.getLong(K_FROM, 0L)
            rangeTo = state.getLong(K_TO, Long.MAX_VALUE)
            rangeLabel = state.getString(K_LABEL).orEmpty()
        } else {
            applyPresetValues(key)
        }
    }

    private fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun rebind() {
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(this, WxNotificationService::class.java)
            )
        }
    }

    private fun manualRebind() {
        rebind()
        toast(getString(R.string.rebind_done))
        ui.postDelayed({ refresh() }, 1500)
    }

    /**
     * 空壳通知的说明。关掉即视为已读——否则这个提示会一直挂在顶上，
     * 用户点过之后再看到只会觉得烦。
     */
    private fun explainPlaceholder() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ph_title)
            .setMessage(R.string.ph_msg)
            .setPositiveButton(R.string.ph_ok, null)
            .setOnDismissListener {
                Prefs.ackPlaceholder(this)
                refresh()
            }
            .show()
    }

    // ---------- 视图切换 ----------

    private fun setMode(next: Int) {
        if (mode == next) {
            // 再点一次当前页签 = 手动刷新
            if (next == MODE_TEXT && pendingText) refresh()
            return
        }
        exitSelection()
        mode = next
        renderMode()
        refresh(resetPage = true)
    }

    private fun renderMode() {
        val text = mode == MODE_TEXT
        rv.visibility = if (text) View.GONE else View.VISIBLE
        textBox.visibility = if (text) View.VISIBLE else View.GONE
        indList.visibility = if (mode == MODE_APP) View.VISIBLE else View.INVISIBLE
        indPerson.visibility = if (mode == MODE_PERSON) View.VISIBLE else View.INVISIBLE
        indText.visibility = if (text) View.VISIBLE else View.INVISIBLE

        val accent = getColor(R.color.primary)
        val normal = getColor(R.color.hint)
        tabList.setTextColor(if (mode == MODE_APP) accent else normal)
        tabPerson.setTextColor(if (mode == MODE_PERSON) accent else normal)
        tabText.setTextColor(if (text) accent else normal)
        tabList.setTypeface(null, if (mode == MODE_APP) TYPE_BOLD else TYPE_NORMAL)
        tabPerson.setTypeface(null, if (mode == MODE_PERSON) TYPE_BOLD else TYPE_NORMAL)
        tabText.setTypeface(null, if (text) TYPE_BOLD else TYPE_NORMAL)

        adapter.setGroupMode(mode == MODE_PERSON)
    }

    private fun toggleSearch() {
        if (etSearch.visibility == View.VISIBLE) {
            closeSearch()
        } else {
            etSearch.visibility = View.VISIBLE
            etSearch.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeSearch() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.setText("")
        etSearch.visibility = View.GONE
        refresh()
    }

    // ---------- 数据加载 ----------

    /**
     * @param resetPage 筛选条件变了就回到第一页，否则保持当前已加载的页数
     *                  （来新消息时不该把已经翻到的几页收回去）
     */
    private fun refresh(resetPage: Boolean = false) {
        if (isFinishing) return
        if (resetPage) displayLimit = PAGE_SIZE
        pendingText = false
        tvNewHint.visibility = View.GONE
        val granted = hasAccess()
        // 这些值必须在主线程读，进后台线程前定下来
        val keyword = etSearch.text?.toString()?.trim().orEmpty()
        val tokens = searchTokens(keyword)
        val pkg = filterPkg
        val from = rangeFrom
        val to = rangeTo
        val label = rangeLabel
        val m = mode
        val limit = displayLimit
        val fmt = Exporter.formatOf(this)

        io.execute {
            val total = db.countFiltered(keyword, pkg, null, from, to)
            val grandTotal = db.count()
            val latest = db.latestTs()
            var rows: List<Row> = emptyList()
            var msgs: List<Message> = emptyList()
            var text = ""

            if (m == MODE_TEXT) {
                val list = db.queryFiltered(keyword, pkg, null, from, to, TEXT_MSG_LIMIT, desc = false)
                text = if (list.isEmpty()) "" else {
                    Exporter.compose(this, list, TEXT_CHAR_LIMIT, label.takeIf { it.isNotEmpty() }, fmt)
                }
            } else {
                msgs = db.queryFiltered(keyword, pkg, null, from, to, limit, desc = true)
                rows = if (m == MODE_PERSON) groupBySender(msgs) else groupByApp(msgs)
            }

            // 三种「没有内容」要分开说，否则用户以为数据丢了
            val emptyRes = when {
                grandTotal == 0 -> R.string.empty_hint
                keyword.isNotEmpty() -> R.string.search_empty
                else -> if (m == MODE_TEXT) R.string.text_empty else R.string.filter_empty
            }

            ui.post {
                if (isFinishing || isDestroyed) return@post

                if (m == MODE_TEXT) {
                    // 新消息是追加在末尾的，刷新时保留滚动位置，不然一有消息就跳回顶部
                    val keepY = textBox.scrollY
                    tvText.text = text
                    // 没有内容时把空框收起来，否则提示文字会浮在一个大白框上面
                    textBox.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                    if (keepY > 0) textBox.post { textBox.scrollTo(0, keepY) }
                    tvEmpty.visibility = if (text.isEmpty()) View.VISIBLE else View.GONE
                    tvEmpty.text = getString(emptyRes)
                    tvFooter.text = if (text.isEmpty()) {
                        getString(R.string.footer_none)
                    } else {
                        getString(R.string.footer_text, total, text.length)
                    }
                    tvFooter.isClickable = false
                    tvFooter.setTextColor(getColor(R.color.hint))
                } else {
                    visibleMessages = msgs
                    lastTotal = total
                    lastShown = msgs.size
                    adapter.submit(rows, selecting, selectedIds, tokens)
                    tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    tvEmpty.text = getString(emptyRes)
                    val more = total > msgs.size
                    tvFooter.text = if (more) {
                        getString(R.string.footer_more, total, msgs.size)
                    } else {
                        getString(R.string.footer_total, total)
                    }
                    tvFooter.isClickable = more
                    // 还能加载时把这一行染成点缀色，让人一眼看出它是可点的
                    tvFooter.setTextColor(getColor(if (more) R.color.primary else R.color.hint))
                }

                updateHeader(granted, latest, grandTotal)
                updateChips()
            }
        }
    }

    /** 再加载一页。已经取完就不动，避免滚动时反复空查。 */
    private fun loadMore() {
        if (mode == MODE_TEXT) return
        if (lastShown <= 0 || displayLimit >= lastTotal) return
        val now = System.currentTimeMillis()
        if (now - lastLoadAt < 200L) return
        lastLoadAt = now
        displayLimit += PAGE_SIZE
        refresh()
    }

    /**
     * 搜索词拆成若干片段，列表里逐段标出来。
     * 以 @ 开头的是「只匹配发信人」，高亮时去掉 @ 再找。
     */
    private fun searchTokens(keyword: String): List<String> {
        if (keyword.isEmpty()) return emptyList()
        return keyword.split(' ', '\n', '\t')
            .filter { it.isNotEmpty() }
            .map { if (it.length > 1 && it.startsWith("@")) it.substring(1) else it }
            .take(8)
    }

    /** 顶部那张授权提示卡 + 一行「正在记录什么、最近一条多久前」。主线程调用。 */
    private fun updateHeader(granted: Boolean, latest: Long, grandTotal: Int) {
        permCard.visibility = if (granted) View.GONE else View.VISIBLE

        // 授权正常、库里有数据，但已经超过 24 小时没进新消息 —— 监听大概被系统回收了
        val stale = granted && latest > 0 &&
            System.currentTimeMillis() - latest > TimeUnit.HOURS.toMillis(24)
        staleBar.visibility = if (stale) View.VISIBLE else View.GONE
        if (stale) {
            tvStale.text = getString(R.string.stale_hint, TimeFmt.ago(latest))
        }

        // 抓到的通知全是「你收到一条新消息」——消息详情没开，正文根本拿不到。
        // 只提示自上次点掉以来新增的那几条，免得这个条永远挂着。
        val phTotal = Prefs.placeholderCount(this)
        val phSeen = Prefs.placeholderAck(this)
        val phFresh = phTotal - phSeen
        val showPh = granted && phFresh > 0
        phBar.visibility = if (showPh) View.VISIBLE else View.GONE
        if (showPh) {
            tvPh.text = getString(R.string.ph_hint, phFresh)
        }

        tvStatus.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) return

        val recordingAll = Prefs.recordAll(this)
        val pkgs = Prefs.apps(this)
        val who = when {
            recordingAll -> getString(R.string.status_recording_all)
            pkgs.isEmpty() -> getString(R.string.status_none)
            else -> getString(R.string.status_recording, labelSummary(pkgs))
        }
        tvStatus.text = if (grandTotal == 0) {
            who
        } else {
            getString(R.string.status_with_latest, who, TimeFmt.ago(latest))
        }
    }

    /** 按应用分组。传入 list 已是时间倒序，组间顺序自然按「最近活跃」排。 */
    private fun groupByApp(list: List<Message>): List<Row> {
        if (list.isEmpty()) return emptyList()
        val map = LinkedHashMap<String, MutableList<Message>>()
        for (m in list) {
            val key = m.pkg.ifEmpty { "unknown" }
            map.getOrPut(key) { ArrayList() }.add(m)
        }
        val out = ArrayList<Row>(list.size + map.size)
        for ((pkg, msgs) in map) {
            out.add(Row.Header(AppInfo.label(this, pkg), msgs.size, pkg, Row.Header.Kind.APP, pkg))
            for (m in msgs) out.add(Row.Msg(m))
        }
        return out
    }

    /** 按发信人分组。标题就是人名/群名。 */
    private fun groupBySender(list: List<Message>): List<Row> {
        if (list.isEmpty()) return emptyList()
        val map = LinkedHashMap<String, MutableList<Message>>()
        for (m in list) {
            val key = m.sender.ifEmpty { getString(R.string.unknown_sender) }
            map.getOrPut(key) { ArrayList() }.add(m)
        }
        val out = ArrayList<Row>(list.size + map.size)
        for ((sender, msgs) in map) {
            out.add(
                Row.Header(sender, msgs.size, msgs.first().pkg, Row.Header.Kind.SENDER, sender)
            )
            for (m in msgs) out.add(Row.Msg(m))
        }
        return out
    }

    private fun labelSummary(pkgs: Set<String>): String {
        val names = pkgs.map { AppInfo.label(this, it) }.sorted()
        if (names.isEmpty()) return ""
        return if (names.size <= 3) {
            names.joinToString("、")
        } else {
            names.take(3).joinToString("、") + " 等 " + names.size + " 个"
        }
    }

    private fun openListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            toast(getString(R.string.listener_missing))
        }
    }

    // ---------- 多选 ----------

    private fun onMessageTapped(m: Message) {
        if (selecting) {
            toggleSelected(m.id)
        } else {
            Exporter.copy(this, m.sender + ": " + m.body)
            toast(getString(R.string.copied_one))
        }
    }

    private fun startSelection(m: Message) {
        selecting = true
        selectedIds.clear()
        selectedIds.add(m.id)
        renderSelection()
    }

    private fun toggleSelected(id: Long) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
        if (selectedIds.isEmpty()) {
            exitSelection()
        } else {
            renderSelection()
        }
    }

    private fun renderSelection() {
        selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
        chipBar.visibility = if (selecting) View.GONE else View.VISIBLE
        tvSelCount.text = getString(R.string.sel_count, selectedIds.size)
        adapter.updateSelection(selecting, selectedIds)
    }

    private fun exitSelection() {
        if (!selecting) return
        selecting = false
        selectedIds.clear()
        selectBar.visibility = View.GONE
        chipBar.visibility = View.VISIBLE
        adapter.updateSelection(false, emptySet())
    }

    private fun selectedMessages(): List<Message> =
        visibleMessages.filter { selectedIds.contains(it.id) }

    private fun copySelected() {
        val picked = selectedMessages()
        if (picked.isEmpty()) {
            toast(getString(R.string.nothing_to_copy))
            return
        }
        val text = Exporter.compose(this, picked, CLIP_LIMIT, null, Exporter.formatOf(this))
        val cut = Exporter.copy(this, text)
        toast(
            if (cut) getString(R.string.copied_truncated)
            else getString(R.string.copied_n, picked.size)
        )
        exitSelection()
    }

    /**
     * 把选中的消息交给候选页，按规则挑出可能要做的事让用户勾选。
     *
     * 不在这一页直接拼文本：一长串「张三: 内容」塞进编辑框，用户还得自己
     * 从里面挑出哪句是待办，等于没帮上忙。候选页会拆句、认时间、给分组。
     */
    private fun selectionToTodo() {
        if (selectedIds.isEmpty()) {
            toast(getString(R.string.sel_todo_empty))
            return
        }
        val ids = selectedIds.toLongArray()
        exitSelection()
        val i = Intent(this, TodoPickActivity::class.java)
        i.putExtra(TodoPickActivity.EXTRA_IDS, ids)
        startActivity(i)
    }

    private fun confirmDeleteSelected() {
        val n = selectedIds.size
        if (n == 0) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.sel_delete_confirm, n))
            .setPositiveButton(R.string.confirm) { _, _ ->
                val ids = ArrayList(selectedIds)
                io.execute {
                    val deleted = db.deleteByIds(ids)
                    ui.post {
                        toast(getString(R.string.sel_deleted, deleted))
                        exitSelection()
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 筛选 ----------

    private fun pickApp() {
        val from = rangeFrom
        val to = rangeTo
        val kw = keyword()
        io.execute {
            val counts = db.countsByPkg(kw, from, to)
            ui.post {
                if (isFinishing) return@post
                val labels = ArrayList<String>()
                val values = ArrayList<String?>()
                var sum = 0
                for ((_, n) in counts) sum += n
                labels.add(getString(R.string.app_option_all, sum))
                values.add(null)
                for ((pkg, n) in counts) {
                    labels.add(getString(R.string.app_option, AppInfo.label(this, pkg), n))
                    values.add(pkg)
                }
                val checked = values.indexOf(filterPkg)
                AlertDialog.Builder(this)
                    .setTitle(R.string.filter_app_title)
                    .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                        filterPkg = values[which]
                        d.dismiss()
                        exitSelection()
                        updateChips()
                        refresh(resetPage = true)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun pickTimeRange() {
        val items = arrayOf(
            getString(R.string.time_today),
            getString(R.string.time_yesterday),
            getString(R.string.time_7d),
            getString(R.string.time_30d),
            getString(R.string.time_all),
            getString(R.string.time_custom),
        )
        val checked = when (rangeKey) {
            RANGE_TODAY -> 0
            RANGE_YESTERDAY -> 1
            RANGE_7D -> 2
            RANGE_30D -> 3
            RANGE_ALL -> 4
            else -> 5
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.filter_time_title)
            .setSingleChoiceItems(items, checked) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> applyPreset(RANGE_TODAY)
                    1 -> applyPreset(RANGE_YESTERDAY)
                    2 -> applyPreset(RANGE_7D)
                    3 -> applyPreset(RANGE_30D)
                    4 -> applyPreset(RANGE_ALL)
                    else -> pickCustomStart()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyPreset(key: Int) {
        exitSelection()
        applyPresetValues(key)
        updateChips()
        refresh(resetPage = true)
    }

    private fun applyPresetValues(key: Int) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        fun startOf(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
        rangeKey = key
        when (key) {
            RANGE_TODAY -> {
                rangeFrom = startOf(today)
                rangeTo = startOf(today.plusDays(1)) - 1
                rangeLabel = getString(R.string.time_today)
            }
            RANGE_YESTERDAY -> {
                val y = today.minusDays(1)
                rangeFrom = startOf(y)
                rangeTo = startOf(today) - 1
                rangeLabel = getString(R.string.time_yesterday)
            }
            RANGE_7D -> {
                rangeFrom = startOf(today.minusDays(6))
                rangeTo = startOf(today.plusDays(1)) - 1
                rangeLabel = getString(R.string.time_7d)
            }
            RANGE_30D -> {
                rangeFrom = startOf(today.minusDays(29))
                rangeTo = startOf(today.plusDays(1)) - 1
                rangeLabel = getString(R.string.time_30d)
            }
            else -> {
                rangeKey = RANGE_ALL
                rangeFrom = 0L
                rangeTo = Long.MAX_VALUE
                rangeLabel = ""
            }
        }
    }

    /** 自定义区间：开始日期 → 开始时间 → 结束日期 → 结束时间，四步。 */
    private fun pickCustomStart() {
        val now = Calendar.getInstance()
        val dlg = android.app.DatePickerDialog(
            this,
            { _, y, m, d ->
                val c = Calendar.getInstance()
                c.set(y, m, d, 0, 0, 0)
                c.set(Calendar.MILLISECOND, 0)
                showTimePicker(now) { hh, mm ->
                    c.set(Calendar.HOUR_OF_DAY, hh)
                    c.set(Calendar.MINUTE, mm)
                    pickCustomEnd(c.timeInMillis)
                }
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
        )
        dlg.setTitle(R.string.pick_start_date)
        dlg.show()
    }

    private fun pickCustomEnd(start: Long) {
        val now = Calendar.getInstance()
        val dlg = android.app.DatePickerDialog(
            this,
            { _, y, m, d ->
                val c = Calendar.getInstance()
                c.set(y, m, d, 23, 59, 0)
                c.set(Calendar.MILLISECOND, 0)
                showTimePicker(now) { hh, mm ->
                    c.set(Calendar.HOUR_OF_DAY, hh)
                    c.set(Calendar.MINUTE, mm)
                    c.set(Calendar.SECOND, 59)
                    finishCustomRange(start, c.timeInMillis)
                }
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
        )
        dlg.setTitle(R.string.pick_end_date)
        dlg.show()
    }

    private fun showTimePicker(default: Calendar, onPicked: (Int, Int) -> Unit) {
        android.app.TimePickerDialog(
            this,
            { _, hh, mm -> onPicked(hh, mm) },
            default.get(Calendar.HOUR_OF_DAY),
            default.get(Calendar.MINUTE),
            true,
        ).show()
    }

    private fun finishCustomRange(startRaw: Long, endRaw: Long) {
        var a = startRaw
        var b = endRaw
        if (b < a) {
            val t = a
            a = b
            b = t
            toast(getString(R.string.range_swapped))
        }
        rangeKey = RANGE_CUSTOM
        rangeFrom = a
        rangeTo = b
        val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        val zone = ZoneId.systemDefault()
        rangeLabel = getString(
            R.string.range_custom_label,
            Instant.ofEpochMilli(a).atZone(zone).format(fmt),
            Instant.ofEpochMilli(b).atZone(zone).format(fmt),
        )
        updateChips()
        refresh(resetPage = true)
    }

    private fun clearFilter() {
        exitSelection()
        filterPkg = null
        applyPresetValues(RANGE_ALL)
        updateChips()
        refresh(resetPage = true)
    }

    private fun updateChips() {
        val accent = getColor(R.color.primary)
        val normal = Ui.textColorPrimary(this)
        val arrow = " ▾"

        val pkg = filterPkg
        chipApp.text = (if (pkg == null) getString(R.string.filter_all_apps) else AppInfo.label(this, pkg)) + arrow
        chipApp.setBackgroundResource(if (pkg == null) R.drawable.bg_chip else R.drawable.bg_chip_on)
        chipApp.setTextColor(if (pkg == null) normal else accent)

        val filtered = rangeKey != RANGE_ALL
        chipTime.text = (if (filtered) rangeLabel else getString(R.string.filter_all_time)) + arrow
        chipTime.setBackgroundResource(if (filtered) R.drawable.bg_chip_on else R.drawable.bg_chip)
        chipTime.setTextColor(if (filtered) accent else normal)

        chipClear.visibility = if (pkg == null && !filtered) View.GONE else View.VISIBLE
    }

    // ---------- 复制 / 分享 / 导出 ----------

    private fun keyword(): String = etSearch.text?.toString()?.trim().orEmpty()

    /** 分组标题上的「复制」：整组。 */
    private fun copyGroup(h: Row.Header) {
        val kw = keyword()
        val from = rangeFrom
        val to = rangeTo
        val label = rangeLabel
        val fmt = Exporter.formatOf(this)
        io.execute {
            val list = if (h.kind == Row.Header.Kind.SENDER) {
                db.queryFiltered(kw, null, h.value, from, to, EXPORT_MSG_LIMIT, desc = false)
            } else {
                db.queryFiltered(kw, h.value, null, from, to, EXPORT_MSG_LIMIT, desc = false)
            }
            if (list.isEmpty()) {
                ui.post { toast(getString(R.string.nothing_to_copy)) }
                return@execute
            }
            val text = Exporter.compose(this, list, CLIP_LIMIT, label.takeIf { it.isNotEmpty() }, fmt)
            ui.post {
                val cut = Exporter.copy(this, text)
                toast(
                    if (cut) getString(R.string.copied_truncated)
                    else getString(R.string.copied_n, list.size)
                )
            }
        }
    }

    private fun copyCurrent() {
        val kw = keyword()
        val pkg = filterPkg
        val from = rangeFrom
        val to = rangeTo
        val label = rangeLabel
        val fmt = Exporter.formatOf(this)
        io.execute {
            val list = db.queryFiltered(kw, pkg, null, from, to, EXPORT_MSG_LIMIT, desc = false)
            if (list.isEmpty()) {
                ui.post { toast(getString(R.string.nothing_to_copy)) }
                return@execute
            }
            val text = Exporter.compose(this, list, CLIP_LIMIT, label.takeIf { it.isNotEmpty() }, fmt)
            ui.post {
                val cut = Exporter.copy(this, text)
                toast(
                    if (cut) getString(R.string.copied_truncated)
                    else getString(R.string.copied_n, list.size)
                )
            }
        }
    }

    /** 分段复制：一次只喂给 AI 一段，避免超上下文。 */
    private fun pickSegment() {
        val kw = keyword()
        val pkg = filterPkg
        val from = rangeFrom
        val to = rangeTo
        io.execute {
            val list = db.queryFiltered(kw, pkg, null, from, to, EXPORT_MSG_LIMIT, desc = false)
            val segs = if (list.isEmpty()) emptyList() else Exporter.segments(this, list, SEGMENT_CHARS)
            ui.post {
                if (segs.isEmpty()) {
                    toast(getString(R.string.nothing_to_copy))
                    return@post
                }
                val labels = segs.map {
                    getString(R.string.seg_item, it.index, it.total, it.title, it.count, it.text.length)
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(R.string.seg_pick)
                    .setItems(labels) { _, which ->
                        val seg = segs[which]
                        Exporter.copy(this, seg.text)
                        toast(getString(R.string.seg_copied, seg.index, seg.total))
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun shareCurrent() {
        val kw = keyword()
        val pkg = filterPkg
        val from = rangeFrom
        val to = rangeTo
        val label = rangeLabel
        val fmt = Exporter.formatOf(this)
        io.execute {
            val list = db.queryFiltered(kw, pkg, null, from, to, EXPORT_MSG_LIMIT, desc = false)
            if (list.isEmpty()) {
                ui.post { toast(getString(R.string.nothing_to_copy)) }
                return@execute
            }
            val text = Exporter.compose(this, list, SHARE_LIMIT, label.takeIf { it.isNotEmpty() }, fmt)
            ui.post {
                try {
                    startActivity(Exporter.shareIntent(this, text))
                } catch (e: Exception) {
                    toast(getString(R.string.export_fail))
                }
            }
        }
    }

    /** 分享成文件（微信里直接发附件，不会被截断）。 */
    private fun shareCurrentFile() {
        val kw = keyword()
        val pkg = filterPkg
        val from = rangeFrom
        val to = rangeTo
        val label = rangeLabel
        val fmt = Exporter.formatOf(this)
        io.execute {
            val list = db.queryFiltered(kw, pkg, null, from, to, EXPORT_MSG_LIMIT, desc = false)
            if (list.isEmpty()) {
                ui.post { toast(getString(R.string.nothing_to_copy)) }
                return@execute
            }
            val text = Exporter.compose(this, list, Int.MAX_VALUE, label.takeIf { it.isNotEmpty() }, fmt)
            ui.post {
                try {
                    startActivity(Exporter.shareFile(this, text, fmt))
                } catch (e: Exception) {
                    toast(getString(R.string.export_fail))
                }
            }
        }
    }

    private fun exportCurrent() {
        val kw = keyword()
        val pkg = filterPkg
        val from = rangeFrom
        val to = rangeTo
        val label = rangeLabel
        val fmt = Exporter.formatOf(this)
        io.execute {
            val list = db.queryFiltered(kw, pkg, null, from, to, EXPORT_MSG_LIMIT, desc = false)
            if (list.isEmpty()) {
                ui.post { toast(getString(R.string.nothing_to_copy)) }
                return@execute
            }
            val text = Exporter.compose(this, list, Int.MAX_VALUE, label.takeIf { it.isNotEmpty() }, fmt)
            val uri = Exporter.saveToDownloads(this, text, fmt)
            ui.post {
                if (uri == null) {
                    toast(getString(R.string.export_fail))
                } else {
                    toast(getString(R.string.export_ok, uri.toString()))
                }
            }
        }
    }

    fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                io.execute {
                    db.clear()
                    ui.post {
                        toast(getString(R.string.cleared))
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_EXPORT, 0, getString(R.string.menu_export_filtered))
        popup.menu.add(0, MENU_SEGMENT, 1, getString(R.string.menu_segment))
        popup.menu.add(0, MENU_SHARE_FILE, 2, getString(R.string.menu_share_file))
        popup.menu.add(0, MENU_SHARE_TEXT, 3, getString(R.string.menu_share_text))
        popup.menu.add(0, MENU_SETTINGS, 4, getString(R.string.menu_settings))
        popup.menu.add(0, MENU_CLEAR, 5, getString(R.string.menu_clear))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EXPORT -> exportCurrent()
                MENU_SEGMENT -> pickSegment()
                MENU_SHARE_FILE -> shareCurrentFile()
                MENU_SHARE_TEXT -> shareCurrent()
                MENU_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
                MENU_CLEAR -> confirmClear()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 监听服务那边刚存进一条新消息。
     * 列表页直接重排；文本页只挂一条提示，等用户点它（或切页签）再重新生成，
     * 这样正在拖选复制的那段文字不会被打断。
     */
    private fun onDataChanged() {
        if (mode != MODE_TEXT) {
            refresh()
            return
        }
        pendingText = true
        tvNewHint.visibility = View.VISIBLE
        io.execute {
            val granted = hasAccess()
            val latest = db.latestTs()
            val total = db.count()
            ui.post { updateHeader(granted, latest, total) }
        }
    }

    companion object {
        /** 列表每页条数：一次查这么多，滑到底再加一页。 */
        const val PAGE_SIZE = 500
        const val TEXT_MSG_LIMIT = 3000
        const val TEXT_CHAR_LIMIT = 300_000
        const val EXPORT_MSG_LIMIT = 20_000
        /**
         * 复制到剪贴板的字符上限。剪贴板走 Binder，硬顶 1MB 字节，
         * 汉字 3 字节，40 万字符就是 1.2MB —— 会直接崩，所以压到 18 万（约 540KB）。
         */
        const val CLIP_LIMIT = 180_000
        const val SHARE_LIMIT = 200_000
        const val SEGMENT_CHARS = 10_000

        private const val MODE_APP = 0
        private const val MODE_PERSON = 1
        private const val MODE_TEXT = 2

        private val TYPE_BOLD = android.graphics.Typeface.BOLD
        private val TYPE_NORMAL = android.graphics.Typeface.NORMAL

        private const val MENU_EXPORT = 1
        private const val MENU_SEGMENT = 2
        private const val MENU_SHARE_FILE = 3
        private const val MENU_SHARE_TEXT = 4
        private const val MENU_SETTINGS = 5
        private const val MENU_CLEAR = 6

        private const val RANGE_ALL = 0
        private const val RANGE_TODAY = 1
        private const val RANGE_YESTERDAY = 2
        private const val RANGE_7D = 3
        private const val RANGE_30D = 4
        private const val RANGE_CUSTOM = 9

        private const val K_MODE = "mode_view"
        private const val K_PKG = "filter_pkg"
        private const val K_RANGE_KEY = "range_key"
        private const val K_FROM = "range_from"
        private const val K_TO = "range_to"
        private const val K_LABEL = "range_label"
    }
}
