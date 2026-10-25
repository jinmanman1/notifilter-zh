package com.wxmsglog

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.Executors

/**
 * 监听健康度。
 *
 * 这个页面解决一个很具体的问题：**你以为在记录，其实早就停了**。
 *
 * 安卓的省电策略会安静地杀掉后台服务，杀掉之后不报错——授权还在、
 * 界面还是「已开启」，但一条也收不到。用户往往过了好几天才发现记录断了，
 * 最要命的是这段时间的消息再也补不回来。
 *
 * 所以这里把「每个应用最后一条消息是什么时候」摆出来。微信一天几百条，
 * 突然停在三天前，一眼就知道出事了。
 */
class HealthActivity : AppCompatActivity() {

    private lateinit var db: MessageDb
    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Ui.closeIfLocked(this)) return
        Ui.applySecureFlag(this)
        setContentView(R.layout.activity_health)
        Ui.edgeToEdge(this, findViewById(R.id.appBar), null)

        db = MessageDb.get(this)
        container = findViewById(R.id.container)
        scroll = findViewById(R.id.scroll)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRebind).setOnClickListener { rebind() }
    }

    override fun onResume() {
        super.onResume()
        if (Ui.closeIfLocked(this)) return
        render()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 界面 ----------

    private fun render() {
        container.removeAllViews()

        val listenerOn = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        val notifyOn = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val batteryOk = try {
            (getSystemService(POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(packageName)
        } catch (t: Throwable) {
            false
        }
        val lastTs = runCatching { db.lastMessageTs() }.getOrDefault(0L)

        // 顶上一句话回答「现在到底有没有在记录」
        section(getString(R.string.health_section_status))
        val headline = when {
            !listenerOn -> getString(R.string.health_off_listener)
            lastTs == 0L -> getString(R.string.health_never)
            else -> getString(R.string.health_last, ago(lastTs))
        }
        body(
            headline,
            when {
                !listenerOn -> getColor(R.color.danger)
                lastTs == 0L -> getColor(R.color.warn_fg)
                System.currentTimeMillis() - lastTs > 24 * 3600_000L -> getColor(R.color.warn_fg)
                else -> getColor(R.color.primary)
            },
        )

        section(getString(R.string.section_permission))
        row(
            getString(R.string.set_listener),
            getString(if (listenerOn) R.string.set_listener_on else R.string.set_listener_off),
        ) { openListenerSettings() }
        row(
            getString(R.string.set_battery),
            getString(if (batteryOk) R.string.set_battery_on else R.string.set_battery_off),
        ) { requestIgnoreBattery() }
        row(
            getString(R.string.set_notif_channel),
            getString(if (notifyOn) R.string.health_notify_on else R.string.health_notify_off),
        ) { openAppNotificationSettings() }

        section(getString(R.string.health_section_apps))
        loadApps()
    }

    private fun loadApps() {
        io.execute {
            val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
            val stats = runCatching { db.pkgStats(weekAgo) }.getOrDefault(emptyList())
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (stats.isEmpty()) {
                    body(getString(R.string.health_empty), getColor(R.color.hint))
                    return@post
                }
                for (s in stats) {
                    container.addView(appRow(s))
                    divider()
                }
            }
        }
    }

    /**
     * 一个应用一行。
     * 颜色按「最后一条有多久」分档：一天内正常，超过一天发黄，超过三天发红。
     */
    private fun appRow(s: PkgStat): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, container, false)
        val title = v.findViewById<TextView>(R.id.tvTitle)
        val sub = v.findViewById<TextView>(R.id.tvSub)
        v.findViewById<TextView>(R.id.tvArrow).visibility = View.GONE

        val name = if (s.pkg.isEmpty()) getString(R.string.unknown_sender) else AppInfo.label(this, s.pkg)
        title.text = name

        val gap = System.currentTimeMillis() - s.lastTs
        val (hint, color) = when {
            gap < 0 -> "" to getColor(R.color.hint)
            gap <= 24 * 3600_000L -> "" to getColor(R.color.hint)
            gap <= 72 * 3600_000L -> getString(R.string.health_stale) to getColor(R.color.warn_fg)
            else -> getString(R.string.health_dead) to getColor(R.color.danger)
        }

        val base = getString(R.string.health_app_line, ago(s.lastTs), s.recent, s.total)
        sub.text = if (hint.isEmpty()) base else "$base\n$hint"
        sub.setTextColor(color)

        // 点一下进主界面按这个应用筛，省得自己去找
        v.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
        return v
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

    private fun body(text: String, color: Int) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 13f
        tv.setTextColor(color)
        tv.setLineSpacing(0f, 1.3f)
        val pad = dp(16)
        tv.setPadding(pad, dp(6), pad, dp(10))
        container.addView(tv)
    }

    private fun row(title: String, sub: String, onClick: () -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, container, false)
        v.findViewById<TextView>(R.id.tvTitle).text = title
        v.findViewById<TextView>(R.id.tvSub).text = sub
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

    /** 「3 分钟前」这种人话。 */
    private fun ago(ts: Long): String {
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

    // ---------- 跳转 ----------

    private fun rebind() {
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, WxNotificationService::class.java)
            )
            toast(getString(R.string.health_rebind_done))
        } catch (t: Throwable) {
            toast(getString(R.string.listener_missing))
        }
    }

    private fun openListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (t: Throwable) {
            toast(getString(R.string.listener_missing))
        }
    }

    private fun requestIgnoreBattery() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName"),
                )
            )
        } catch (t: Throwable) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            toast(getString(R.string.battery_no_page))
        }
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (t: Throwable) {
            // 打不开就算了
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
