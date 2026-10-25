package com.wxmsglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 把消息变成可复制、可导出、可分享的文本。 */
object Exporter {

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /** 剪贴板单次传输的字节上限，压在 Binder 的 1MB 之下留足余量。 */
    private const val CLIP_MAX_BYTES = 700_000

    /** 导出格式。Markdown 给人/AI 看，CSV 给表格，JSON 给程序。 */
    enum class Format(val key: String, val ext: String, val mime: String) {
        MD("md", "md", "text/markdown"),
        TXT("txt", "txt", "text/plain"),
        CSV("csv", "csv", "text/csv"),
        JSON("json", "json", "application/json");

        companion object {
            fun of(key: String): Format = entries.firstOrNull { it.key == key } ?: MD
        }
    }

    /** 分段复制中的一段。 */
    class Segment(
        val index: Int,
        val total: Int,
        val title: String,
        val text: String,
        val count: Int,
    )

    fun formatOf(context: Context): Format = Format.of(Prefs.exportFormat(context))

    /**
     * 生成文本。超过 maxChars 就截断——剪贴板和 Intent 都有大小上限。
     *
     * @param rangeLabel 时间范围描述，会写成正文第二行，方便归档后回看这是哪一段
     */
    fun compose(
        context: Context,
        list: List<Message>,
        maxChars: Int,
        rangeLabel: String? = null,
        format: Format = formatOf(context),
    ): String {
        if (list.isEmpty()) return ""
        val full = build(context, list, rangeLabel, format)
        if (full.length <= maxChars) return full
        val cut = full.substring(0, maxChars.coerceIn(0, full.length))
        return cut + context.getString(R.string.truncated_note)
    }

    fun build(
        context: Context,
        list: List<Message>,
        rangeLabel: String? = null,
        format: Format = Format.MD,
    ): String {
        if (list.isEmpty()) return ""
        return when (format) {
            Format.CSV -> buildCsv(context, list)
            Format.JSON -> buildJson(context, list)
            Format.MD -> buildReadable(context, list, rangeLabel, markdown = true)
            Format.TXT -> buildReadable(context, list, rangeLabel, markdown = false)
        }
    }

