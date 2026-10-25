package com.wxmsglog

import android.content.Context
import android.util.Log

/**
 * 语义搜索。
 *
 * 和关键字搜索是两条路：关键字比「字面」，这一层比「意思」。
 * 搜「那个钱的事」，关键字找不到「报销还没交」，但向量能。
 *
 * 底层是 bge-small-zh：512 维、46M、一条几毫秒。它不是生成模型，
 * 不会写字，只会把一句话压成一串数字。两句话意思越近，向量夹角越小。
 *
 * 所有向量在入库时就做了 L2 归一化，所以检索时一次点积就是余弦相似度，
 * 不用再算模长。
 */
object SemanticSearch {

    private const val TAG = "WxVec"

    /** 低于这个相似度的结果直接丢掉。不设阈值会把整库的废话都匹配出来。 */
    private const val MIN_SCORE = 0.32f

    data class Hit(val msg: Message, val score: Float)

    /** 建索引时一次处理多少条。50 条大约半秒，够快又不会太久看不到进度。 */
    const val BATCH = 50

    fun available(context: Context): Boolean =
        ModelStore.embedModel(context) != null && Llm.available()

    fun indexedCount(context: Context): Int =
        runCatching { MessageDb.get(context).vecCount() }.getOrDefault(0)

    /**
     * 建一批索引。阻塞调用。
     * @return 这一批实际处理了多少条；0 表示没有可处理的了，或者模型起不来。
     */
    fun indexBatch(context: Context, limit: Int = BATCH): Int {
        val model = ModelStore.embedModel(context) ?: return 0
        if (Llm.loadEmbed(model) != null) {
            Log.e(TAG, "向量模型加载失败")
            return 0
        }
        val db = MessageDb.get(context)
        val batch = try {
            db.unembeddedMessages(limit)
        } catch (t: Throwable) {
            return 0
        }
        if (batch.isEmpty()) return 0

        var done = 0
        for (m in batch) {
            // 连发信人一起算：搜索「张三说要改方案」时人名也是语义的一部分
            val text = (m.sender + " " + m.body).trim()
            val v = Llm.embed(text) ?: continue
            runCatching { db.saveVec(m.id, v) }
            done++
        }
        return done
    }

    /** 还要跑多少批才能建完。界面用来显示进度。 */
    fun estimatePendingBatches(context: Context): Int {
        val total = runCatching { MessageDb.get(context).unembeddedMessages(1) }.getOrDefault(emptyList())
        if (total.isEmpty()) return 0
        // 只知道「还有」，不知道具体多少条，用总数估算个大概
        val all = runCatching {
            MessageDb.get(context).countFiltered("", null, null, 0L, Long.MAX_VALUE)
        }.getOrDefault(0)
        val vec = indexedCount(context)
        return ((all - vec + BATCH - 1) / BATCH).coerceAtLeast(0)
    }

    /**
     * 语义检索。阻塞调用。
     *
     * @return 按相似度降序，最多 limit 条；模型没就绪或一条索引都没有时返回空。
     */
    fun search(context: Context, query: String, limit: Int = 30): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val model = ModelStore.embedModel(context) ?: return emptyList()
        if (Llm.loadEmbed(model) != null) return emptyList()

        val qv = Llm.embed(q) ?: return emptyList()
        val db = MessageDb.get(context)

        val vecs = try {
            db.allVecs()
        } catch (t: Throwable) {
            return emptyList()
        }
        if (vecs.isEmpty()) return emptyList()

        // 全量过一遍。一万条 512 维大约几百万次乘加，手机上是几十毫秒，
        // 不值得为它建专门的近似索引——那种索引本身也要占内存。
        val scored = ArrayList<Pair<Long, Float>>(vecs.size)
        for ((id, v) in vecs) {
            if (v.size != qv.size) continue
            val s = dot(qv, v)
            if (s >= MIN_SCORE) scored.add(id to s)
        }
        if (scored.isEmpty()) return emptyList()

        scored.sortByDescending { it.second }
        val top = scored.take(limit)
        val msgs = try {
            db.queryByIds(top.map { it.first }).associateBy { it.id }
        } catch (t: Throwable) {
            return emptyList()
        }
        return top.mapNotNull { (id, s) ->
            msgs[id]?.let { Hit(it, s) }
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0.0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
