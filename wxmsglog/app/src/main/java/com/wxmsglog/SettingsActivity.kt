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

        section(getString(R.string.section_privacy))
        row(
            getString(R.string.set_lock),
            getString(if (Prefs.lockEnabled(this)) R.string.set_lock_on else R.string.set_lock_off),
        ) { toggleLock() }
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

    // ---------- 隐私 ----------

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
                    Lock.unlocked = true
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
                    Lock.unlocked = true
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
