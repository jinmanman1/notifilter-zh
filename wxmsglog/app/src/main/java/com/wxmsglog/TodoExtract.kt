package com.wxmsglog

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 从消息里按规则挑出可能的待办。
 *
 * 两条判据：句子里有没有「时间表达」，有没有「祈使 / 交付类词」。
 * 两个信号加权，够分才算候选。
 *
 * 有几件事是刻意的：
 * - **不猜**。没有时间表达就不给时间，不按「今天」填充。给一个错的截止日比不给更糟。
 * - **相对时间按消息发生的时刻算**，不是按现在。三天前说的「明天交」指三天前那天的明天。
 * - 只挑「可能要你做事」的句子，纯陈述、纯问句（不带祈使词）全部跳过。
 */
object TodoExtract {

    /**
     * 一条候选。due = 0 表示没在原文里找到时间。
     *
     * 字段是可变的：候选页里长按能改时间、点一下能勾选，
     * 改完还要能重排，所以状态就放在候选自己身上，不再另开一个平行数组
     * （平行数组在重排后索引会错位，这里踩过一次）。
     */
    class Cand(
        val msgId: Long,
        val text: String,
        var due: Long,
        /** 按消息时间算出来已经过去了，界面标红，默认不勾。 */
        var past: Boolean,
        val sender: String,
        val ts: Long,
    ) {
        /** 有时间、且时间还没过的默认勾上；其余的留给你自己点。 */
        var picked: Boolean = due > 0L && !past
    }

    /** 候选门槛。低于这个分数连列都不列，免得列表里全是噪音。 */
    private const val SCORE_MIN = 3

    /** 太短的句子不可能是待办（「好的」「收到」）。 */
    private const val MIN_LEN = 5

    /** 单条待办正文上限，超了截断。 */
    private const val MAX_TEXT = 60

    // ---------- 词表 ----------

    private val STRONG = arrayOf(
        "记得", "别忘了", "别忘记", "务必", "一定要", "必须",
        "麻烦", "帮我", "帮忙", "请你", "需要你", "你去",
        "尽快", "马上", "赶紧",
        "发我", "发给我", "发过来", "发一下", "给我", "回复我", "回我", "回一下",
        "提交", "上报", "报给我", "截止", "deadline",
        "确认一下", "核对", "跟进", "安排一下", "准备一下", "带过来", "带上",
    )

    /**
     * 动作词。单靠它不算待办，得配上时间才能凑够分——
     * 否则「明天降温了」这种纯陈述也会被列进来。
     *
     * 一律用两个以上的字：「交」「去」「做」这种单字命中率太高，
     * 「明天交通管制」会被当成待办。
     */
    private val WEAK = arrayOf(
        "开会", "开个会", "见面", "碰头", "碰一下", "对稿", "面试",
        "交材料", "交报告", "交作业", "带上", "带过去", "送过去",
        "发一下", "发过去", "回一下", "报一下", "看一下", "去一趟",
        "准备", "安排", "确认", "核对", "联系", "处理", "整理", "汇总",
        "报销", "付款", "打款", "取件", "打电话", "视频", "培训", "体检",
        "电话", "会议", "聚餐", "吃饭", "航班", "高铁", "机票", "车票", "快递",
    )

    private val NOISE_PARTS = arrayOf(
        "撤回了一条消息", "你已添加", "以上是打招呼", "消息已发出",
        "语音通话", "视频通话", "你收到一条新消息", "拍一拍",
        "开启了朋友验证", "对方正在输入",
    )

    /** 整句只有表情 / 图片 / 语音这类占位符。 */
    private val MEDIA_ONLY = Regex("^(\\s*\\[[^\\]]{1,8}\\]\\s*)+$")

    // ---------- 时间正则 ----------

