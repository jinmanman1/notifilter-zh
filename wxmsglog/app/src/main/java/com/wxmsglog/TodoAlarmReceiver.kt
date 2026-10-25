package com.wxmsglog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 待办提醒的接收端。两件事：
 * 1. 闹钟响了 → 弹通知
 * 2. 开机了 → 把所有未完成待办的闹钟重排一遍（闹钟不跨重启保留）
 */
class TodoAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val db = MessageDb.get(context)

        if (action == Intent.ACTION_BOOT_COMPLETED) {
            Alarm.rescheduleAll(context, db)
            return
        }
        if (action != ACTION_REMIND) return

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        if (id <= 0L) return
        // 闹钟排下之后用户可能已删掉或勾完，响的时候再确认一次
        val item = runCatching { db.todos().firstOrNull { it.id == id } }.getOrNull() ?: return
        if (item.done) return
        TodoNotify.show(context, item)
    }

    companion object {
        const val ACTION_REMIND = "com.wxmsglog.TODO_REMIND"
        const val EXTRA_ID = "todo_id"
    }
}
