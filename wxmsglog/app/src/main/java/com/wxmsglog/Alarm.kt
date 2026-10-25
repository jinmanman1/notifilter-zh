package com.wxmsglog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 *
 * 强提醒是「多轮」的：第一轮响完没被处理，隔 5 分钟再来一轮，最多三轮。
 * 每轮用不同的 requestCode 和 data URI，不然后一轮会把前一轮的 PendingIntent
 * 覆盖掉，最终只有最后一轮能响。
 */
object Alarm {

    /** 强提醒最多再补几轮。三轮之后就不再骚扰了，剩下的交给通知栏。 */
    const val MAX_ROUND = 3

    /** 两轮之间隔多久。 */
    const val ROUND_GAP_MS = 5 * 60 * 1000L

    fun schedule(context: Context, id: Long, at: Long, round: Int = 0) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, id, round)
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

    /** 强提醒的下一轮。 */
    fun remindAgain(context: Context, id: Long, nextRound: Int) {
        if (nextRound > MAX_ROUND) return
        schedule(context, id, System.currentTimeMillis() + ROUND_GAP_MS, nextRound)
    }

    /** 「稍后提醒」：从通知上按的，固定推 10 分钟，不占用强提醒的轮次。 */
    fun snooze(context: Context, id: Long) {
        schedule(context, id, System.currentTimeMillis() + 10 * 60 * 1000L, 0)
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 所有轮次都要取消，只取消第 0 轮的话强提醒还会继续响
        for (round in 0..MAX_ROUND) {
            runCatching { am.cancel(pending(context, id, round)) }
        }
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

    private fun pending(context: Context, id: Long, round: Int): PendingIntent {
        val intent = Intent(context, TodoAlarmReceiver::class.java).apply {
            action = TodoAlarmReceiver.ACTION_REMIND
            putExtra(TodoAlarmReceiver.EXTRA_ID, id)
            putExtra(TodoAlarmReceiver.EXTRA_ROUND, round)
            // 不同待办、以及同一待办的不同轮次，都必须拿到不同的 PendingIntent，
            // 否则后一个会把前一个覆盖掉
            data = Uri.parse("wxmsglog://todo/$id/$round")
        }
        return PendingIntent.getBroadcast(
            context,
            ((id and 0x7FFFFFFFL).toInt()) + round * 100_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
