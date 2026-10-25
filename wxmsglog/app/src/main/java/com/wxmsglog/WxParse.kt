package com.wxmsglog

import android.app.Notification
import androidx.core.app.NotificationCompat
import java.security.MessageDigest

/**
 * 从一条通知里解析出来的一条消息。
 *
 * fp 是「内容指纹」：只由 包名 + 发信人 + 正文 决定，不含时间。
 * 去重靠它——聊天应用会把同一个会话通知反复重发，内容是累积的，
 * 只有指纹稳定，才分得清「这几条早就存过了」和「这条是新的」。
 */
class Parsed(
    val ts: Long,
    val sender: String,
    val body: String,
    val fp: String,
    /** true 表示只是「你收到一条新消息」这类空壳，不该进库。 */
    val placeholder: Boolean = false,
)

/**
 * 通知内容的解析。
 *
 * 优先读 MessagingStyle——聊天类应用放多条消息的地方，能一次拿到
 * 「谁、什么时候、说了什么」，时间戳精确，去重最可靠。
 *
 * 拿不到就退回标题 + 正文，这时要做两件事：
 * 1. 按行拆开。群聊的累积通知常常是「张三: 内容\n李四: 内容」拼成一段，
 *    整段存成一条就等于把人名和内容一起塞进了正文，AI 分不清谁说的。
 * 2. 拆完逐条给指纹，让上层的多重集去重能只取增量。
 */
object WxParse {

    private const val FALLBACK_SENDER = "通知"

    /** 发信人名最长这么多个字，超过就不是人名了，是句子。 */
    private const val MAX_NAME_LEN = 24

    /** 一个通知里最多认这么多行，防止某个应用塞几百行把库撑爆。 */
    private const val MAX_LINES = 40

    /**
     * 空壳通知的字符白名单。「你收到一条新消息」「3条新消息」这类模板
     * 全部由这些字构成；只要混进别的字，就当成真消息。这样用户真发一句
     * 「收到」不会被误杀（不含「消息」二字）。
     */
    private const val PH_CHARS = "你您收到了有未读新条消息的数几多两一二三四五六七八九十通知微信"

    /** 正文里「XX:」很常见的开头词，这些不是人名。 */
    private val NOT_NAMES = setOf(
        "注意", "提示", "备注", "说明", "时间", "地点", "主题", "标题",
        "内容", "声明", "公告", "警告", "温馨", "提醒", "重要",
    )

    private val PH_SPACE = Regex("\\s")
    private val PH_PUNCT = Regex("[\\p{Punct}，。！？、；：\"''（）【】《》…—·]")

    /** 标题尾部的未读数：「张三(3)」「张三（3条消息）」「张三 3条新消息」。 */
    private val UNREAD_TAIL = Regex("[\\s]*[（(]\\s*(\\d{1,2})(?:\\s*条(?:新?消息)?)?\\s*[)）]\\s*$")
    private val UNREAD_WORD = Regex("\\s+(\\d{1,2})\\s*条新?消息\\s*$")

    /**
     * @param pkg 来源应用包名，参与指纹计算，避免两个应用内容相同时互相干扰
     * @param appLabel 应用显示名，标题为空时的兜底发信人
     */
    fun extract(n: Notification, postedAt: Long, pkg: String, appLabel: String): List<Parsed> {
        val extras = n.extras
        val title = stripUnread(
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        )
        val app = appLabel.trim().ifEmpty { FALLBACK_SENDER }

        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull()

        if (style != null && style.messages.isNotEmpty()) {
            val convTitle = stripUnread(style.conversationTitle?.toString().orEmpty())
            val out = ArrayList<Parsed>(style.messages.size)
            for (m in style.messages) {
                val text = m.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val ts = if (m.timestamp > 0L) m.timestamp else postedAt
                val person = stripUnread(m.person?.name?.toString().orEmpty())
                if (person.isNotEmpty()) {
                    out.add(make(ts, person, text, pkg))
                } else {
                    // 拿不到发信人时，正文里可能自带「张三: 内容」
                    val pair = splitSpeaker(text)
                    val sender = pair?.first
                        ?: convTitle.takeIf { it.isNotEmpty() }
                        ?: title.takeIf { it.isNotEmpty() }
                        ?: app
                    out.add(make(ts, sender, pair?.second ?: text, pkg))
                }
            }
            if (out.isNotEmpty()) return out
        }

        // 退回：正文可能分散在 BIG_TEXT / TEXT / TEXT_LINES 里
        val lines = collectLines(extras)
        if (lines.isEmpty()) return emptyList()

        val capped = if (lines.size > MAX_LINES) {
            lines.subList(lines.size - MAX_LINES, lines.size)
        } else {
            lines
        }

        // 只有「多行 + 半数以上带名字前缀」才拆。单条长消息里的换行
        // 不该被切成好几条，那会把一句话拆成七八条记录。
        val named = capped.count { splitSpeaker(it) != null }
        val shouldSplit = capped.size >= 2 && named * 2 >= capped.size

        val fallbackSender = title.takeIf { it.isNotEmpty() } ?: app
        if (!shouldSplit) {
            return listOf(make(postedAt, fallbackSender, capped.joinToString("\n"), pkg))
        }

        val names = ArrayList<String>(capped.size)
        val bodies = ArrayList<StringBuilder>(capped.size)
        for (line in capped) {
            val pair = splitSpeaker(line)
            if (pair != null) {
                names.add(pair.first)
                bodies.add(StringBuilder(pair.second))
            } else if (bodies.isNotEmpty()) {
                // 没前缀的行当成上一条的续行——长消息被换行拆开时靠这个还原
                bodies[bodies.size - 1].append('\n').append(line)
            } else {
                names.add(fallbackSender)
                bodies.add(StringBuilder(line))
            }
        }
        val out = ArrayList<Parsed>(names.size)
        for (i in names.indices) {
            val body = bodies[i].toString().trim()
            if (body.isEmpty()) continue
            out.add(make(postedAt, names[i], body, pkg))
        }
        return out
    }

