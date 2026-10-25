package com.wxmsglog

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 核心：监听通知并落库。
 *
 * 注意点：
 * 1. 微信会给同一个通知反复更新（同 key），onNotificationPosted 会被多次调用，
 *    所以必须去重，否则一条消息会被存成十几条。
 * 2. 群组汇总通知（FLAG_GROUP_SUMMARY）本身不带内容，跳过即可，
 *    子通知已经各自记录了。
 * 3. 全部写入放到单线程里串行执行，避免开多条连接。
 */
class WxNotificationService : NotificationListenerService() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val status = sbn ?: return
        try {
            if (!Prefs.recordAll(this) && status.packageName != Prefs.WECHAT) return

            val notification = status.notification ?: return
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

            val postedAt = if (notification.`when` > 0L) notification.`when` else System.currentTimeMillis()
            val items = WxParse.extract(notification, postedAt)
            if (items.isEmpty()) return

            worker.execute {
                try {
                    val db = MessageDb.get(this)
                    var added = 0
                    for (item in items) {
                        if (db.insert(item.ts, item.sender, item.body, item.hash)) added++
                    }
                    if (added > 0) Bus.notifyChanged()
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
