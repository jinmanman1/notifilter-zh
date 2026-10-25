package com.wxmsglog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.Executors

/**
 * 后台跑 AI 的容器。
 *
 * 为什么必须用前台服务：加载 1.7B 模型要吃 1.5G 内存、跑几十秒，
 * 普通后台线程在国产 ROM 上随时会被冻结或杀掉，跑一半断了还留下半截状态。
 * 挂个前台通知，系统至少不会在你专注做事的时候把进程回收掉。
 *
 * 一次只跑一件事。同一时间开两个推理任务会把内存直接打穿。
 */
class AiService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-service").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        // 先把前台通知挂上，再干活。顺序反了在 Android 12+ 会直接抛异常
        startForeground(FG_ID, progressNotification(action))

        worker.execute {
            var title = ""
            var text = ""
            try {
                when (action) {
                    ACTION_SCAN -> {
                        // 进度回调直接改写前台通知。一分钟里一个字都不变的话，
                        // 用户没法判断是在干活还是已经死了
                        val nm = getSystemService(NotificationManager::class.java)
                        val r = AiAggregate.run(this) { done, total ->
                            runCatching {
                                nm?.notify(
                                    FG_ID,
                                    progressNotification(action, "正在整理待办 $done / $total…"),
                                )
                            }
                        }
                        title = "AI 整理完成"
                        text = when {
                            r.error != null -> r.error
                            r.scanned == 0 -> "没有新消息需要分析"
                            r.added == 0 -> "看了 ${r.scanned} 条，这次没找到要办的事"
                            else -> "看了 ${r.scanned} 条，新增 ${r.added} 条待办"
                        }
                    }

                    ACTION_REPORT_DAY -> {
                        val r = ReportGen.generateDay(this)
                        title = "今日日报已生成"
                        text = if (r == null) "今天没有可归纳的消息" else "点开看看今天都发生了什么"
                    }

                    ACTION_REPORT_WEEK -> {
                        val r = ReportGen.generateWeek(this)
                        title = "本周周报已生成"
                        text = if (r == null) "这周没有可归纳的消息" else "点开回顾这一周"
                    }

                    ACTION_INDEX -> {
                        // 建语义索引。一批 50 条，最多跑 5 分钟——再久就该
                        // 让用户知道进度，而不是让一个前台服务一直挂着耗电
                        var total = 0
                        val deadline = System.currentTimeMillis() + 5 * 60 * 1000L
                        while (System.currentTimeMillis() < deadline) {
                            val n = SemanticSearch.indexBatch(this, SemanticSearch.BATCH)
                            if (n <= 0) break
                            total += n
                        }
                        title = "语义索引"
                        text = if (total == 0) {
                            "没有需要索引的消息"
                        } else {
                            "已处理 $total 条，共 ${SemanticSearch.indexedCount(this)} 条可搜"
                        }
                    }
                }
            } catch (t: Throwable) {
                title = "AI 任务失败"
                text = t.message ?: "出了点问题"
            } finally {
                if (title.isNotEmpty()) notifyDone(title, text)
                // 先摘掉前台状态再停服务。只 stopSelf 的话，某些 ROM 上那条
                // 「AI 工作中」会一直挂在通知栏，用户以为卡住了
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun progressNotification(
        action: String,
        progress: String? = null,
    ): android.app.Notification {
        val what = when (action) {
            // 一条要一两秒，四十条就是一分多钟。光写「正在整理」挂一分钟，
            // 用户会以为卡死了——所以要能报数
            ACTION_SCAN -> progress ?: "正在整理待办，约 1~2 分钟…"
            ACTION_REPORT_DAY -> "正在生成今日日报…"
            ACTION_REPORT_WEEK -> "正在生成周报…"
            ACTION_INDEX -> "正在建语义索引，可以先去忙别的"
            else -> "处理中…"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_todo)
            .setContentTitle("AI 工作中")
            .setContentText(what)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun notifyDone(title: String, text: String) {
        val open = Intent(this, TodoActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_todo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(DONE_ID, n)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "AI 任务", NotificationManager.IMPORTANCE_LOW).apply {
            description = "AI 在后台整理待办、生成日报时显示"
            setShowBadge(false)
        }
        runCatching { nm.createNotificationChannel(ch) }
    }

    companion object {
        private const val CHANNEL = "ai_task"
        private const val FG_ID = 0x4101
        private const val DONE_ID = 0x4102

        const val ACTION_SCAN = "com.wxmsglog.ai.SCAN"
        const val ACTION_REPORT_DAY = "com.wxmsglog.ai.REPORT_DAY"
        const val ACTION_REPORT_WEEK = "com.wxmsglog.ai.REPORT_WEEK"
        const val ACTION_INDEX = "com.wxmsglog.ai.INDEX"

        fun start(context: Context, action: String) {
            val i = Intent(context, AiService::class.java).setAction(action)
            val ok = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
                true
            } catch (t: Throwable) {
                // Android 12+ 会拦「后台启动前台服务」，国产 ROM 更严。
                // 以前这里用 runCatching 把异常吞了，用户点了按钮什么都没发生，
                // 也不知道是失败了——现在至少给一条通知。
                android.util.Log.e("WxAi", "启动 AI 服务失败", t)
                false
            }
            if (!ok) notifyStartFailed(context, action)
        }

        /** 服务起不来时退而求其次：发条普通通知，让用户知道点了但没跑。 */
        private fun notifyStartFailed(context: Context, action: String) {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // 渠道可能还没建（服务没起来），这里兜一下
            runCatching {
                val sys = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (sys.getNotificationChannel(CHANNEL) == null) {
                    sys.createNotificationChannel(
                        NotificationChannel(CHANNEL, "AI 任务", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_todo)
                .setContentTitle("AI 没能启动")
                .setContentText("系统限制后台运行，打开 App 再点一次")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            runCatching { nm.notify(DONE_ID + 1, n) }
        }
    }
}