    /** 从 extras 里把所有可见的行摊平。TEXT_LINES 优先，因为它是最完整的。 */
    private fun collectLines(extras: android.os.Bundle?): List<String> {
        val out = ArrayList<String>()
        val arr = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (arr != null) {
            for (cs in arr) {
                val s = cs?.toString() ?: continue
                for (part in s.split('\n')) {
                    val t = part.trim()
                    if (t.isNotEmpty()) out.add(t)
                }
            }
        }
        if (out.isNotEmpty()) return out

        val one = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (one != null) {
            for (part in one.split('\n')) {
                val t = part.trim()
                if (t.isNotEmpty()) out.add(t)
            }
        }
        return out
    }

    private fun make(ts: Long, sender: String, body: String, pkg: String): Parsed {
        val s = sender.trim().ifEmpty { FALLBACK_SENDER }
        return Parsed(ts, s, body, fingerprint(pkg, s, body), isPlaceholder(body))
    }

    /**
     * 内容指纹。入库去重、以及「状态表为空时从库里回填余额」都必须用同一套算法，
     * 否则两边算出来的东西对不上，回填就失效了。
     */
    fun fingerprint(pkg: String, sender: String, body: String): String =
        sha1("$pkg|$sender|$body")

    /**
     * 从一行里拆出「名字: 内容」。拆不出来返回 null。
     *
     * 故意做得很保守：宁可漏拆（整条存着，至少内容没丢），也不要拆错
     * （把「注意: 明天九点」里的「注意」当成人名）。
     */
    private fun splitSpeaker(line: String): Pair<String, String>? {
        var idx = -1
        val scanTo = if (line.length > MAX_NAME_LEN + 1) MAX_NAME_LEN + 1 else line.length
        for (i in 0 until scanTo) {
            val c = line[i]
            if (c == ':' || c == '：') {
                idx = i
                break
            }
        }
        if (idx <= 0) return null

        val name = line.substring(0, idx).trim()
        val rest = line.substring(idx + 1).trim()
        if (name.isEmpty() || rest.isEmpty()) return null
        if (name.length > MAX_NAME_LEN) return null
        if (name in NOT_NAMES) return null

        // 「10:30」「2024-01-01 10:30」这类时间开头的行不是人名
        var digits = 0
        for (c in name) {
            if (c.isDigit()) digits++
        }
        if (digits > 0 && digits * 2 >= name.length) return null

        // 人名里不会有句子标点，有就说明这是正文里的冒号
        for (c in name) {
            when (c) {
                ',', '，', '。', '！', '？', '；', '、', '(', ')', '（', '）',
                '[', ']', '【', '】', '"', '\'', '“', '”', '‘', '’', '\n' ->
                    return null
            }
        }
        return name to rest
    }

    /** 去掉标题里的未读数。剥不掉就原样返回，宁可多带个括号也不要丢名字。 */
    private fun stripUnread(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        s = UNREAD_TAIL.replace(s, "").trim()
        s = UNREAD_WORD.replace(s, "").trim()
        return s
    }

    /**
     * 判断这句是不是「通知里没内容」的空壳。
     *
     * 微信关掉「通知显示消息详情」之后，通知正文只剩「你收到一条新消息」，
     * 这种东西进库只会污染汇总结果，所以单独计数、在界面上提醒用户去开。
     */
    private fun isPlaceholder(body: String): Boolean {
        val t = PH_PUNCT.replace(PH_SPACE.replace(body, ""), "")
        if (t.isEmpty() || t.length > 14) return false
        if (!t.contains("消息")) return false
        for (c in t) {
            if (c.isDigit()) continue
            if (c !in PH_CHARS) return false
        }
        return true
    }

    private fun sha1(s: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append("%02x".format(b))
        }
        return sb.toString()
    }
}
