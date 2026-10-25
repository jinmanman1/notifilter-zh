package com.wxmsglog

import android.app.Notification
import androidx.core.app.NotificationCompat
import java.security.MessageDigest

/** 从一条通知里解析出来的一条消息。 */
class Parsed(
    val ts: Long,
    val sender: String,
    val body: String,
    val hash: String,
)

/**
 * 通知内容的解析。
 *
 * 优先读 MessagingStyle —— 那是聊天类应用放多条消息的地方，能一次拿到
 * 「谁、什么时候、说了什么」，而且时间戳是精确的，去重最可靠。
 * 拿不到就退回标题 + 正文，这时时间只能用通知的投递时间，去重靠时间桶。
 */
object WxParse {

    /** 倒退方案里用于去重的时间窗口：同一个发信人 + 同样内容，落在同一窗口内视为重复。 */
    private const val FALLBACK_BUCKET_MS = 5 * 60 * 1000L

    private const val FALLBACK_SENDER = "微信"

    fun extract(n: Notification, postedAt: Long): List<Parsed> {
        val extras = n.extras
        val fallbackTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()

        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull()

        if (style != null && style.messages.isNotEmpty()) {
            val conversation = style.conversationTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val out = ArrayList<Parsed>(style.messages.size)
            for (m in style.messages) {
                val body = m.text?.toString()?.trim().orEmpty()
                if (body.isEmpty()) continue
                val person = m.person?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val sender = person ?: conversation ?: fallbackTitle.takeIf { it.isNotEmpty() } ?: FALLBACK_SENDER
                val ts = if (m.timestamp > 0L) m.timestamp else postedAt
                out.add(Parsed(ts, sender, body, sha1("$sender|$body|${ts / 1000}")))
            }
            if (out.isNotEmpty()) return out
        }

        // 退回：正文可能分散在 BIG_TEXT / TEXT / TEXT_LINES 里
        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val body = if (lines.isNotEmpty()) {
            lines.joinToString("\n")
        } else {
            (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                ?.trim()
                .orEmpty()
        }
        if (body.isEmpty()) return emptyList()

        val sender = fallbackTitle.takeIf { it.isNotEmpty() } ?: FALLBACK_SENDER
        val bucket = postedAt / FALLBACK_BUCKET_MS
        return listOf(Parsed(postedAt, sender, body, sha1("$sender|$body|$bucket")))
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
