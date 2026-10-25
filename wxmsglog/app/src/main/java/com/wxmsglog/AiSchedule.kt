package com.wxmsglog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 定时排程：自动聚合、日报、周报。
 *
 * 全部用 setAndAllowWhileIdle 而不是精确闹钟——这几件事早跑十分钟晚跑十分钟
 * 都不影响，用精确闹钟反而要去申请特殊权限、还更费电。真正需要准点的是待办
 * 提醒，那个在 [Alarm] 里单独处理。
 *
 * 每次只排「下一次」。跑完（或开机、或用户改设置）再重排一次。
 * 不用 setRepeating，因为 Doze 下重复闹钟会被攒到一起，行为不可预期。
 */
object AiSchedule {

    private const val REQ_SCAN = 0x4201
    private const val REQ_DAY = 0x4202
    private const val REQ_WEEK = 0x4203

    const val ACTION_SCAN = "com.wxmsglog.ai.ALARM_SCAN"
    const val ACTION_DAY = "com.wxmsglog.ai.ALARM_DAY"
    const val ACTION_WEEK = "com.wxmsglog.ai.ALARM_WEEK"

    /** 设置变了、开机、或某次跑完之后调用，把三类任务全部重排。 */
    fun rescheduleAll(context: Context) {
        scheduleScan(context)
        scheduleReport(context, ACTION_DAY)
        scheduleReport(context, ACTION_WEEK)
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (action in listOf(ACTION_SCAN, ACTION_DAY, ACTION_WEEK)) {
            runCatching { am.cancel(pending(context, action, 0L)) }
        }
    }

    // ---------- 自动聚合 ----------

    private fun scheduleScan(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, ACTION_SCAN, REQ_SCAN.toLong())
        am.cancel(pi)

        // 没开自动、或者没装模型，就不排了
        if (!Prefs.aiAutoEnabled(context)) return
        if (ModelStore.current(context) == null) return

        val hours = Prefs.aiAutoHours(context).coerceIn(1, 48)
        val at = System.currentTimeMillis() + hours * 3600_000L
        put(am, at, pi)
    }

    /** 一次跑完之后，从「现在」重新算下一次。 */
    fun afterScan(context: Context) = scheduleScan(context)

    // ---------- 日报 / 周报 ----------

    private fun scheduleReport(context: Context, action: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val req = if (action == ACTION_DAY) REQ_DAY else REQ_WEEK
        val pi = pending(context, action, req.toLong())
        am.cancel(pi)

        val enabled = if (action == ACTION_DAY) {
            Prefs.reportDayEnabled(context)
        } else {
            Prefs.reportWeekEnabled(context)
        }
        if (!enabled) return
        if (ModelStore.current(context) == null) return

        val at = nextRun(context, action)
        put(am, at, pi)
    }

    /** 算出下一次该跑的时刻。今天这个点已经过了就明天（周报是下周同一天）。 */
    fun nextRun(context: Context, action: String, now: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val current = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)

        return if (action == ACTION_DAY) {
            val hour = Prefs.reportDayHour(context)
            var at = LocalDateTime.of(current.toLocalDate(), LocalTime.of(hour, 0))
            if (!at.isAfter(current)) at = at.plusDays(1)
            at.atZone(zone).toInstant().toEpochMilli()
        } else {
            val targetDow = Prefs.reportWeekDay(context) // 1=周一 … 7=周日
            val hour = Prefs.reportWeekHour(context)
            var date = current.toLocalDate()
            // 找下一个目标星期几
            while (date.dayOfWeek.value != targetDow) date = date.plusDays(1)
            var at = LocalDateTime.of(date, LocalTime.of(hour, 0))
            if (!at.isAfter(current)) at = at.plusWeeks(1)
            at.atZone(zone).toInstant().toEpochMilli()
        }
    }

    /** 跑完之后立刻排下一次，避免「只跑一次就再也不动了」。 */
    fun afterReport(context: Context, action: String) {
        scheduleReport(context, action)
    }

    // ---------- 底层 ----------

    private fun pending(context: Context, action: String, req: Long): PendingIntent {
        val i = Intent(context, AiAlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            req.toInt(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun put(am: AlarmManager, at: Long, pi: PendingIntent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }
}

/** 日报是哪一天：跑的时候取当天。放在这里方便测试替换。 */
internal fun reportDayOf(now: Long): LocalDate =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.systemDefault()).toLocalDate()
