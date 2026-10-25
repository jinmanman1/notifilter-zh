package com.wxmsglog

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 设置页。所有行都是动态建的，因为很多状态（授权、电池优化、应用数量）要在
 * 每次回到这里时重新读一遍——用户可能刚从系统设置页回来。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var db: MessageDb

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** 选备份文件。恢复是覆盖式导入，所以选完还要再确认一次。 */
    private val pickBackup =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmRestore(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_settings)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), null)
        db = MessageDb.get(this)
        container = findViewById(R.id.container)
        scroll = findViewById(R.id.scroll)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // 离开太久就退回主界面，由它统一弹验证
        if (Ui.closeIfLocked(this)) return
        val y = if (::scroll.isInitialized) scroll.scrollY else 0
        render()
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 界面构建 ----------

    private fun render() {
        container.removeAllViews()
        val granted = listenerGranted()
        val batteryOk = ignoringBattery()
        val apps = Prefs.apps(this)
        val all = Prefs.recordAll(this)

        section(getString(R.string.section_permission))
        // 放在最上面：用户来设置页，一半是因为「好像不记录了」
        row(
            getString(R.string.health_title),
            if (granted) getString(R.string.health_last, lastAgo()) else getString(R.string.health_off_listener),
        ) { startActivity(Intent(this, HealthActivity::class.java)) }
        row(
            getString(R.string.set_listener),
            getString(if (granted) R.string.set_listener_on else R.string.set_listener_off),
        ) { openListenerSettings() }
        row(
            getString(R.string.set_battery),
            getString(if (batteryOk) R.string.set_battery_on else R.string.set_battery_off),
        ) { requestIgnoreBattery() }
        row(
            getString(R.string.set_autostart),
            getString(R.string.set_autostart_sub),
        ) { openAutoStart() }
        row(
            getString(R.string.set_notif_channel),
            getString(R.string.set_notif_channel_sub),
        ) { openAppNotificationSettings() }

        section(getString(R.string.section_scope))
        val summary = if (apps.isEmpty()) getString(R.string.status_none) else labelSummary(apps)
        row(
            getString(R.string.set_apps),
            getString(R.string.set_apps_sub, summary, apps.size),
        ) { startActivity(Intent(this, AppPickerActivity::class.java)) }
        row(
            getString(R.string.set_all_apps),
            getString(if (all) R.string.set_all_apps_on else R.string.set_all_apps_off),
        ) {
            Prefs.setRecordAll(this, !Prefs.recordAll(this))
            render()
        }

        section(getString(R.string.section_ai))
        val model = ModelStore.current(this)
        val modelSub = when {
            model == null -> getString(R.string.set_model_none)
            Prefs.aiTodoEnabled(this) -> getString(R.string.set_model_on, model.name)
            else -> getString(R.string.set_model_off, model.name)
        }
        row(getString(R.string.set_model), modelSub) {
            startActivity(Intent(this, ModelActivity::class.java))
        }

        if (model != null) {
            // 定时自动整理
            row(
                getString(R.string.set_ai_auto),
                if (Prefs.aiAutoEnabled(this)) {
                    getString(R.string.set_ai_auto_on, Prefs.aiAutoHours(this))
                } else {
                    getString(R.string.set_ai_auto_off)
                },
            ) {
                val on = !Prefs.aiAutoEnabled(this)
                Prefs.setAiAutoEnabled(this, on)
                // 开关一变就要重排闹钟：关掉要撤掉已排的，打开要立刻排上
                AiSchedule.rescheduleAll(this)
                render()
            }
            row(getString(R.string.set_ai_auto_interval), intervalLabel()) { pickInterval() }

            row(getString(R.string.report_entry), getString(R.string.report_need_model_hint)) {
                startActivity(Intent(this, ReportActivity::class.java))
            }
            row(
                getString(R.string.set_report_day),
                if (Prefs.reportDayEnabled(this)) {
                    getString(R.string.set_report_day_on, Prefs.reportDayHour(this))
                } else {
                    getString(R.string.set_report_day_off)
                },
            ) {
                Prefs.setReportDayEnabled(this, !Prefs.reportDayEnabled(this))
                AiSchedule.rescheduleAll(this)
                render()
            }
            row(
                getString(R.string.set_report_week),
                if (Prefs.reportWeekEnabled(this)) {
                    getString(
                        R.string.set_report_week_on,
                        weekDayLabel(Prefs.reportWeekDay(this)),
                        Prefs.reportWeekHour(this),
                    )
                } else {
                    getString(R.string.set_report_week_off)
                },
            ) {
                Prefs.setReportWeekEnabled(this, !Prefs.reportWeekEnabled(this))
                AiSchedule.rescheduleAll(this)
                render()
            }
            row(getString(R.string.set_report_time), getString(R.string.set_report_time)) { pickReportTime() }
        }

        section(getString(R.string.section_remind))
        row(
            getString(R.string.set_strong_alarm),
            getString(
                if (Prefs.strongAlarm(this)) R.string.set_strong_alarm_on
                else R.string.set_strong_alarm_off
            ),
        ) {
            Prefs.setStrongAlarm(this, !Prefs.strongAlarm(this))
            render()
        }

        section(getString(R.string.section_privacy))
        val lockOn = Prefs.lockEnabled(this)
        row(
            getString(R.string.set_lock),
            if (lockOn) getString(R.string.set_lock_on, timeoutLabel()) else getString(R.string.set_lock_off),
        ) { toggleLock() }
        // 时机只在锁开着的时候才有意义，关着就不显示，免得用户以为设了有用
        if (lockOn) {
            row(getString(R.string.set_lock_when), timeoutLabel()) { pickLockTimeout() }
        }
        row(
            getString(R.string.set_secure),
            getString(
                if (Prefs.blockScreenshot(this)) R.string.set_secure_on
                else R.string.set_secure_off
            ),
        ) { toggleSecure() }

        section(getString(R.string.section_data))
        val fmt = Exporter.formatOf(this)
        row(getString(R.string.set_format), formatLabel(fmt)) { pickFormat() }
        row(getString(R.string.set_retention), retentionLabel()) { pickRetention() }
        row(getString(R.string.set_prune), getString(R.string.set_prune_sub)) { pickPrune() }
        row(getString(R.string.set_export), getString(R.string.set_export_sub)) { exportToDownloads() }
        row(getString(R.string.set_share), getString(R.string.set_share_sub)) { shareText() }
        row(getString(R.string.set_share_file), getString(R.string.set_share_file_sub)) { shareFile() }
        row(getString(R.string.set_clear), getString(R.string.set_clear_sub)) { confirmClear() }
        row(getString(R.string.backup_export), getString(R.string.backup_export_sub)) { exportBackup() }
        row(getString(R.string.backup_restore), getString(R.string.backup_restore_sub)) { pickBackup() }

        section(getString(R.string.section_about))
        body(getString(R.string.about_body, versionName()))
    }

    private fun section(title: String) {
        val tv = TextView(this)
        tv.text = title
        tv.textSize = 12f
        tv.setTextColor(getColor(R.color.primary))
        val pad = dp(16)
        tv.setPadding(pad, dp(18), pad, dp(6))
        container.addView(tv)
    }

    private fun row(title: String, sub: String, onClick: () -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, container, false)
        v.findViewById<TextView>(R.id.tvTitle).text = title
        v.findViewById<TextView>(R.id.tvSub).text = sub
        v.setOnClickListener { onClick() }
        container.addView(v)
        divider()
    }

    private fun body(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(getColor(R.color.hint))
        tv.setLineSpacing(0f, 1.25f)
        val pad = dp(16)
        tv.setPadding(pad, dp(8), pad, dp(8))
        container.addView(tv)
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

    // ---------- 状态查询 ----------

    private fun listenerGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    /** 最后一次收到消息是多久前，给「监听状态」那行做副标题。 */
    private fun lastAgo(): String {
        val ts = runCatching { db.lastMessageTs() }.getOrDefault(0L)
        if (ts <= 0L) return getString(R.string.health_never_short)
        val gap = System.currentTimeMillis() - ts
        val min = gap / 60_000L
        val hour = gap / 3_600_000L
        val day = gap / 86_400_000L
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "$min 分钟前"
            hour < 24 -> "$hour 小时前"
            day < 30 -> "$day 天前"
            else -> (day / 30).toString() + " 个月前"
        }
    }

    private fun ignoringBattery(): Boolean = try {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    } catch (t: Throwable) {
        false
    }

    private fun labelSummary(pkgs: Set<String>): String =
        pkgs.map { AppInfo.label(this, it) }.sorted().joinToString("、")

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    // ---------- 跳转 ----------

    private fun openListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (t: Throwable) {
            toast(getString(R.string.listener_missing))
        }
    }

    /** 直接弹「是否允许忽略电池优化」，老系统或国产 ROM 没有这个页面就退到列表页。 */
    private fun requestIgnoreBattery() {
        if (ignoringBattery()) {
            toast(getString(R.string.battery_done))
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                // 连列表页都没有，就算了
            }
            toast(getString(R.string.battery_no_page))
        }
    }

    /**
     * 自启动管理没有统一的标准 Action，只能把各家的页面挨个试一遍。
     * 一个都打不开就退到应用详情页，让用户自己找。
     */
    private fun openAutoStart() {
        for (spec in AUTO_START_PAGES) {
            val cn = ComponentName.unflattenFromString(spec) ?: continue
            val intent = Intent().apply {
                component = cn
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = try {
                packageManager.resolveActivity(intent, 0) != null
            } catch (t: Throwable) {
                false
            }
            if (!ok) continue
            try {
                startActivity(intent)
                return
            } catch (t: Throwable) {
                // 这个页面存在但打不开（被厂商藏了），换下一个
            }
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (t: Throwable) {
            // 应用详情页都打不开就算了
        }
        toast(getString(R.string.autostart_no_page))
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (t: Throwable) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (t2: Throwable) {
                // 打不开就算了
            }
        }
    }

    // ---------- AI 定时 / 报告时间 ----------

    private fun intervalLabel(): String =
        getString(R.string.set_ai_auto_on, Prefs.aiAutoHours(this))

    private fun pickInterval() {
        val hours = intArrayOf(1, 3, 6, 12, 24)
        val labels = hours.map { "$it 小时" }.toTypedArray()
        val checked = hours.indexOf(Prefs.aiAutoHours(this)).coerceAtLeast(0)
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(R.string.set_ai_auto_interval)
            .setSingleChoiceItems(labels, checked) { d, which ->
                Prefs.setAiAutoHours(this, hours[which])
                d.dismiss()
                AiSchedule.rescheduleAll(this)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun weekDayLabel(d: Int): String =
        arrayOf("一", "二", "三", "四", "五", "六", "日")[(d - 1).coerceIn(0, 6)]

    /** 推送时间。日报和周报分开设，弹一层列表让用户先选改哪个。 */
    private fun pickReportTime() {
        val items = arrayOf(
            "日报 ${"%02d".format(Prefs.reportDayHour(this))}:00",
            "周报 周${weekDayLabel(Prefs.reportWeekDay(this))} " +
                "${"%02d".format(Prefs.reportWeekHour(this))}:00",
        )
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(R.string.set_report_time)
            .setItems(items) { _, which ->
                if (which == 0) pickHour { h -> Prefs.setReportDayHour(this, h) }
                else pickWeekDay()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickHour(onPick: (Int) -> Unit) {
        val labels = (0..23).map { "%02d:00".format(it) }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(R.string.set_report_time)
            .setSingleChoiceItems(labels, -1) { d, which ->
                onPick(which)
                d.dismiss()
                AiSchedule.rescheduleAll(this)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickWeekDay() {
        val labels = (1..7).map { "周${weekDayLabel(it)}" }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(R.string.set_report_week)
            .setSingleChoiceItems(labels, Prefs.reportWeekDay(this) - 1) { d, which ->
                Prefs.setReportWeekDay(this, which + 1)
                d.dismiss()
                AiSchedule.rescheduleAll(this)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 隐私 ----------

    /**
     * 重新验证时机。
     *
     * 从最严到最松。默认 1 分钟：切出去回个消息再回来不用重按指纹，
     * 但手机交给别人超过一分钟就得重新验证。
     */
    private fun timeoutLabel(): String = msLabel(Prefs.lockTimeout(this))

    private fun msLabel(ms: Long): String = when {
        ms < 0L -> getString(R.string.lock_when_never)
        ms == 0L -> getString(R.string.lock_when_now)
        ms >= 1_800_000L -> getString(R.string.lock_when_30m)
        ms >= 300_000L -> getString(R.string.lock_when_5m)
        ms >= 60_000L -> getString(R.string.lock_when_1m)
        else -> getString(R.string.lock_when_30s)
    }

    private fun pickLockTimeout() {
        val values = longArrayOf(0L, 30_000L, 60_000L, 300_000L, 1_800_000L, -1L)
        val labels = values.map { msLabel(it) }.toTypedArray()
        val checked = values.indexOf(Prefs.lockTimeout(this)).coerceAtLeast(0)
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(R.string.set_lock_when)
            .setSingleChoiceItems(labels, checked) { d, which ->
                Prefs.setLockTimeout(this, values[which])
                d.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 开关防截屏。不用重启界面，FLAG_SECURE 可以随时加上或去掉。 */
    private fun toggleSecure() {
        val on = !Prefs.blockScreenshot(this)
        Prefs.setBlockScreenshot(this, on)
        Ui.applySecureFlag(this)
        render()
        toast(getString(if (on) R.string.secure_on_toast else R.string.secure_off_toast))
    }

    private fun toggleLock() {
        val enabled = Prefs.lockEnabled(this)
        if (enabled) {
            // 关掉也要验证一次，否则谁拿到手机都能把锁关了
            Lock.prompt(
                this,
                onSuccess = {
                    Prefs.setLockEnabled(this, false)
                    Lock.markUnlocked()
                    render()
                    toast(getString(R.string.lock_off_toast))
                },
                onCancel = { toast(getString(R.string.lock_need_auth)) },
            )
        } else {
            if (!Lock.canUse(this)) {
                toast(getString(R.string.lock_needed))
                return
            }
            Lock.prompt(
                this,
                onSuccess = {
                    Prefs.setLockEnabled(this, true)
                    Lock.markUnlocked()
                    render()
                    toast(getString(R.string.lock_on_toast))
                },
                onCancel = { toast(getString(R.string.lock_need_auth)) },
            )
        }
    }

    // ---------- 导出格式 / 保留时长 ----------

    private fun formatLabel(f: Exporter.Format): String = when (f) {
        Exporter.Format.MD -> getString(R.string.fmt_md)
        Exporter.Format.TXT -> getString(R.string.fmt_txt)
        Exporter.Format.CSV -> getString(R.string.fmt_csv)
        Exporter.Format.JSON -> getString(R.string.fmt_json)
    }

    private fun pickFormat() {
        val values = Exporter.Format.entries
        val labels = values.map { formatLabel(it) }.toTypedArray()
        val checked = values.indexOf(Exporter.formatOf(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.format_title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                Prefs.setExportFormat(this, values[which].key)
                d.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun retentionLabel(): String {
        val days = Prefs.retentionDays(this)
        return if (days <= 0) getString(R.string.retention_keep) else getString(R.string.retention_days, days)
    }

    private fun pickRetention() {
        val days = intArrayOf(7, 30, 90, 180, 365, 0)
        val labels = days.map {
            if (it == 0) getString(R.string.retention_keep) else getString(R.string.retention_days, it)
        }.toTypedArray()
        val checked = days.indexOf(Prefs.retentionDays(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.retention_title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                Prefs.setRetentionDays(this, days[which])
                d.dismiss()
                // 改完立刻按新规则清一次，别等下一次启动
                ui.post { pruneNow(days[which]) }
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pruneNow(days: Int) {
        if (days <= 0) return
        io.execute {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
            val deleted = runCatching { db.deleteOlderThan(cutoff) }.getOrDefault(0)
            Prefs.setLastPrune(this, System.currentTimeMillis())
            ui.post {
                if (deleted > 0) toast(getString(R.string.prune_done, deleted))
                render()
            }
        }
    }

    private fun pickPrune() {
        val days = intArrayOf(30, 90, 180, 365)
        val labels = days.map { getString(R.string.prune_days_ago, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.prune_title)
            .setItems(labels) { _, which ->
                val d = days[which]
                io.execute {
                    val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(d.toLong())
                    val deleted = runCatching { db.deleteOlderThan(cutoff) }.getOrDefault(0)
                    ui.post {
                        toast(
                            if (deleted > 0) getString(R.string.prune_done, deleted)
                            else getString(R.string.prune_none)
                        )
                        render()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 数据操作 ----------

    private fun exportToDownloads() {
        io.execute {
            val list = db.queryAll()
            val fmt = Exporter.formatOf(this)
            val empty = list.isEmpty()
            val text = if (empty) "" else Exporter.compose(this, list, Int.MAX_VALUE, null, fmt)
            val uri = if (empty) null else Exporter.saveToDownloads(this, text, fmt)
            ui.post {
                when {
                    empty -> toast(getString(R.string.nothing_to_copy))
                    uri == null -> toast(getString(R.string.export_fail))
                    else -> toast(getString(R.string.export_ok, uri.toString()))
                }
            }
        }
    }

    private fun shareText() {
        io.execute {
            val list = db.queryAll()
            val fmt = Exporter.formatOf(this)
            val empty = list.isEmpty()
            val text = if (empty) "" else Exporter.compose(this, list, SHARE_LIMIT, null, fmt)
            ui.post {
                if (empty) {
                    toast(getString(R.string.nothing_to_copy))
                } else {
                    try {
                        startActivity(Exporter.shareIntent(this, text))
                    } catch (t: Throwable) {
                        toast(getString(R.string.export_fail))
                    }
                }
            }
        }
    }

    private fun shareFile() {
        io.execute {
            val list = db.queryAll()
            val fmt = Exporter.formatOf(this)
            val empty = list.isEmpty()
            val text = if (empty) "" else Exporter.compose(this, list, Int.MAX_VALUE, null, fmt)
            ui.post {
                if (empty) {
                    toast(getString(R.string.nothing_to_copy))
                } else {
                    try {
                        startActivity(Exporter.shareFile(this, text, fmt))
                    } catch (t: Throwable) {
                        toast(getString(R.string.export_fail))
                    }
                }
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                io.execute {
                    db.clear()
                    ui.post {
                        toast(getString(R.string.cleared))
                        render()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 备份 / 恢复 ----------

    /**
     * 导出全部数据。
     *
     * 这是明文 JSON，里面有全部聊天记录。写入的是系统「下载」目录，
     * 别的应用（文件管理器、网盘自动同步）也可能读到——这一点必须在
     * 导出之后的提示里说清楚，不能只发个「成功」。
     */
    private fun exportBackup() {
        toast(getString(R.string.backup_exporting))
        io.execute {
            val json = try {
                Backup.export(this)
            } catch (t: Throwable) {
                null
            }
            val uri = if (json == null) {
                null
            } else {
                runCatching {
                    Exporter.saveToDownloads(this, json, Exporter.Format.JSON, "wxmsglog-backup-")
                }.getOrNull()
            }
            ui.post {
                if (uri == null) {
                    toast(getString(R.string.export_fail))
                } else {
                    toast(getString(R.string.backup_export_ok))
                }
            }
        }
    }

    private fun confirmRestore(uri: android.net.Uri) {
        AlertDialog.Builder(this, R.style.Theme_WxMsgLog_Dialog)
            .setTitle(R.string.backup_restore)
            .setMessage(R.string.backup_restore_confirm)
            .setPositiveButton(R.string.confirm) { _, _ -> doRestore(uri) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doRestore(uri: android.net.Uri) {
        toast(getString(R.string.backup_restoring))
        io.execute {
            val text = Backup.read(this, uri)
            val r = if (text == null) {
                Backup.Result(0, 0, 0, getString(R.string.backup_read_fail))
            } else {
                Backup.restore(this, text)
            }
            ui.post {
                if (r.error != null) {
                    toast(r.error)
                } else {
                    toast(getString(R.string.backup_restore_ok, r.messages, r.todos, r.reports))
                }
                render()
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SHARE_LIMIT = 200_000

        val AUTO_START_PAGES = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/.optimize.process.ProtectActivity",
            "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
            "com.oppo.safe/.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.meizu.safe/.security.SecureSafeMainActivity",
            "com.nubia.systemmanager/.power.ui.PowerSavingModeActivity",
            "com.transsion.phonemaster/com.cyin.himgr.autostart.AutoStartActivity",
        )
    }
}
