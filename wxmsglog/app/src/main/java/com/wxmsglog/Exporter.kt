package com.wxmsglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 把消息变成可复制、可导出、可分享的纯文本。 */
object Exporter {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * 生成文本。超过 maxChars 就截断——剪贴板和 Intent 都有大小上限，
     * 完整内容走导出文件那条路。
     */
    fun compose(context: Context, list: List<Message>, maxChars: Int): String {
        if (list.isEmpty()) return ""
        val full = build(list)
        if (full.length <= maxChars) return full
        val cut = full.substring(0, maxChars.coerceIn(0, full.length))
        return cut + context.getString(R.string.truncated_note)
    }

    /**
     * 按日期分组。这个格式是刻意选的：直接丢给 AI，它能顺着日期理解
     * 「今天聊了什么」，比一整坨流水文本好读得多。
     */
    fun build(list: List<Message>): String {
        val sb = StringBuilder()
        sb.append("微信消息记录，共 ").append(list.size).append(" 条\n")
        var lastDay = ""
        for (m in list) {
            val dt = Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault())
            val day = dt.format(DATE_FMT)
            if (day != lastDay) {
                sb.append("\n## ").append(day).append("\n")
                lastDay = day
            }
            sb.append("[").append(dt.format(TIME_FMT)).append("] ")
            sb.append(m.sender).append(": ").append(m.body).append("\n")
        }
        return sb.toString().trim()
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clip_label), text))
    }

    /** 写入系统「下载」目录。API 29+ 走 MediaStore，不需要任何存储权限。 */
    fun saveToDownloads(context: Context, text: String): Uri? {
        val name = "微信消息-" + LocalDateTime.now().format(FILE_STAMP) + ".txt"
        val values = ContentValues()
        values.put(MediaStore.Downloads.DISPLAY_NAME, name)
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> out.write(text.toByteArray(Charsets.UTF_8)) }
        return uri
    }

    fun shareIntent(context: Context, text: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, context.getString(R.string.share_title))
    }
}
