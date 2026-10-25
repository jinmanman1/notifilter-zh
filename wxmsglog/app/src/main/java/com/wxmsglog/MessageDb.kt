package com.wxmsglog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 一次增量入库的结果。placeholders 是「只有空壳、没内容」的通知条数。 */
class BatchResult(val added: Int, val placeholders: Int)

/**
 * 备份用的一行消息。
 *
 * 比 [Message] 多带 hash 和 tag：hash 是唯一索引，恢复时要靠它去重；
 * tag 是分类结果，不带上就等于备份完之后分类全丢了。
 */
data class BackupMsg(
    val ts: Long,
    val sender: String,
    val body: String,
    val pkg: String,
    val hash: String,
    val tag: String,
)

/**
 * 一个应用的监听情况。
 *
 * @param lastTs  最后一条消息的时间，0 表示从来没收到过
 * @param total   总条数
 * @param recent  最近这段时间（调用方给）的条数
 */
data class PkgStat(
    val pkg: String,
    val lastTs: Long,
    val total: Int,
    val recent: Int,
)

/**
 * 本地存储。用最原始的 SQLiteOpenHelper，不引 Room——
 * 少一层注解处理，CI 上就少一类可能失败的地方。
 *
 * v1 → v2：加 pkg 列，支持记录微信以外的应用。
 * v2 → v3：pkg 索引。
 * v4：不需要改表结构，只加了按发信人查询、按时间清理这几个入口。
 * v5：加 notestate 表，按「通知 key」记住上次已入库的内容指纹，
 *     用来把累积型通知里的增量挑出来，不再整段重存。
 * v6：加 todo 表，待办事项跟消息放同一个库，备份和清理都是一套。
 * v7：加 report 表（日报/周报），以及 ai_scan 表记录 AI 扫到哪了。
 * v8：消息加 tag 列（分类打标），加 vec 表（语义搜索的向量）。
 */
class MessageDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "sender TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "pkg TEXT NOT NULL DEFAULT ''," +
                // 分类打标的结果："" / code / delivery / bill / ad / work / life
                "tag TEXT NOT NULL DEFAULT ''," +
                "hash TEXT NOT NULL UNIQUE)"
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_ts ON $TABLE(ts)")
        db.execSQL("CREATE INDEX idx_${TABLE}_pkg ON $TABLE(pkg)")
        db.execSQL("CREATE INDEX idx_${TABLE}_sender ON $TABLE(sender)")
        db.execSQL("CREATE INDEX idx_${TABLE}_tag ON $TABLE(tag)")
        db.execSQL(createStateSql())
        db.execSQL(createTodoSql())
        db.execSQL(createReportSql())
        db.execSQL(createVecSql())
    }

    /** 语义搜索的向量。单独一张表，主表就不用扛几百兆的 blob。 */
    private fun createVecSql() =
        "CREATE TABLE IF NOT EXISTS $VEC_TABLE (" +
            "msg_id INTEGER PRIMARY KEY," +
            "v BLOB NOT NULL)"

    private fun createReportSql() =
        "CREATE TABLE IF NOT EXISTS $REPORT_TABLE (" +
            "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            // "day" 或 "week"
            "kind TEXT NOT NULL," +
            "from_ts INTEGER NOT NULL," +
            "to_ts INTEGER NOT NULL," +
            "body TEXT NOT NULL," +
            "created INTEGER NOT NULL," +
            "UNIQUE(kind, from_ts))"

    private fun createStateSql() =
        "CREATE TABLE IF NOT EXISTS $STATE_TABLE (" +
            "nkey TEXT PRIMARY KEY," +
            "fps TEXT NOT NULL," +
            "updated INTEGER NOT NULL)"

    private fun createTodoSql() =
        "CREATE TABLE IF NOT EXISTS $TODO_TABLE (" +
            "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "text TEXT NOT NULL," +
            "due INTEGER NOT NULL DEFAULT 0," +
            "done INTEGER NOT NULL DEFAULT 0," +
            "src INTEGER NOT NULL DEFAULT 0," +
            "created INTEGER NOT NULL)"

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 老数据全部来自微信，补上包名即可
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN pkg TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE $TABLE SET pkg = ? WHERE pkg = ''", arrayOf(Prefs.WECHAT))
        }
        if (oldVersion < 3) {
            // 按应用筛选是常用操作，补一个索引
            runCatching { db.execSQL("CREATE INDEX idx_${TABLE}_pkg ON $TABLE(pkg)") }
        }
        if (oldVersion < 4) {
            // 「按人」视图要按发信人查
            runCatching { db.execSQL("CREATE INDEX idx_${TABLE}_sender ON $TABLE(sender)") }
        }
        if (oldVersion < 5) {
            // 老数据没有通知状态，第一次收到通知时会当作全新的重存一遍，
            // 之后 hash 的 UNIQUE 约束会把完全相同的那几条挡掉。
            runCatching { db.execSQL(createStateSql()) }
        }
        if (oldVersion < 6) {
            runCatching { db.execSQL(createTodoSql()) }
        }
        if (oldVersion < 7) {
            runCatching { db.execSQL(createReportSql()) }
        }
        if (oldVersion < 8) {
            // 老消息的 tag 一律为空，等用户点「分类」再补
            runCatching { db.execSQL("ALTER TABLE $TABLE ADD COLUMN tag TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("CREATE INDEX idx_${TABLE}_tag ON $TABLE(tag)") }
            runCatching { db.execSQL(createVecSql()) }
        }
    }

    // ---------- 待办 ----------

    /** 新建一条待办，返回它的 id。due 传 0 表示不设截止时间。 */
    fun addTodo(text: String, due: Long, src: Long): Long {
        val cv = ContentValues(5)
        cv.put("text", text)
        cv.put("due", due)
        cv.put("done", 0)
        cv.put("src", src)
        cv.put("created", System.currentTimeMillis())
        return writableDatabase.insert(TODO_TABLE, null, cv)
    }

    fun updateTodo(id: Long, text: String, due: Long) {
        val cv = ContentValues(3)
        cv.put("text", text)
        cv.put("due", due)
        writableDatabase.update(TODO_TABLE, cv, "_id = ?", arrayOf(id.toString()))
    }

    fun setTodoDone(id: Long, done: Boolean) {
        val cv = ContentValues(1)
        cv.put("done", if (done) 1 else 0)
        writableDatabase.update(TODO_TABLE, cv, "_id = ?", arrayOf(id.toString()))
    }

    fun deleteTodo(id: Long) {
        writableDatabase.delete(TODO_TABLE, "_id = ?", arrayOf(id.toString()))
    }

    /**
     * 全部待办。排序规则：
     * 未完成在前 → 有截止时间的在前（按时间升序）→ 没有截止时间的垫底（新的在上）。
     * 已完成的一律沉到最下面，按创建时间倒序，方便回看。
     */
    fun todos(): List<Todo> {
        val sql = "SELECT * FROM $TODO_TABLE ORDER BY done ASC," +
            " CASE WHEN due = 0 THEN 1 ELSE 0 END ASC," +
            " due ASC, created DESC"
        val out = ArrayList<Todo>()
        readableDatabase.rawQuery(sql, null).use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iText = c.getColumnIndexOrThrow("text")
            val iDue = c.getColumnIndexOrThrow("due")
            val iDone = c.getColumnIndexOrThrow("done")
            val iSrc = c.getColumnIndexOrThrow("src")
            val iCreated = c.getColumnIndexOrThrow("created")
            while (c.moveToNext()) {
                out.add(
                    Todo(
                        c.getLong(iId),
                        c.getString(iText) ?: "",
                        c.getLong(iDue),
                        c.getInt(iDone) != 0,
                        c.getLong(iSrc),
                        c.getLong(iCreated),
                    )
                )
            }
        }
        return out
    }

    /**
     * 指定时间范围内的待办，历史页用。
     *
     * 按「创建时间」落在区间里算，不是按截止时间——用户想找「上周三记了什么」时，
     * 脑子里是那天写下的东西，不是截止日期正好在那天。
     */
    fun todosBetween(from: Long, to: Long): List<Todo> {
        val sql = "SELECT * FROM $TODO_TABLE WHERE created >= ? AND created < ?" +
            " ORDER BY done ASC, created DESC"
        val out = ArrayList<Todo>()
        readableDatabase.rawQuery(sql, arrayOf(from.toString(), to.toString())).use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iText = c.getColumnIndexOrThrow("text")
            val iDue = c.getColumnIndexOrThrow("due")
            val iDone = c.getColumnIndexOrThrow("done")
            val iSrc = c.getColumnIndexOrThrow("src")
            val iCreated = c.getColumnIndexOrThrow("created")
            while (c.moveToNext()) {
                out.add(
                    Todo(
                        c.getLong(iId),
                        c.getString(iText) ?: "",
                        c.getLong(iDue),
                        c.getInt(iDone) != 0,
                        c.getLong(iSrc),
                        c.getLong(iCreated),
                    )
                )
            }
        }
        return out
    }

    /**
     * 已经关联了待办的消息 id。
     * AI 聚合时拿它判重：这条消息上次已经生成过待办了，别再弹一次。
     */
    fun todoSourceIds(): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT src FROM $TODO_TABLE WHERE src > 0", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    /** 还没完成、且设了时间的待办。用来在开机或重装闹钟后重新排一遍。 */
    fun pendingTodos(): List<Todo> = todos().filter { !it.done && it.due > 0L }

    /** 未完成的条数，给入口上的角标用。 */
    fun todoPendingCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TODO_TABLE WHERE done = 0", null
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ---------- 日报 / 周报 ----------

    /** 覆盖写。同一天重跑就更新内容，不会堆出一堆重复的。 */
    fun saveReport(kind: String, from: Long, to: Long, body: String) {
        val cv = ContentValues().apply {
            put("kind", kind)
            put("from_ts", from)
            put("to_ts", to)
            put("body", body)
            put("created", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            REPORT_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** 新的在前。 */
    fun reports(limit: Int = 60): List<Report> {
        val out = ArrayList<Report>()
        readableDatabase.rawQuery(
            "SELECT * FROM $REPORT_TABLE ORDER BY from_ts DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Report(
                        c.getLong(c.getColumnIndexOrThrow("_id")),
                        c.getString(c.getColumnIndexOrThrow("kind")) ?: "day",
                        c.getLong(c.getColumnIndexOrThrow("from_ts")),
                        c.getLong(c.getColumnIndexOrThrow("to_ts")),
                        c.getString(c.getColumnIndexOrThrow("body")) ?: "",
                        c.getLong(c.getColumnIndexOrThrow("created")),
                    )
                )
            }
        }
        return out
    }

    fun deleteReport(id: Long) {
        writableDatabase.delete(REPORT_TABLE, "_id = ?", arrayOf(id.toString()))
    }

    // ---------- 语义搜索的向量 ----------

    /**
     * 还没算过向量的消息，新的优先。
     *
     * 用 LEFT JOIN 判空而不是 NOT IN：几万条消息时 NOT IN 要全表扫，
     * LEFT JOIN 配上 msg_id 主键索引快得多。
     */
    fun unembeddedMessages(limit: Int): List<Message> {
        val sql = "SELECT m.* FROM $TABLE m LEFT JOIN $VEC_TABLE v ON m._id = v.msg_id" +
            " WHERE v.msg_id IS NULL AND length(m.body) > 0" +
            " ORDER BY m.ts DESC LIMIT ?"
        return readMessages(sql, arrayOf(limit.toString()))
    }

    fun saveVec(msgId: Long, v: FloatArray) {
        val cv = ContentValues().apply {
            put("msg_id", msgId)
            put("v", floatsToBlob(v))
        }
        writableDatabase.insertWithOnConflict(
            VEC_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun vecCount(): Int = countOf("SELECT COUNT(*) FROM $VEC_TABLE")

    /** 全部向量。一次读进内存算相似度，比每条查一次快一个数量级。 */
    fun allVecs(): List<Pair<Long, FloatArray>> {
        val out = ArrayList<Pair<Long, FloatArray>>()
        readableDatabase.rawQuery("SELECT msg_id, v FROM $VEC_TABLE", null).use { c ->
            while (c.moveToNext()) {
                out.add(c.getLong(0) to blobToFloats(c.getBlob(1)))
            }
        }
        return out
    }

    fun clearVecs() {
        writableDatabase.delete(VEC_TABLE, null, null)
    }

    // ---------- 分类标签 ----------

    fun setTag(msgId: Long, tag: String) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply { put("tag", tag) },
            "_id = ?",
            arrayOf(msgId.toString()),
        )
    }

    /** 批量写标签，一次事务，几千条也不会卡。 */
    fun setTags(items: List<Pair<Long, String>>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, tag) in items) {
                db.update(
                    TABLE,
                    ContentValues().apply { put("tag", tag) },
                    "_id = ?",
                    arrayOf(id.toString()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun messagesByTag(tag: String, limit: Int = 500): List<Message> {
        val sql = "SELECT * FROM $TABLE WHERE tag = ? ORDER BY ts DESC LIMIT ?"
        return readMessages(sql, arrayOf(tag, limit.toString()))
    }

    /** 标签 -> 条数。没分类的用空键 ""。 */
    fun tagCounts(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT tag, COUNT(*) FROM $TABLE GROUP BY tag ORDER BY COUNT(*) DESC", null
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0) ?: ""] = c.getInt(1)
        }
        return out
    }

    fun untaggedCount(): Int = countOf("SELECT COUNT(*) FROM $TABLE WHERE tag = ''")

    /**
     * 待分类的消息。
     *
     * 只取 6 个字以上的：太短的消息（「好的」「收到」）分不出什么，硬要分
     * 反而会把它们塞进「其他」，而且它们分类后 tag 仍然是空，下次还会被
     * 捞出来——变成一个永远跑不完的循环。这里直接排除。
     */
    fun untaggedMessages(limit: Int): List<Message> {
        val sql = "SELECT * FROM $TABLE WHERE tag = '' AND length(body) >= 6" +
            " ORDER BY ts DESC LIMIT ?"
        return readMessages(sql, arrayOf(limit.toString()))
    }

    // ---------- 内部小工具 ----------

    private fun countOf(sql: String): Int {
        readableDatabase.rawQuery(sql, null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun readMessages(sql: String, args: Array<String>): List<Message> {
        val out = ArrayList<Message>()
        readableDatabase.rawQuery(sql, args).use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iTs = c.getColumnIndexOrThrow("ts")
            val iSender = c.getColumnIndexOrThrow("sender")
            val iBody = c.getColumnIndexOrThrow("body")
            val iPkg = c.getColumnIndexOrThrow("pkg")
            while (c.moveToNext()) {
                out.add(
                    Message(
                        c.getLong(iId),
                        c.getLong(iTs),
                        c.getString(iSender) ?: "",
                        c.getString(iBody) ?: "",
                        c.getString(iPkg) ?: "",
                    )
                )
            }
        }
        return out
    }

    private fun floatsToBlob(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) bb.putFloat(f)
        return bb.array()
    }

    private fun blobToFloats(b: ByteArray?): FloatArray {
        if (b == null || b.size < 4) return FloatArray(0)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(b.size / 4)
        for (i in out.indices) out[i] = bb.float
        return out
    }

    // ---------- 备份 / 恢复 ----------

    /**
     * 导出用：全部消息，带上 hash 和 tag。
     * hash 是唯一索引，恢复时靠它判重；tag 是分类结果，不带上等于备份完分类全丢。
     */
    fun backupMessages(): List<BackupMsg> {
        val out = ArrayList<BackupMsg>()
        readableDatabase.rawQuery(
            "SELECT ts, sender, body, pkg, hash, tag FROM $TABLE ORDER BY ts ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    BackupMsg(
                        c.getLong(0),
                        c.getString(1) ?: "",
                        c.getString(2) ?: "",
                        c.getString(3) ?: "",
                        c.getString(4) ?: "",
                        c.getString(5) ?: "",
                    )
                )
            }
        }
        return out
    }

    /**
     * 存一条从系统分享进来的文字。
     *
     * 用专门的包名标记来源，界面上显示成「手动分享」，跟通知抓来的分开。
     * hash 还是走那套规则，所以同一段文字分享两次只会存一条。
     *
     * @return 新插入的 id；已经存在时返回 -1。
     */
    fun insertShared(text: String, now: Long = System.currentTimeMillis()): Long {
        val body = text.trim()
        if (body.isEmpty()) return -1
        val cv = ContentValues().apply {
            put("ts", now)
            put("sender", "")
            put("body", body)
            put("pkg", SHARE_PKG)
            put("hash", WxParse.fingerprint(SHARE_PKG, "", body))
            put("tag", TagRules.classify(body))
        }
        return writableDatabase.insertWithOnConflict(
            TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /** 恢复消息：hash 已存在的自动跳过，可以重复导入同一份备份。 */    fun insertBackupMessages(items: List<BackupMsg>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (m in items) {
                val cv = ContentValues().apply {
                    put("ts", m.ts)
                    put("sender", m.sender)
                    put("body", m.body)
                    put("pkg", m.pkg)
                    put("hash", m.hash)
                    put("tag", m.tag)
                }
                if (db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE) > 0) {
                    added++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    /** 恢复待办：保留完成状态和创建时间，恢复之后历史页也能看到。 */
    fun insertBackupTodos(items: List<Todo>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (t in items) {
                val cv = ContentValues().apply {
                    put("text", t.text)
                    put("due", t.due)
                    put("done", if (t.done) 1 else 0)
                    put("src", t.src)
                    put("created", t.created)
                }
                if (db.insert(TODO_TABLE, null, cv) > 0) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    /** 恢复报告：同一天同一类型的用 REPLACE，不会堆重复。 */
    fun insertBackupReports(items: List<Report>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (r in items) {
                val cv = ContentValues().apply {
                    put("kind", r.kind)
                    put("from_ts", r.from)
                    put("to_ts", r.to)
                    put("body", r.body)
                    put("created", r.created)
                }
                if (db.insertWithOnConflict(
                        REPORT_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE
                    ) > 0
                ) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    /** 导出报告用，条数上限给够。 */
    fun reportsForBackup(): List<Report> = reports(100_000)

    /**
     * 每个应用的监听情况，按「最后一条消息」倒序。
     *
     * 「静默失效」是这类应用最坑的失败模式：授权还在、界面还显示正常，
     * 但其实一条也没收到。所以这里把「最后一条是什么时候」摆出来，
     * 用户一眼就能看出哪个应用断了。
     */
    fun pkgStats(sinceTs: Long): List<PkgStat> {
        val out = ArrayList<PkgStat>()
        val sql = "SELECT pkg, MAX(ts), COUNT(*)," +
            " SUM(CASE WHEN ts >= ? THEN 1 ELSE 0 END)" +
            " FROM $TABLE GROUP BY pkg ORDER BY MAX(ts) DESC"
        readableDatabase.rawQuery(sql, arrayOf(sinceTs.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PkgStat(
                        pkg = c.getString(0) ?: "",
                        lastTs = c.getLong(1),
                        total = c.getInt(2),
                        recent = c.getInt(3),
                    )
                )
            }
        }
        return out
    }

    /** 整库最后一次收到消息是什么时候。0 表示库是空的。 */
    fun lastMessageTs(): Long {
        readableDatabase.rawQuery("SELECT MAX(ts) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    /**
     * 某条消息的前后文。
     *
     * 排序用 (ts, _id) 两段：同一秒可能有多条消息，只按 ts 排的话
     * 前后关系会乱，取出来的「上下文」顺序就是错的。
     */
    fun messagesAround(m: Message, before: Int = 20, after: Int = 20): List<Message> {
        val out = ArrayList<Message>()
        val args = arrayOf(m.ts.toString(), m.ts.toString(), m.id.toString())

        // 比它早的，倒着取 N 条，再翻回来
        val earlier = readMessages(
            "SELECT * FROM $TABLE WHERE ts < ? OR (ts = ? AND _id < ?)" +
                " ORDER BY ts DESC, _id DESC LIMIT ?",
            args + arrayOf(before.toString()),
        )
        out.addAll(earlier.asReversed())

        out.add(m)

        // 比它晚的，正着取 N 条
        val later = readMessages(
            "SELECT * FROM $TABLE WHERE ts > ? OR (ts = ? AND _id > ?)" +
                " ORDER BY ts ASC, _id ASC LIMIT ?",
            args + arrayOf(after.toString()),
        )
        out.addAll(later)
        return out
    }

    /**
     * 按「通知 key」做增量入库。
     *
     * 聊天应用的通知是累积快照：来一条新消息，同一 key 的整段内容都会重发，
     * 老消息也会跟着再来一遍。所以先按指纹做多重集相减——本次出现的指纹
     * 先消耗上次记下的余额，消耗得掉就是旧的，消耗不掉才是新的。
     *
     * 用多重集而不是集合，是为了让「同一个人连说两句『好的』」也能存成两条：
     * 上次余额是 1，本次来 2 个，扣掉 1 个还剩 1 个。
     *
     * @return 新增条数 + 空壳通知条数（空壳只计数、不入库）
     */
    fun insertBatch(nkey: String, items: List<Parsed>, pkg: String): BatchResult {
        if (items.isEmpty()) return BatchResult(0, 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val prev = readState(db, nkey)
            val pool = HashMap<String, Int>(prev.size * 2 + 4)
            for (fp in prev) pool[fp] = (pool[fp] ?: 0) + 1
            // 状态表是空的 —— 要么刚升级上来，要么这个会话第一次见。
            // 这时通知里往往还显示着几条早就存过的老消息，不先补个底
            // 就会把它们当新消息重存一遍。拿库里最近的记录当初始余额。
            if (prev.isEmpty()) seedPool(db, pkg, pool)

            var placeholders = 0
            val fresh = ArrayList<Parsed>(items.size)
            for (item in items) {
                val left = pool[item.fp] ?: 0
                if (left > 0) {
                    pool[item.fp] = left - 1
                    continue
                }
                if (item.placeholder) {
                    placeholders++
                    continue
                }
                fresh.add(item)
            }

            // 状态写回：用本次的全集覆盖。窗口滑出去的老消息余额自然清零，
            // 这样即使某段内容再被挤回来，也不会因为余额还在而被误当成旧的。
            val fps = items.map { it.fp }
            val kept = if (fps.size > STATE_LIMIT) fps.subList(fps.size - STATE_LIMIT, fps.size) else fps
            val cv = ContentValues(3)
            cv.put("nkey", nkey)
            cv.put("fps", kept.joinToString("\n"))
            cv.put("updated", System.currentTimeMillis())
            db.insertWithOnConflict(STATE_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)

            var added = 0
            for (i in fresh.indices) {
                val m = fresh[i]
                // hash 只用于唯一约束：同一批里正文完全相同的两条靠序号区分，
                // 既不会互相顶掉，也不会因为换了批次就变成新记录（外层已去重）
                val hash = "${m.fp}:${m.ts}:$i"
                if (insertRow(db, m.ts, m.sender, m.body, pkg, hash)) added++
            }

            db.setTransactionSuccessful()
            return BatchResult(added, placeholders)
        } finally {
            db.endTransaction()
        }
    }

    private fun readState(db: SQLiteDatabase, nkey: String): List<String> {
        db.rawQuery("SELECT fps FROM $STATE_TABLE WHERE nkey = ?", arrayOf(nkey)).use { c ->
            if (!c.moveToFirst()) return emptyList()
            val s = c.getString(0) ?: return emptyList()
            if (s.isEmpty()) return emptyList()
            return s.split('\n')
        }
    }

    /**
     * 用库里最近的一批记录给余额打个底。
     *
     * 只能按应用取，表里没有会话字段——所以别的会话里恰好同名同内容的
     * 消息也会占一份余额。这种巧合极少，而且失手的后果只是少存一条，
     * 比升级后一下子冒出几十条重复记录好得多。
     */
    private fun seedPool(db: SQLiteDatabase, pkg: String, pool: HashMap<String, Int>) {
        db.rawQuery(
            "SELECT sender, body FROM $TABLE WHERE pkg = ? ORDER BY ts DESC LIMIT $SEED_LIMIT",
            arrayOf(pkg),
        ).use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                val fp = WxParse.fingerprint(pkg, sender, body)
                pool[fp] = (pool[fp] ?: 0) + 1
            }
        }
    }

    /** 太久没更新的通知状态没有价值，后台顺手清掉，别让这张表一直长。 */
    fun deleteStaleStates(cutoff: Long): Int =
        writableDatabase.delete(STATE_TABLE, "updated < ?", arrayOf(cutoff.toString()))

    private fun insertRow(
        db: SQLiteDatabase,
        ts: Long,
        sender: String,
        body: String,
        pkg: String,
        hash: String,
    ): Boolean {
        val cv = ContentValues(5)
        cv.put("ts", ts)
        cv.put("sender", sender)
        cv.put("body", body)
        cv.put("pkg", pkg)
        cv.put("hash", hash)
        return db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** 按内容哈希去重插入。返回 true 表示确实新增了一条。 */
    fun insert(ts: Long, sender: String, body: String, pkg: String, hash: String): Boolean =
        insertRow(writableDatabase, ts, sender, body, pkg, hash)

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 最新一条的时间。0 表示库里还没有数据。用来判断监听是不是还活着。 */
    fun latestTs(): Long {
        readableDatabase.rawQuery("SELECT MAX(ts) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }

    /**
     * 组合筛选查询，列表、文本框、复制、导出全都走这一个入口，
     * 保证「看到的就是复制到的」。
     *
     * @param keyword 空格分隔多个词，全部满足才算命中；以 @ 开头的词只匹配发信人
     * @param pkg     null 表示不限应用
     * @param sender  null 表示不限发信人
     * @param from    ts >= from（0 表示不限）
     * @param to      ts <= to（Long.MAX_VALUE 表示不限）
     * @param desc    true 按时间倒序（列表用），false 正序（生成文本用，读起来顺）
     */
    fun queryFiltered(
        keyword: String = "",
        pkg: String? = null,
        sender: String? = null,
        from: Long = 0L,
        to: Long = Long.MAX_VALUE,
        limit: Int = Int.MAX_VALUE,
        desc: Boolean = true,
    ): List<Message> {
        val (where, args) = filterOf(keyword, pkg, sender, from, to)
        val order = if (desc) "ORDER BY ts DESC, _id DESC" else "ORDER BY ts ASC, _id ASC"
        val limitSql = if (limit == Int.MAX_VALUE) "" else " LIMIT $limit"
        return query("SELECT * FROM $TABLE$where $order$limitSql", args)
    }

    fun countFiltered(
        keyword: String = "",
        pkg: String? = null,
        sender: String? = null,
        from: Long = 0L,
        to: Long = Long.MAX_VALUE,
    ): Int {
        val (where, args) = filterOf(keyword, pkg, sender, from, to)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE$where", args).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * 某时间范围内各来源应用的消息数，条数多的在前。给「按应用筛选」弹窗用。
     * keyword 与列表共用同一套条件，否则搜索时的条数会和列表对不上。
     */
    fun countsByPkg(
        keyword: String = "",
        from: Long = 0L,
        to: Long = Long.MAX_VALUE,
    ): List<Pair<String, Int>> = countsBy("pkg", keyword, from, to)

    /** 同上，按发信人统计。给「按人」视图的筛选弹窗用。 */
    fun countsBySender(
        keyword: String = "",
        from: Long = 0L,
        to: Long = Long.MAX_VALUE,
    ): List<Pair<String, Int>> = countsBy("sender", keyword, from, to)

    private fun countsBy(
        column: String,
        keyword: String,
        from: Long,
        to: Long,
    ): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val (where, args) = filterOf(keyword, null, null, from, to)
        val join = if (where.isEmpty()) " WHERE" else " AND"
        val sql = "SELECT $column, COUNT(*) FROM $TABLE$where$join $column <> '' " +
            "GROUP BY $column ORDER BY COUNT(*) DESC"
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
        }
        return out
    }

    /** 按 id 批量取，按时间正序。给「从选中消息生成待办」用。 */
    fun queryByIds(ids: Collection<Long>): List<Message> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Message>()
        // 分批，避免 SQLite 变量个数上限（默认 999）
        for (chunk in ids.chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            out.addAll(query("SELECT * FROM $TABLE WHERE _id IN ($marks)", args))
        }
        // 分块查回来的顺序是乱的，统一按时间重排
        return out.sortedWith(compareBy({ it.ts }, { it.id }))
    }

    /** 最近若干条，按时间倒序（新的在前）。 */
    fun queryLatest(limit: Int): List<Message> =
        query("SELECT * FROM $TABLE ORDER BY ts DESC, _id DESC LIMIT $limit")

    /** 全部消息，按时间正序。 */
    fun queryAll(): List<Message> =
        query("SELECT * FROM $TABLE ORDER BY ts ASC, _id ASC")

    /** 某时间点之后的消息，按时间正序。 */
    fun querySince(startTs: Long): List<Message> =
        query("SELECT * FROM $TABLE WHERE ts >= $startTs ORDER BY ts ASC, _id ASC")

    /** 记录过的来源应用包名。 */
    fun packages(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT pkg FROM $TABLE WHERE pkg <> ''", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /** 删除早于 cutoff 的记录，返回删掉的条数。 */
    fun deleteOlderThan(cutoff: Long): Int =
        writableDatabase.delete(TABLE, "ts < ?", arrayOf(cutoff.toString()))

    /** 按 id 批量删除。 */
    fun deleteByIds(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        var total = 0
        // 分批，避免 SQLite 变量个数上限（默认 999）
        for (chunk in ids.chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            total += writableDatabase.delete(TABLE, "_id IN ($marks)", args)
        }
        return total
    }

    fun clear() {
        writableDatabase.delete(TABLE, null, null)
        // 状态表必须一起清：留着它，接下来那些通知里已经被记过的内容
        // 会被当成旧消息跳过，清空之后就再也补不回来了
        writableDatabase.delete(STATE_TABLE, null, null)
    }

    /**
     * 拼 WHERE 子句。时间戳是 Long，直接内联——不存在注入风险，
     * 而且避免了 SQLite 把 TEXT 参数和 INTEGER 列做隐式转换。
     *
     * 多关键词是 AND 关系：搜「顺丰 取件」只出同时含这两个词的消息。
     * 以 @ 开头的词只匹配发信人，用来把某个人的话单独捞出来。
     */
    private fun filterOf(
        keyword: String,
        pkg: String?,
        sender: String?,
        from: Long,
        to: Long,
    ): Pair<String, Array<String>?> {
        val conds = ArrayList<String>()
        val args = ArrayList<String>()
        if (keyword.isNotEmpty()) {
            for (token in keyword.split(' ', '\n', '\t').filter { it.isNotEmpty() }.take(8)) {
                if (token.length > 1 && token.startsWith("@")) {
                    conds.add("sender LIKE ?")
                    args.add("%" + token.substring(1) + "%")
                } else {
                    conds.add("(sender LIKE ? OR body LIKE ? OR pkg LIKE ?)")
                    val like = "%$token%"
                    args.add(like)
                    args.add(like)
                    args.add(like)
                }
            }
        }
        if (!pkg.isNullOrEmpty()) {
            conds.add("pkg = ?")
            args.add(pkg)
        }
        if (!sender.isNullOrEmpty()) {
            conds.add("sender = ?")
            args.add(sender)
        }
        if (from > 0L) conds.add("ts >= $from")
        if (to < Long.MAX_VALUE) conds.add("ts <= $to")
        val where = if (conds.isEmpty()) "" else " WHERE " + conds.joinToString(" AND ")
        return where to (if (args.isEmpty()) null else args.toTypedArray())
    }

    private fun query(sql: String, args: Array<String>? = null): List<Message> {
        val out = ArrayList<Message>()
        readableDatabase.rawQuery(sql, args).use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iTs = c.getColumnIndexOrThrow("ts")
            val iSender = c.getColumnIndexOrThrow("sender")
            val iBody = c.getColumnIndexOrThrow("body")
            val iPkg = c.getColumnIndexOrThrow("pkg")
            while (c.moveToNext()) {
                out.add(
                    Message(
                        c.getLong(iId),
                        c.getLong(iTs),
                        c.getString(iSender),
                        c.getString(iBody),
                        c.getString(iPkg) ?: "",
                    )
                )
            }
        }
        return out
    }

    companion object {
        private const val NAME = "wxmsglog.db"
        private const val VERSION = 8
        private const val TABLE = "msg"
        private const val STATE_TABLE = "notestate"
        private const val TODO_TABLE = "todo"
        private const val REPORT_TABLE = "report"
        private const val VEC_TABLE = "vec"

        /** 分享进来的内容用这个包名做标记，不指向任何真实应用。 */
        const val SHARE_PKG = "com.wxmsglog.share"

        /** 每个通知 key 最多记这么多条指纹，够覆盖通知里能显示的那一段。 */
        private const val STATE_LIMIT = 60

        /** 状态表为空时，从库里取这么多条最近记录回填余额。 */
        private const val SEED_LIMIT = 80

        @Volatile
        private var instance: MessageDb? = null

        fun get(context: Context): MessageDb {
            val existing = instance
            if (existing != null) return existing
            synchronized(this) {
                val again = instance
                if (again != null) return again
                val created = MessageDb(context)
                instance = created
                return created
            }
        }
    }
}
