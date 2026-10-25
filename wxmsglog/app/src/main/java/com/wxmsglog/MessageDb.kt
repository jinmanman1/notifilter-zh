package com.wxmsglog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地存储。用最原始的 SQLiteOpenHelper，不引 Room——
 * 少一层注解处理，CI 上就少一类可能失败的地方。
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
                "hash TEXT NOT NULL UNIQUE)"
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_ts ON $TABLE(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 目前只有 v1，升级逻辑留空
    }

    /** 按内容哈希去重插入。返回 true 表示确实新增了一条。 */
    fun insert(ts: Long, sender: String, body: String, hash: String): Boolean {
        val cv = ContentValues(4)
        cv.put("ts", ts)
        cv.put("sender", sender)
        cv.put("body", body)
        cv.put("hash", hash)
        val rowId = writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        return rowId != -1L
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
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

    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }

    private fun query(sql: String): List<Message> {
        val out = ArrayList<Message>()
        readableDatabase.rawQuery(sql, null).use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iTs = c.getColumnIndexOrThrow("ts")
            val iSender = c.getColumnIndexOrThrow("sender")
            val iBody = c.getColumnIndexOrThrow("body")
            while (c.moveToNext()) {
                out.add(Message(c.getLong(iId), c.getLong(iTs), c.getString(iSender), c.getString(iBody)))
            }
        }
        return out
    }

    companion object {
        private const val NAME = "wxmsglog.db"
        private const val VERSION = 1
        private const val TABLE = "msg"

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