    /**
     * 先按应用分组，组内再按日期分组。
     *
     * 这个结构调整过：原来的版本只按日期排，多个应用混在一天里，
     * 粘给 AI 之后它分不清哪句是微信、哪句是短信。按应用切开之后，
     * 「微信聊了什么」「短信收到了什么」一眼能看出来。
     *
     * 传入的 list 顺序即输出顺序，调用方负责排序（文本用时间正序）。
     */
    private fun buildReadable(
        context: Context,
        list: List<Message>,
        rangeLabel: String?,
        markdown: Boolean,
    ): String {
        val groups = LinkedHashMap<String, MutableList<Message>>()
        for (m in list) {
            val key = m.pkg.ifEmpty { "unknown" }
            groups.getOrPut(key) { ArrayList() }.add(m)
        }

        val sb = StringBuilder()
        sb.append(
            if (groups.size == 1) context.getString(R.string.doc_header, list.size)
            else context.getString(R.string.doc_header_multi, list.size, groups.size)
        )
        if (!rangeLabel.isNullOrEmpty()) {
            sb.append("\n").append(context.getString(R.string.doc_range, rangeLabel))
        }

        for ((pkg, msgs) in groups) {
            val label = AppInfo.label(context, pkg)
            sb.append("\n\n")
            if (markdown) {
                sb.append("# ").append(label).append("（").append(msgs.size).append(" 条）\n")
            } else {
                sb.append("===== ").append(label).append("（").append(msgs.size).append(" 条）=====\n")
            }
            var lastDay = ""
            for (m in msgs) {
                val dt = Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault())
                val day = TimeFmt.day(m.ts)
                if (day != lastDay) {
                    if (lastDay.isNotEmpty()) sb.append("\n")
                    sb.append(if (markdown) "## $day\n" else "$day\n")
                    lastDay = day
                }
                sb.append("[").append(dt.format(TIME_FMT)).append("] ")
                    .append(m.sender).append(": ").append(m.body).append("\n")
            }
        }
        return sb.toString().trim()
    }

    private fun buildCsv(context: Context, list: List<Message>): String {
        val sb = StringBuilder()
        // BOM 让 Excel 直接按 UTF-8 打开，不然中文全是乱码
        sb.append('\uFEFF')
        sb.append("时间,应用,发信人,内容\r\n")
        val zone = ZoneId.systemDefault()
        for (m in list) {
            val dt = Instant.ofEpochMilli(m.ts).atZone(zone)
            sb.append(csv(dt.format(ISO))).append(',')
                .append(csv(AppInfo.label(context, m.pkg))).append(',')
                .append(csv(m.sender)).append(',')
                .append(csv(m.body)).append("\r\n")
        }
        return sb.toString()
    }

    private fun csv(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""

    private fun buildJson(context: Context, list: List<Message>): String {
        val arr = JSONArray()
        val zone = ZoneId.systemDefault()
        for (m in list) {
            val o = JSONObject()
            o.put("time", Instant.ofEpochMilli(m.ts).atZone(zone).format(ISO))
            o.put("ts", m.ts)
            o.put("app", AppInfo.label(context, m.pkg))
            o.put("package", m.pkg)
            o.put("sender", m.sender)
            o.put("body", m.body)
            arr.put(o)
        }
        return arr.toString(2)
    }

    /**
     * 把消息切成若干段，每段不超过 maxChars。
     *
     * 用途：几万字一次粘给 AI 会超上下文，而且模型对这种长输入反而抓不住重点。
     * 按「应用 + 日期」切成 1 万字左右，一次喂一段效果明显更好。
     */
    fun segments(
        context: Context,
        list: List<Message>,
        maxChars: Int = 10_000,
    ): List<Segment> {
        if (list.isEmpty()) return emptyList()

        // 先摊平成「应用 · 日期」标题行 + 单行消息，切段时不会把标题和正文拆散。
        // 以 \u0000 开头的是标题行，正文里不会出现这个字符。
        val blocks = ArrayList<String>(list.size + 16)
        var lastKey = ""
        for (m in list) {
            val day = TimeFmt.day(m.ts)
            val key = m.pkg + "|" + day
            if (key != lastKey) {
                blocks.add("\u0000" + AppInfo.label(context, m.pkg) + " · " + day)
                lastKey = key
            }
            val hm = Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).format(TIME_FMT)
            blocks.add("[$hm] ${m.sender}: ${m.body}")
        }

        val drafts = ArrayList<Draft>()
        var cur: Draft? = null
        var curTitle = ""
        for (line in blocks) {
            if (line.startsWith("\u0000")) {
                curTitle = line.substring(1)
                continue
            }
            val d = cur
            if (d != null && d.body.isNotEmpty() && d.body.length + line.length + 24 > maxChars) {
                drafts.add(d)
                cur = Draft(curTitle)
            } else if (d == null) {
                cur = Draft(curTitle)
            }
            val target = cur!!
            target.body.append(line).append("\n")
            target.count++
        }
        cur?.let { if (it.body.isNotEmpty()) drafts.add(it) }

        val total = drafts.size
        return drafts.mapIndexed { i, d ->
            val range = d.title.ifEmpty { "消息" }
            val head = if (total > 1) {
                context.getString(R.string.seg_head, i + 1, total, range)
            } else {
                range
            }
            Segment(i + 1, total, range, head + "\n" + d.body.toString().trimEnd(), d.count)
        }
    }

    /** 切段时的中间产物：一段还没写完的文本。 */
    private class Draft(val title: String) {
        val body = StringBuilder()
        var count = 0
    }

    /**
     * 复制到剪贴板。
     *
     * 这里必须按「字节」而不是「字符」设上限：剪贴板内容要经过 Binder 传给系统，
     * 单次传输硬顶 1MB，超了直接抛 TransactionTooLargeException 把应用崩掉。
     * 一个汉字 UTF-8 占 3 字节，40 万字符就是 1.2MB —— 正好越界。
     *
     * @return true 表示内容过长被截断了，调用方可以给个不同的提示
     */
    fun copy(context: Context, text: String): Boolean {
        val truncated = text.toByteArray(Charsets.UTF_8).size > CLIP_MAX_BYTES
        val safe = if (!truncated) {
            text
        } else {
            // 二分找「不超过上限的最大字符数」，这样不会把某个汉字切成半个
            var lo = 0
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (text.substring(0, mid).toByteArray(Charsets.UTF_8).size <= CLIP_MAX_BYTES) {
                    lo = mid
                } else {
                    hi = mid - 1
                }
            }
            text.substring(0, lo) + context.getString(R.string.truncated_note)
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clip_label), safe))
        return truncated
    }

    /** 写入系统「下载」目录。API 29+ 走 MediaStore，不需要任何存储权限。 */
    fun saveToDownloads(
        context: Context,
        text: String,
        format: Format = formatOf(context),
        prefix: String? = null,
    ): Uri? {
        val name = (prefix ?: context.getString(R.string.export_file_prefix)) +
            LocalDateTime.now().format(FILE_STAMP) + "." + format.ext
        val values = ContentValues()
        values.put(MediaStore.Downloads.DISPLAY_NAME, name)
        values.put(MediaStore.Downloads.MIME_TYPE, format.mime)
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> out.write(text.toByteArray(Charsets.UTF_8)) }
        return uri
    }

    /** 分享纯文本（微信里当消息发）。 */
    fun shareIntent(context: Context, text: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, context.getString(R.string.share_title))
    }

    /** 分享成文件（微信里能直接发文件，不会被截断）。 */
    fun shareFile(
        context: Context,
        text: String,
        format: Format = formatOf(context),
        subject: String? = null,
    ): Intent {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        val name = "消息记录-" + LocalDateTime.now().format(FILE_STAMP) + "." + format.ext
        val file = File(dir, name)
        file.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
        send.type = format.mime
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.putExtra(Intent.EXTRA_SUBJECT, subject ?: name)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, context.getString(R.string.share_file_title))
    }
}
