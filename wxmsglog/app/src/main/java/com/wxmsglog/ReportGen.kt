package com.wxmsglog

import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日报 / 周报生成。
 *
 * 分两层：
 *   1. **统计层**——条数、来源分布、这段时间记下的待办。这一层不依赖模型，
 *      没装模型也能出报告，只是没有「要点」那一段。
 *   2. **摘要层**——把消息压成几句话。这层才需要模型，而且必须限长：
 *      1.7B 的上下文只有 2048 token，约 1500 汉字，塞多了它就开始胡说。
 *
 * 宁可少喂几条，也不要喂一坨让它总结出错误结论。
 */
object ReportGen {

    private const val TAG = "WxReport"

    /** 喂给模型的消息条数上限。 */
    private const val MAX_FEED = 30

    /** 单条喂进去前截断到多少字。 */
    private const val FEED_CLIP = 40

    /** 摘要输出上限。 */
    private const val MAX_TOKENS = 220

    private const val KIND_DAY = "day"
    private const val KIND_WEEK = "week"

    fun generateDay(context: Context, date: LocalDate = LocalDate.now()): Report? {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return generate(context, KIND_DAY, from, to)
    }

    /** 最近 7 天（含今天）。 */
    fun generateWeek(context: Context, endDate: LocalDate = LocalDate.now()): Report? {
        val zone = ZoneId.systemDefault()
        val from = endDate.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return generate(context, KIND_WEEK, from, to)
    }

    /**
     * 生成并入库。阻塞调用，必须在工作线程。
     * 这段时间一条消息都没有时返回 null，不留空报告。
     */
    fun generate(context: Context, kind: String, from: Long, to: Long): Report? {
        val db = MessageDb.get(context)

        val msgs = try {
            db.queryFiltered(from = from, to = to, limit = 400, desc = false)
        } catch (t: Throwable) {
            return null
        }
        if (msgs.isEmpty()) return null

        val stat = statistics(context, msgs)
        val todos = try {
            db.todosBetween(from, to)
        } catch (t: Throwable) {
            emptyList<Todo>()
        }

        val highlights = summarize(context, msgs, from, to)

        val body = buildString {
            append(stat.header).append("\n\n")
            if (highlights.isNotEmpty()) {
                append("要点\n")
                for (h in highlights) append("· ").append(h).append('\n')
                append('\n')
            }
            append("来源\n").append(stat.sources).append('\n')
            if (todos.isNotEmpty()) {
                append("\n待办\n")
                for (t in todos.take(10)) {
                    append("· ").append(t.text)
                    if (t.due > 0L) append("（").append(dueText(t.due)).append("）")
                    if (t.done) append(" 已完成")
                    append('\n')
                }
            }
        }.trim()

        try {
            db.saveReport(kind, from, to, body)
        } catch (t: Throwable) {
            Log.e(TAG, "存报告失败", t)
            return null
        }
        return Report(0L, kind, from, to, body, System.currentTimeMillis())
    }

    // ---------- 统计层 ----------

    private class Stat(val header: String, val sources: String)

    private fun statistics(context: Context, msgs: List<Message>): Stat {
        val byPkg = LinkedHashMap<String, Int>()
        for (m in msgs) {
            val label = if (m.pkg.isEmpty()) "其他" else AppInfo.label(context, m.pkg)
            byPkg[label] = (byPkg[label] ?: 0) + 1
        }
        val range = "${dayText(msgs.first().ts)} · 共 ${msgs.size} 条"
        val sources = byPkg.entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString("、") { "${it.key} ${it.value} 条" }
        return Stat(range, sources.ifEmpty { "—" })
    }

    // ---------- 摘要层 ----------

    /**
     * 让模型把消息压成几句要点。模型不可用时返回空列表，调用方照常出统计报告。
     */
    private fun summarize(context: Context, msgs: List<Message>, from: Long, to: Long): List<String> {
        val model = ModelStore.current(context) ?: return emptyList()
        if (Llm.load(model) != null) return emptyList()

        // 采样：消息多的时候等距抽，比只取开头更能代表整段时间
        val picked = if (msgs.size <= MAX_FEED) {
            msgs
        } else {
            val step = msgs.size.toDouble() / MAX_FEED
            (0 until MAX_FEED).map { msgs[(it * step).toInt().coerceIn(0, msgs.lastIndex)] }
        }.distinctBy { it.id }

        val feed = picked.joinToString("\n") { m ->
            val who = m.sender.ifBlank { m.pkg.ifBlank { "未知" } }
            val body = m.body.replace('\n', ' ').let {
                if (it.length > FEED_CLIP) it.substring(0, FEED_CLIP) + "…" else it
            }
            "$who: $body"
        }

        val prompt = buildString {
            append("<|im_start|>system\n")
            append("你是消息摘要助手。把一段聊天记录压成三到六条要点，")
            append("每条一行，以「· 」开头，只写发生了什么和需要做什么，不要解释，不要复述原话。")
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            append(dayText(from)).append(" 的消息：\n").append(feed)
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
            // 关掉 Qwen3 的思考段
            append("<").append("/think>\n\n")
        }

        val raw = try {
            Llm.generate(prompt, null, MAX_TOKENS)
        } catch (t: Throwable) {
            Log.e(TAG, "摘要失败", t)
            null
        } ?: return emptyList()

        return parseBullets(raw)
    }

    /** 把模型输出收拾成干净的要点列表。它有时会带上自己的编号或解释，这里只留·开头的。 */
    private fun parseBullets(raw: String): List<String> {
        val out = ArrayList<String>()
        for (line in raw.split('\n')) {
            var s = line.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("·") || s.startsWith("•") || s.startsWith("-")) {
                s = s.substring(1).trim()
            } else if (s.first().isDigit() && s.length > 2 && (s[1] == '.' || s[1] == '、')) {
                s = s.substring(2).trim()
            } else {
                continue
            }
            if (s.length < 4) continue
            if (s.length > 80) s = s.substring(0, 80) + "…"
            out.add(s)
            if (out.size >= 6) break
        }
        return out
    }

    // ---------- 小工具 ----------

    private fun dayText(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("M月d日"))

    private fun dueText(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("M-d HH:mm"))
}
