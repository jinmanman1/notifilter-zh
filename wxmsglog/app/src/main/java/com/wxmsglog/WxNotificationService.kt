package com.wxmsglog

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 核心：监听通知并落库。
 *
 * 注意点：
 * 1. 聊天类应用会给同一个通知反复更新（同 key），onNotificationPosted 会被多次调用，
 *    所以必须去重，否则一条消息会被存成十几条。
 * 2. 群组汇总通知（FLAG_GROUP_SUMMARY）本身不带内容，跳过即可，
 *    子通知已经各自记录了。
 * 3. 全部写入放到单线程里串行执行，避免开多条连接。
 */
class WxNotificationService : NotificationListenerService() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        // 服务被系统重建时顺手清一次旧记录；真正是否到期由 Maintenance 判断
        worker.execute {
            runCatching {
                val db = MessageDb.get(this)
                Maintenance.pruneIfDue(this, db)
                // 通知状态表跟保留天数无关，单独按「30 天没再出现过」清理
                db.deleteStaleStates(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val status = sbn ?: return
        try {
            val pkg = status.packageName ?: return
            if (!Prefs.enabled(this, pkg)) return

            val notification = status.notification ?: return
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

            val postedAt = if (notification.`when` > 0L) notification.`when` else System.currentTimeMillis()
            val items = WxParse.extract(notification, postedAt, pkg, AppInfo.label(this, pkg))
            if (items.isEmpty()) return

            // 按通知 key 隔离状态：微信每个会话是一个 tag，正好一个会话一份。
            // 用 key 而不是 id —— 同一条消息改个标题重发，id 会变但 key 不变。
            val nkey = status.key?.takeIf { it.isNotEmpty() } ?: "$pkg|${status.id}"

            worker.execute {
                try {
                    val db = MessageDb.get(this)
                    val r = db.insertBatch(nkey, items, pkg)
                    // 空壳通知不入库，只累计一个数，界面上提示用户去开消息详情
                    if (r.placeholders > 0) Prefs.bumpPlaceholder(this, r.placeholders)
                    if (r.added > 0 || r.placeholders > 0) Bus.notifyChanged()
                } catch (t: Throwable) {
                    Log.w(TAG, "写入失败", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "解析失败", t)
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WxMsgLog"
    }
}
