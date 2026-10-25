package com.wxmsglog

import android.content.Context
import android.content.SharedPreferences

/** 本地配置：记录哪些应用、保留多久、导出成什么格式、要不要加锁。 */
object Prefs {

    const val WECHAT = "com.tencent.mm"

    private const val FILE = "cfg"
    private const val KEY_ALL = "record_all"
    private const val KEY_APPS = "apps"
    private const val KEY_LOCK = "lock_enabled"
    private const val KEY_SECURE = "block_screenshot"
    private const val KEY_RETENTION = "retention_days"
    private const val KEY_FORMAT = "export_format"
    private const val KEY_LAST_PRUNE = "last_prune"
    private const val KEY_PH_COUNT = "placeholder_count"
    private const val KEY_PH_LAST = "placeholder_last"
    private const val KEY_PH_ACK = "placeholder_ack"
    private const val KEY_MODEL = "model_path"
    private const val KEY_AI_TODO = "ai_todo_enabled"

    /** 保留天数，0 表示永久保留。 */
    const val RETENTION_KEEP_ALL = 0

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 记录所有应用的通知。开启后忽略白名单。 */
    fun recordAll(context: Context): Boolean = sp(context).getBoolean(KEY_ALL, false)

    fun setRecordAll(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_ALL, value).apply()
    }

    /** 白名单。默认只有微信，与旧版本行为一致。 */
    fun apps(context: Context): Set<String> {
        val stored = sp(context).getStringSet(KEY_APPS, null) ?: return setOf(WECHAT)
        return HashSet(stored)
    }

    fun setApps(context: Context, value: Set<String>) {
        // getStringSet 返回的是内部集合，不能直接改，所以拷贝一份
        sp(context).edit().putStringSet(KEY_APPS, HashSet(value)).apply()
    }

    fun enabled(context: Context, pkg: String): Boolean =
        if (recordAll(context)) true else apps(context).contains(pkg)

    /** 应用锁（指纹 / 锁屏密码）。 */
    fun lockEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_LOCK, false)

    fun setLockEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_LOCK, value).apply()
    }

    /**
     * 是否禁止截屏/录屏（FLAG_SECURE）。默认关——多数人偶尔要截图分享，
     * 只有确实怕别人翻手机时才值得开。
     */
    fun blockScreenshot(context: Context): Boolean = sp(context).getBoolean(KEY_SECURE, false)

    fun setBlockScreenshot(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_SECURE, value).apply()
    }

    /** 自动清理：超过这么多天的记录会被删掉。0 = 永不删除。 */
    fun retentionDays(context: Context): Int = sp(context).getInt(KEY_RETENTION, 0)

    fun setRetentionDays(context: Context, days: Int) {
        sp(context).edit().putInt(KEY_RETENTION, days).apply()
    }

    /** 导出格式：md / txt / csv / json。 */
    fun exportFormat(context: Context): String = sp(context).getString(KEY_FORMAT, "md") ?: "md"

    fun setExportFormat(context: Context, value: String) {
        sp(context).edit().putString(KEY_FORMAT, value).apply()
    }

    /** 上次自动清理的时间，避免每次启动都扫一遍库。 */
    fun lastPrune(context: Context): Long = sp(context).getLong(KEY_LAST_PRUNE, 0L)

    fun setLastPrune(context: Context, at: Long) {
        sp(context).edit().putLong(KEY_LAST_PRUNE, at).apply()
    }

    // ---------- 「只有空壳通知」的统计 ----------
    // 微信关掉「通知显示消息详情」后，正文只剩「你收到一条新消息」，
    // 抓不到内容的通知不入库，但也不能装作没发生——记个数，界面上提醒。

    /** 至今累计抓到多少条空壳通知。 */
    fun placeholderCount(context: Context): Int = sp(context).getInt(KEY_PH_COUNT, 0)

    fun placeholderLast(context: Context): Long = sp(context).getLong(KEY_PH_LAST, 0L)

    /** 用户已经看过并点掉的那个计数，比它大才需要重新提示。 */
    fun placeholderAck(context: Context): Int = sp(context).getInt(KEY_PH_ACK, 0)

    fun bumpPlaceholder(context: Context, n: Int) {
        if (n <= 0) return
        val p = sp(context)
        p.edit()
            .putInt(KEY_PH_COUNT, p.getInt(KEY_PH_COUNT, 0) + n)
            .putLong(KEY_PH_LAST, System.currentTimeMillis())
            .apply()
    }

    fun ackPlaceholder(context: Context) {
        val p = sp(context)
        p.edit().putInt(KEY_PH_ACK, p.getInt(KEY_PH_COUNT, 0)).apply()
    }

    // ---------- 端侧模型 ----------

    /** 当前选用的模型文件绝对路径。空表示还没装模型。 */
    fun modelPath(context: Context): String? = sp(context).getString(KEY_MODEL, null)

    fun setModelPath(context: Context, value: String?) {
        sp(context).edit().putString(KEY_MODEL, value).apply()
    }

    /**
     * 是否让模型参与待办识别。默认关。
     *
     * 默认关是有意的：模型要占 1.5G 内存、每次推理几百毫秒，而正则那套
     * 对绝大多数消息已经够准。只有用户自己装了模型、又确实觉得漏了东西，
     * 才值得打开。
     */
    fun aiTodoEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_AI_TODO, false)

    fun setAiTodoEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_AI_TODO, value).apply()
    }
}
