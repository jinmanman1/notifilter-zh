package com.wxmsglog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 待办提醒的接收端。四件事：
 * 1. 闹钟响了 → 弹通知 + 排下一轮强提醒
 * 2. 开机了 → 重排待办闹钟和 AI 定时任务（闹钟不跨重启保留）
 * 3. 通知上按「完成」→ 直接勾掉
 * 4. 通知上按「稍后」→ 推 10 分钟
 */
class TodoAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val db = MessageDb.get(context)

        if (action == Intent.ACTION_BOOT_COMPLETED) {
            Alarm.rescheduleAll(context, db)
            // AI 的定时聚合和日报闹钟也不跨重启，一并重排
            runCatching { AiSchedule.rescheduleAll(context) }
            return
        }

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        if (id <= 0L) return

        when (action) {
            ACTION_REMIND -> {
                // 闹钟排下之后用户可能已删掉或勾完，响的时候再确认一次
                val item = itemOf(db, id) ?: return
                if (item.done) return

                val round = intent.getIntExtra(EXTRA_ROUND, 0)
                TodoNotify.show(context, item, round)

                // 强提醒：这一轮没被处理就再排一轮。点开通知或勾完成都会把
                // 后续轮次取消，所以用户处理过就不会再响
                if (Prefs.strongAlarm(context) && round < Alarm.MAX_ROUND) {
                    Alarm.remindAgain(context, id, round + 1)
                }
            }

            ACTION_DONE -> {
                runCatching {
                    db.setTodoDone(id, true)
                    Alarm.cancel(context, id)
                }
                TodoNotify.dismiss(context, id)
            }

            ACTION_SNOOZE -> {
                Alarm.snooze(context, id)
                TodoNotify.dismiss(context, id)
            }
        }
    }

    private fun itemOf(db: MessageDb, id: Long): Todo? =
        runCatching { db.todos().firstOrNull { it.id == id } }.getOrNull()

    companion object {
        const val ACTION_REMIND = "com.wxmsglog.TODO_REMIND"
        const val ACTION_DONE = "com.wxmsglog.TODO_DONE"
        const val ACTION_SNOOZE = "com.wxmsglog.TODO_SNOOZE"
        const val EXTRA_ID = "todo_id"
        const val EXTRA_ROUND = "todo_round"
    }
}
