package com.wxmsglog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 待办提醒的闹钟。
 *
 * 用的是 AlarmManager + 一个 BroadcastReceiver，不引 WorkManager——
 * WorkManager 的最小延迟是 15 分钟，做不到「下午三点提醒我」这种精度。
 *
 * 精确闹钟在 Android 12 之后要用户单独授权（SCHEDULE_EXACT_ALARM），
 * 没授权就自动降级成不精确的闹钟：可能晚几分钟，但不会不响。
 * 国产 ROM 还有后台限制，闹钟被推迟是常态，这点解决不了，只能尽量。
 */
object Alarm {

    fun schedule(context: Context, id: Long, at: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, id)
        // 时间已经过去就不排了，否则系统会立刻回调一次
        if (at <= System.currentTimeMillis()) {
            am.cancel(pi)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (t: Throwable) {
            // 个别 ROM 会直接抛 SecurityException，退到最普通的闹钟
            runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pending(context, id)) }
    }

    /**
     * 把所有未完成待办的闹钟重排一遍。
     *
     * 什么时候需要：开机、应用更新、闹钟被系统清掉之后。AlarmManager 的
     * 闹钟在重启后不会保留，不重排就永远不响了。
     */
    fun rescheduleAll(context: Context, db: MessageDb) {
        runCatching {
            for (t in db.pendingTodos()) {
                schedule(context, t.id, t.due)
            }
        }
    }

    private fun pending(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, TodoAlarmReceiver::class.java).apply {
            action = TodoAlarmReceiver.ACTION_REMIND
            putExtra(TodoAlarmReceiver.EXTRA_ID, id)
            // 不同待办必须拿到不同的 PendingIntent，否则后一个会覆盖前一个
            data = android.net.Uri.parse("wxmsglog://todo/$id")
        }
        return PendingIntent.getBroadcast(
            context,
            (id and 0x7FFFFFFFL).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
