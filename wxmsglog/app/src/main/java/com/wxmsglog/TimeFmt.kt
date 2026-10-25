package com.wxmsglog

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 时间的两种显示：
 * - friendly：列表里用。今天只显示 14:32，昨天显示「昨天 14:32」，再早才带日期。
 * - ago：状态行用。「12 分钟前」这种，用来判断监听是不是还活着。
 */
object TimeFmt {

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val MD = DateTimeFormatter.ofPattern("MM-dd")
    private val MDHM = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val YMDHM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun friendly(ts: Long): String {
        val zone = ZoneId.systemDefault()
        val dt = Instant.ofEpochMilli(ts).atZone(zone)
        val day = dt.toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            day == today -> dt.format(HM)
            day == today.minusDays(1) -> "昨天 " + dt.format(HM)
            day.year == today.year -> dt.format(MDHM)
            else -> dt.format(YMDHM)
        }
    }

    /** 距离现在多久。用于「最近一条：3 小时前」。 */
    fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return ""
        val diff = now - ts
        if (diff < 0) return "刚刚"
        val min = diff / 60_000L
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "$min 分钟前"
            min < 60 * 24 -> "${min / 60} 小时前"
            min < 60 * 24 * 30 -> "${min / (60 * 24)} 天前"
            else -> Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(YMD)
        }
    }

    fun day(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(YMD)

    private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /**
     * 待办的时间。「今天 14:00」「明天 09:30」「周五 18:00」。
     *
     * 不直接用 friendly()：那里今天只显示 14:00，放在待办上会分不清是今天还是
     * 上个月的 14:00；而待办最需要的就是「哪一天」。
     */
    fun due(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return ""
        val head = dayLabel(ts, now)
        if (head.isEmpty()) return ""
        return head + " " + Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(HM)
    }

    /**
     * 只到「天」的标签：「今天」「明天」「周五」「10-31」。
     * 待办候选按天分组时用这个当小节标题，不带时刻。
     */
    fun dayLabel(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return ""
        val zone = ZoneId.systemDefault()
        val dt = Instant.ofEpochMilli(ts).atZone(zone)
        val day = dt.toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val diff = ChronoUnit.DAYS.between(today, day)
        return when {
            diff == 0L -> "今天"
            diff == 1L -> "明天"
            diff == 2L -> "后天"
            diff == -1L -> "昨天"
            diff in 3L..6L -> WEEK[day.dayOfWeek.value - 1]
            day.year == today.year -> dt.format(MD)
            else -> dt.format(YMD)
        }
    }
}
