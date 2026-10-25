package com.wxmsglog

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 完整备份与恢复。
 *
 * 为什么需要它：应用刻意关掉了 `allowBackup`（否则系统会把聊天库同步到厂商云，
 * 等于把「数据留在本机」这层承诺绕过去）。代价是换手机、卸载、清数据就全没了。
 *
 * 这个功能把「备份」的控制权交回用户手里：你想备份就自己导一份，不想就不导。
 * 备份文件是明文 JSON，里面有全部聊天记录，导出时会提醒。
 */
object Backup {

    private const val TAG = "WxBackup"

    /** 格式版本。以后加字段靠它判断要不要兼容。 */
    private const val VERSION = 1

    const val APP_TAG = "wxmsglog"

    data class Result(
        val messages: Int,
        val todos: Int,
        val reports: Int,
        val error: String?,
    ) {
        val total: Int get() = messages + todos + reports
    }

    /**
     * 打包成 JSON 字符串。阻塞调用——几万条消息可能有几十兆，
     * 一定要放到工作线程，别在点击事件里直接调。
     */
    fun export(context: Context): String {
        val db = MessageDb.get(context)

        val messages = JSONArray()
        for (m in db.backupMessages()) {
            messages.put(
                JSONObject().apply {
                    put("ts", m.ts)
                    put("sender", m.sender)
                    put("body", m.body)
                    put("pkg", m.pkg)
                    put("hash", m.hash)
                    put("tag", m.tag)
                }
            )
        }

        val todos = JSONArray()
        for (t in db.todos()) {
            todos.put(
                JSONObject().apply {
                    put("text", t.text)
                    put("due", t.due)
                    put("done", if (t.done) 1 else 0)
                    put("src", t.src)
                    put("created", t.created)
                }
            )
        }

        val reports = JSONArray()
        for (r in db.reportsForBackup()) {
            reports.put(
                JSONObject().apply {
                    put("kind", r.kind)
                    put("from", r.from)
                    put("to", r.to)
                    put("body", r.body)
                    put("created", r.created)
                }
            )
        }

        val root = JSONObject().apply {
            put("app", APP_TAG)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("messages", messages)
            put("todos", todos)
            put("reports", reports)
        }
        return root.toString()
    }

    /**
     * 从 JSON 恢复。阻塞调用。
     *
     * 消息按 hash 去重，所以同一份备份可以反复导入，不会变成双份。
     * 待办和报告没有天然唯一键，重复导入会重复——这是已知的取舍，
     * 因为待办正文可能一模一样（「交日报」每周都有）。
     */
    fun restore(context: Context, json: String): Result {
        val root = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return Result(0, 0, 0, "文件不是有效的备份")
        }

        if (root.optString("app") != APP_TAG) {
            return Result(0, 0, 0, "这不是本应用导出的备份")
        }

        val db = MessageDb.get(context)

        val msgs = ArrayList<BackupMsg>()
        val arr = root.optJSONArray("messages")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val body = o.optString("body")
                if (body.isEmpty()) continue
                msgs.add(
                    BackupMsg(
                        ts = o.optLong("ts"),
                        sender = o.optString("sender"),
                        body = body,
                        pkg = o.optString("pkg"),
                        hash = o.optString("hash").ifEmpty {
                            // 老版本备份可能没带 hash，这里按入库那套规则补一个，
                            // 否则唯一索引会把空串当成同一行，所有老消息挤成一条
                            WxParse.fingerprint(
                                o.optString("pkg"),
                                o.optString("sender"),
                                body,
                            )
                        },
                        tag = o.optString("tag"),
                    )
                )
            }
        }

        val todos = ArrayList<Todo>()
        val ta = root.optJSONArray("todos")
        if (ta != null) {
            for (i in 0 until ta.length()) {
                val o = ta.optJSONObject(i) ?: continue
                val text = o.optString("text")
                if (text.isEmpty()) continue
                todos.add(
                    Todo(
                        id = 0L,
                        text = text,
                        due = o.optLong("due"),
                        done = o.optInt("done") != 0,
                        src = o.optLong("src"),
                        created = o.optLong("created").takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                )
            }
        }

        val reports = ArrayList<Report>()
        val ra = root.optJSONArray("reports")
        if (ra != null) {
            for (i in 0 until ra.length()) {
                val o = ra.optJSONObject(i) ?: continue
                val body = o.optString("body")
                if (body.isEmpty()) continue
                reports.add(
                    Report(
                        id = 0L,
                        kind = o.optString("kind", "day"),
                        from = o.optLong("from"),
                        to = o.optLong("to"),
                        body = body,
                        created = o.optLong("created"),
                    )
                )
            }
        }

        return try {
            val m = db.insertBackupMessages(msgs)
            val t = db.insertBackupTodos(todos)
            val r = db.insertBackupReports(reports)
            // 恢复完把闹钟重排一遍：备份里那些没完成的待办，原闹钟早就没了
            runCatching { Alarm.rescheduleAll(context, db) }
            Result(m, t, r, null)
        } catch (th: Throwable) {
            Log.e(TAG, "恢复失败", th)
            Result(0, 0, 0, "写入失败：" + (th.message ?: "未知错误"))
        }
    }

    // ---------- 文件读写 ----------

    /** 建议的文件名，带日期。 */
    fun fileName(): String {
        val d = java.time.LocalDate.now()
        return "wxmsglog-备份-%04d%02d%02d.json".format(d.year, d.monthValue, d.dayOfMonth)
    }

    /** 从选中的文件读文本。 */
    fun read(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            ins.readBytes().toString(Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "读备份文件失败", t)
        null
    }
}