    private val FULL_DATE = Regex("(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})")
    /** 「02-28」这种简写。要求两段都是两位数，否则「2-3 个人」会被当成 2 月 3 日。 */
    private val SHORT_DATE = Regex("(?<!\\d)(\\d{2})\\s*[-/]\\s*(\\d{2})(?!\\d)")
    private val MONTH_DAY = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]")
    private val REL_DAY = Regex("(大后天|后天|明天|明日|明早|明晚|明儿|今天|今日|今早|今晚|今儿)")
    private val IN_DAYS = Regex("(\\d{1,2})\\s*天(?:以)?后")
    private val WEEK = Regex("(下下|下|本|这)?\\s*(?:周|星期|礼拜)\\s*([一二三四五六日天1-7])")
    private val WEEKEND = Regex("(下下|下|本|这)?\\s*周末")
    private val MONTH_END = Regex("(?:本月|这个月|这月)?\\s*月(?:底|末)")
    private val MONTH_START = Regex("(?:本月|这个月|这月)?\\s*月(?:初|头)")

    private val TIME = Regex(
        "(今晚|明晚|今早|明早|上午|早上|早晨|中午|下午|傍晚|晚上|夜里|凌晨|晚|早)?\\s*" +
            "(\\d{1,2}|[零一二三四五六七八九十两]{1,2})\\s*[点:：时]\\s*(\\d{1,2})?\\s*(半)?"
    )

    /** 「周五前」「月底前」「02-28 前」「截止」这类，说明时间是个截止点，默认取当天 18:00 而不是 09:00。 */
    private val DEADLINE = Regex(
        "(?:之前|以前|截止|deadline)|[日号天底\\d]\\s*前|(?:周|星期|礼拜)[一二三四五六日天]\\s*前"
    )

    // ---------- 入口 ----------

    fun extract(messages: List<Message>, now: Long = System.currentTimeMillis()): List<Cand> {
        val out = ArrayList<Cand>()
        for (m in messages) {
            val body = m.body.trim()
            if (body.isEmpty()) continue
            for (s in sentences(body)) {
                val c = judge(m, s, now) ?: continue
                out.add(c)
            }
        }
        // 同一段文字只留一条：一条通知里重复出现的句子、或者同一条被拆出来的重复片段
        val uniq = LinkedHashMap<String, Cand>()
        for (c in out) if (!uniq.containsKey(c.text)) uniq[c.text] = c
        return sort(uniq.values)
    }

    /** 有时间的一律在前，按时间升序；没时间的按消息时间倒序垫底。改过时间后重排也走这里。 */
    fun sort(list: Collection<Cand>): List<Cand> =
        list.sortedWith(compareBy<Cand>({ if (it.due > 0L) 0 else 1 }, { it.due }, { -it.ts }))

    // ---------- 单句判分 ----------

    private fun judge(m: Message, raw: String, now: Long): Cand? {
        val s = raw.trim()
        if (s.length < MIN_LEN) return null
        if (isNoise(s)) return null

        val strong = STRONG.any { s.contains(it) }
        val weak = !strong && WEAK.any { s.contains(it) }
        val deadline = DEADLINE.containsMatchIn(s)
        // 三样都没有，就是一句普通聊天，哪怕带了时间也不列
        if (!strong && !weak && !deadline) return null

        val due = parseDue(s, m.ts, deadline)

        var score = if (due > 0L) 2 else 0
        score += if (strong) 3 else if (weak) 1 else 0
        // 「周五前」「02-28 前」这种明确截止，本身就是个强信号
        if (deadline && due > 0L) score += 1
        // 问句只要没带祈使词就压下去：「明天几点集合？」不是待办
        if (isQuestion(s) && !strong) score -= 2
        if (score < SCORE_MIN) return null

        return Cand(m.id, clip(s), due, due in 1L until now, m.sender, m.ts)
    }

    private fun isNoise(s: String): Boolean {
        if (MEDIA_ONLY.matches(s)) return true
        for (n in NOISE_PARTS) if (s.contains(n)) return true
        return false
    }

    private fun isQuestion(s: String): Boolean {
        val t = s.trim()
        if (t.endsWith("？") || t.endsWith("?")) return true
        if (t.endsWith("吗") || t.endsWith("呢") || t.endsWith("么")) return true
        return t.contains("能不能") || t.contains("可不可以") || t.contains("要不要")
    }

    private fun clip(s: String): String =
        if (s.length <= MAX_TEXT) s else s.substring(0, MAX_TEXT) + "…"

    /**
     * 切句。只在句号、分号、换行处切——问号和叹号留在句子里，
     * 因为 [isQuestion] 要靠句尾的「？」判断。
     */
    private fun sentences(body: String): List<String> =
        body.split('\n', '。', '；', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ---------- 时间解析 ----------

    /**
     * 把一句话里的时间表达解析成时间戳。返回 0 表示没找到。
     *
     * @param baseTs   消息本身的时间。相对表达（明天、周五）以它为基准。
     * @param deadline 句子里有截止含义时，只有日期没时刻的情况按 18:00 算。
     */
    private fun parseDue(text: String, baseTs: Long, deadline: Boolean): Long {
        val zone = ZoneId.systemDefault()
        val base = Instant.ofEpochMilli(baseTs).atZone(zone)
        val baseDate = base.toLocalDate()
        val baseTime = base.toLocalTime()

        val date = findDate(text, baseDate)
        val time = findTime(text)
        if (date == null && time == null) return 0L

        val day = date ?: baseDate
        val tt = time ?: if (deadline) LocalTime.of(18, 0) else LocalTime.of(9, 0)
        var ldt = LocalDateTime.of(day, tt)

        // 只说了时刻没说哪天：今天这个点已经过了，就顺延到明天
        if (date == null && !ldt.isAfter(LocalDateTime.of(baseDate, baseTime))) {
            ldt = ldt.plusDays(1)
        }
        return ldt.atZone(zone).toInstant().toEpochMilli()
    }

    private fun findDate(text: String, base: LocalDate): LocalDate? {
        FULL_DATE.find(text)?.let { m ->
            val y = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toIntOrNull()
            val d = m.groupValues[3].toIntOrNull()
            if (y != null && mo != null && d != null) {
                val hit = runCatching { LocalDate.of(y, mo, d) }.getOrNull()
                if (hit != null) return hit
            }
        }

        SHORT_DATE.find(text)?.let { m ->
            val mo = m.groupValues[1].toIntOrNull()
            val d = m.groupValues[2].toIntOrNull()
            if (mo != null && d != null) {
                val hit = runCatching { LocalDate.of(base.year, mo, d) }.getOrNull()
                if (hit != null) return rollYear(hit, base)
            }
        }

        MONTH_DAY.find(text)?.let { m ->
            val mo = m.groupValues[1].toIntOrNull()
            val d = m.groupValues[2].toIntOrNull()
            if (mo != null && d != null) {
                val hit = runCatching { LocalDate.of(base.year, mo, d) }.getOrNull()
                if (hit != null) return rollYear(hit, base)
            }
        }

        REL_DAY.find(text)?.let { m ->
            return when (m.groupValues[1]) {
                "大后天" -> base.plusDays(3)
                "后天" -> base.plusDays(2)
                "明天", "明日", "明早", "明晚", "明儿" -> base.plusDays(1)
                else -> base
            }
        }

        IN_DAYS.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..60) return base.plusDays(n.toLong())
        }

        WEEK.find(text)?.let { m ->
            val pre = m.groupValues[1]
            val idx = weekIndex(m.groupValues[2])
            if (idx != null) {
                val weeks = when (pre) {
                    "下" -> 1L
                    "下下" -> 2L
                    else -> 0L
                }
                var d = base.with(DayOfWeek.MONDAY).plusWeeks(weeks).plusDays((idx - 1).toLong())
                // 没写「下周」时，说的是本周的某天；如果那天已经过了，按下一周算
                if (isThisWeek(pre) && d.isBefore(base)) d = d.plusWeeks(1)
                return d
            }
        }

        WEEKEND.find(text)?.let { m ->
            val pre = m.groupValues[1]
            val weeks = when (pre) {
                "下" -> 1L
                "下下" -> 2L
                else -> 0L
            }
            var d = base.with(DayOfWeek.SATURDAY).plusWeeks(weeks)
            if (isThisWeek(pre) && d.isBefore(base)) d = d.plusWeeks(1)
            return d
        }

        MONTH_END.find(text)?.let { return base.with(TemporalAdjusters.lastDayOfMonth()) }
        MONTH_START.find(text)?.let { return base.withDayOfMonth(1).plusMonths(1) }

        return null
    }

    private fun isThisWeek(pre: String): Boolean = pre.isEmpty() || pre == "本" || pre == "这"

    /**
     * 没写年份的日期（「10月1日」「02-28」）跨年处理：
     * 已经过去一周以上就当作明年，避免生成一条八个月前的待办；
     * 一周之内的按过去算，标红提醒你已经错过。
     */
    private fun rollYear(hit: LocalDate, base: LocalDate): LocalDate =
        if (ChronoUnit.DAYS.between(hit, base) > 7L) hit.plusYears(1) else hit

    private fun weekIndex(ch: String): Int? {
        val idx = when (ch) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日", "天", "7" -> 7
            else -> ch.toIntOrNull()
        }
        return if (idx != null && idx in 1..7) idx else null
    }

    private fun findTime(text: String): LocalTime? {
        val m = TIME.find(text) ?: return null
        val period = m.groupValues[1]
        val h0 = cnInt(m.groupValues[2]) ?: return null
        if (h0 > 23) return null
        val minute = if (m.groupValues[4] == "半") 30 else (cnInt(m.groupValues[3]) ?: 0)
        if (minute > 59) return null

        var h = h0
        when (period) {
            "下午", "傍晚", "晚上", "夜里", "中午", "今晚", "明晚", "晚" ->
                if (h < 12) h += 12
            "凌晨", "早上", "上午", "今早", "明早", "早" -> if (h == 12) h = 0
            // 没写时段时，1—6 点几乎都指下午（聊天里没人说「三点」指凌晨三点）
            else -> if (h in 1..6) h += 12
        }
        if (h > 23) return null
        return LocalTime.of(h, minute)
    }

    /** 阿拉伯数字直接转；中文数字支持到 59（十二 / 二十 / 二十五）。 */
    private fun cnInt(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return it }
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        if (t.length == 1) return digits[t[0]]
        val ten = t.indexOf('十')
        if (ten < 0) return null
        val hi = if (ten == 0) 1 else digits[t[0]] ?: return null
        val lo = if (ten == t.length - 1) 0 else digits[t[ten + 1]] ?: return null
        return hi * 10 + lo
    }
}
