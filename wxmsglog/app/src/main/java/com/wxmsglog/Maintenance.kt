package com.wxmsglog

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * 数据维护。目前只做一件事：按「保留天数」删旧记录。
 *
 * 为什么需要：通知监听是一直在跑的，一年下来几十万条很正常。
 * 不清理的话搜索会越来越慢，数据库文件也会一直涨。
 */
object Maintenance {

    private val PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(12)

    /**
     * 到期才真删。启动 App、服务被创建时各调一次，成本可忽略。
     * @return 删掉的条数
     */
    fun pruneIfDue(context: Context, db: MessageDb): Int {
        val days = Prefs.retentionDays(context)
        if (days <= 0) return 0
        val now = System.currentTimeMillis()
        val last = Prefs.lastPrune(context)
        if (last > 0 && now - last < PRUNE_INTERVAL_MS) return 0
        Prefs.setLastPrune(context, now)
        val cutoff = now - TimeUnit.DAYS.toMillis(days.toLong())
        return runCatching { db.deleteOlderThan(cutoff) }.getOrDefault(0)
    }
}
